package com.hilight.studio

import android.content.Context
import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import org.json.JSONArray
import java.io.File

/**
 * File channel used by the ADB transport.
 *
 * Cross-UID binder is not available there: the helper runs as the adb shell UID (2000), or as uid 0
 * for the root transport, and a shell process that touches a ContentProvider gets killed by
 * ActivityManager. So state and status are exchanged as two small JSON files. (The Shizuku transport
 * has a real binder and does not use any of this.)
 */
object Bridge {

    private const val TAG = "HiLightBridge"
    const val DIR_NAME = "hilight"
    const val DEVICE_DIR = "/storage/emulated/0/Android/data/com.hilight.studio/files/hilight"
    private val statusCache = BridgeStatusCache()

    private fun dir(ctx: Context): File =
        File(ctx.getExternalFilesDir(null), DIR_NAME).apply { if (!exists()) mkdirs() }

    fun stateFile(ctx: Context) = File(dir(ctx), "state.json")
    private fun statusFile(ctx: Context) = File(dir(ctx), "helper_status.json")

    /**
     * Both files must be created by the app, not by the helper.
     *
     * On external storage a file keeps the uid of whoever created it, and the app has no access to a
     * file the shell UID created — but the shell can happily write into a file the app owns. So the
     * app pre-creates both, and the helper only ever overwrites them in place.
     */
    fun ensureFiles(ctx: Context) {
        runCatching {
            dir(ctx)
            stateFile(ctx).let { if (!it.exists()) it.writeText("{\"enabled\":false}") }
            statusFile(ctx).let { if (!it.exists()) it.writeText("{}") }
        }.onFailure { Log.w(TAG, "could not prepare bridge files", it) }
    }

    /** Builds the state document both transports understand. */
    fun stateJson(
        enabled: Boolean,
        priority: Int,
        ambient: Ambient,
        alert: JSONObject?,
        ambientTimeoutMs: Int = Limits.AMBIENT_DEFAULT_MS,
        /** true only for deliberate user action; see Engine's arm handling */
        arm: Boolean = true,
        /** scales every frame, ambient and alert alike — used by dimmed quiet hours */
        dim: Float = 1f,
        privacyRules: List<PrivacyRule> = emptyList(),
        privacyObserverEnabled: Boolean = false,
        privacyOutputEnabled: Boolean = privacyObserverEnabled,
        stateRevision: Long = 0,
        /** Opaque one-shot id; absent from every ordinary renderer document. */
        manualBlackClearRequestId: Long? = null,
    ): String =
        JSONObject().apply {
            put("v", 2)
            put("stateRevision", stateRevision)
            put("enabled", enabled)
            put("priority", priority)
            put("ambientTimeoutMs", ambientTimeoutMs)
            put("arm", arm)
            put("dim", dim.toDouble())
            put("ambient", ambient.toJson())
            put("privacyObserverEnabled", privacyObserverEnabled)
            put("privacyOutputEnabled", privacyOutputEnabled)
            put("privacyRules", JSONArray().also { out ->
                privacyRules.filter { it.enabled }.forEach { out.put(it.toRendererJson()) }
            })
            if (alert != null) put("alert", alert)
            if (manualBlackClearRequestId != null) {
                put("manualBlackClearRequestId", manualBlackClearRequestId)
            }
        }.toString()

    /**
     * Smallest backward-compatible state that can only disable output. Used solely as a best-effort
     * mitigation for a rejected Shizuku daemon while its disconnect remains unconfirmed.
     */
    fun incompatibleRendererSafeIdleJson(stateRevision: Long): String =
        JSONObject().apply {
            put("v", 2)
            put("stateRevision", stateRevision)
            put("enabled", false)
            put("arm", false)
            put("privacyObserverEnabled", false)
            put("privacyOutputEnabled", false)
        }.toString()

    /**
     * Replaces the state document the ADB helper polls.
     *
     * Synchronized because the scratch file has one fixed name: two threads could each write it and
     * then rename, so whichever payload lost the write race was the one promoted and the other push
     * vanished. Only this app writes the state file, so serialising here is enough.
     */
    @Synchronized
    fun writeState(ctx: Context, json: String) {
        // Never let a bridge failure take the UI down with it.
        runCatching {
            val target = stateFile(ctx)
            val tmp = File(target.parentFile, target.name + ".tmp")
            tmp.writeText(json)
            if (!tmp.renameTo(target)) {
                // FUSE can refuse the rename; a direct write is fine because the helper reads whole
                // files and simply retries when a parse fails.
                target.writeText(json)
                tmp.delete()
            }
        }.onFailure { Log.w(TAG, "state write failed", it) }
    }

    /**
     * Scopes a file-bridge document to one helper process. A second helper polling the same file sees
     * the mismatch and can only apply a disabled state; it cannot become a second visible writer.
     * Disabled handoff documents deliberately remain unscoped so every leftover helper releases.
     */
    fun targetRenderer(json: String, rendererInstanceId: String): String {
        if (!BridgeStatusCache.validInstanceId(rendererInstanceId)) return json
        return runCatching {
            JSONObject(json).put("bridgeRendererInstanceId", rendererInstanceId).toString()
        }.getOrDefault(json)
    }

    /** Exact valid renderer target recorded in a state document, or null if absent/malformed. */
    fun targetedRendererInstanceId(json: String): String? = runCatching {
        JSONObject(json).optString("bridgeRendererInstanceId", "")
            .takeIf(BridgeStatusCache::validInstanceId)
    }.getOrNull()

    /** True only when this state document is already scoped to this exact renderer process. */
    fun stateTargetsRenderer(json: String, rendererInstanceId: String): Boolean =
        BridgeStatusCache.validInstanceId(rendererInstanceId) &&
            targetedRendererInstanceId(json) == rendererInstanceId

    fun readStatus(ctx: Context): HelperStatus {
        val f = statusFile(ctx)
        val raw = runCatching { f.takeIf(File::exists)?.readText() }
            .onFailure { Log.w(TAG, "unreadable status", it) }
            .getOrNull()
        return statusCache.read(raw, System.currentTimeMillis())
    }

    /** Used after an exact process-exit proof so a cached heartbeat cannot impersonate a successor. */
    fun forgetStatusInstance(rendererInstanceId: String) {
        statusCache.forget(rendererInstanceId)
    }

    internal class BridgeStatusCache {
        private data class Good(val timestampMs: Long, val status: HelperStatus)
        private data class ProcessIdentity(
            val pid: Int,
            val owner: String,
            val rendererInstanceId: String,
        )

        private var lastGood: Good? = null
        /** A successor heartbeat must never hide a process that failed its final close. */
        private var unreleasedFatal: Good? = null
        /** Exact verified-exited identities whose stale on-disk heartbeat must not resurrect them. */
        private val forgotten = LinkedHashMap<ProcessIdentity, Long>()

        @Synchronized
        fun read(raw: String?, nowMs: Long): HelperStatus {
            val parsedFromDisk = parseGood(raw)
            val parsed = parsedFromDisk?.takeUnless {
                val forgottenAt = forgotten[it.identity()]
                forgottenAt != null && it.timestampMs <= forgottenAt
            }
            parsed?.also {
                lastGood = it
                val pinned = unreleasedFatal
                if (pinned != null && pinned.identity() == it.identity()) {
                    // A later heartbeat from the exact same process refreshes liveness, but even a
                    // success-shaped sample cannot erase the fatal status without verified exit.
                    unreleasedFatal = Good(it.timestampMs, pinned.status)
                } else if (pinned == null && it.status.blackClearUnreleasedFatal &&
                    validInstanceId(it.status.rendererInstanceId)
                ) {
                    unreleasedFatal = it
                }
            }
            val good = unreleasedFatal ?: lastGood
                ?: return HelperStatus(alive = false, identityResolved = parsedFromDisk != null)
            val age = nowMs - good.timestampMs
            return good.status.copy(
                // The helper writes once a second. A torn read cannot erase its identity; only an
                // explicitly old heartbeat crosses this dead threshold.
                alive = age in -5_000..STATUS_STALE_AFTER_MS,
                ageMs = age,
                // Cached fields remain useful for fencing, but they do not turn a torn *fresh* read
                // into a new process-identity proof. Store performs a bounded reread before acting.
                identityResolved = parsedFromDisk != null,
            )
        }

        @Synchronized
        fun forget(rendererInstanceId: String) {
            unreleasedFatal?.takeIf {
                it.status.rendererInstanceId == rendererInstanceId
            }?.let(::rememberForgotten)
            lastGood?.takeIf {
                it.status.rendererInstanceId == rendererInstanceId
            }?.let(::rememberForgotten)
            if (unreleasedFatal?.status?.rendererInstanceId == rendererInstanceId) {
                unreleasedFatal = null
            }
            if (lastGood?.status?.rendererInstanceId == rendererInstanceId) lastGood = null
        }

        private fun Good.identity() = ProcessIdentity(
            pid = status.pid,
            owner = status.owner,
            rendererInstanceId = status.rendererInstanceId,
        )

        private fun rememberForgotten(good: Good) {
            val identity = good.identity()
            forgotten[identity] = maxOf(forgotten[identity] ?: Long.MIN_VALUE, good.timestampMs)
            while (forgotten.size > MAX_FORGOTTEN_IDENTITIES) {
                forgotten.remove(forgotten.entries.first().key)
            }
        }

        private fun parseGood(raw: String?): Good? = try {
            if (raw.isNullOrBlank()) return null
            val o = JSONObject(raw)
            val timestamp = o.optLong("ts", 0)
            val pid = o.optInt("pid", -1)
            val uid = o.optInt("uid", -1)
            val owner = o.optString("owner", "")
            // Reject syntactically valid fragments such as the app-created `{}` placeholder. They
            // are not a heartbeat and must never replace a live cached process identity.
            if (timestamp <= 0 || pid <= 0 || !ownerMatchesUid(owner, uid)) return null
            val instanceId = o.optString("rendererInstanceId", "")
            // v1.0.9 helpers always have an instance id. Their status is safe to cache only when
            // every field used to prove cleanup, manual acknowledgement, release and renderer
            // identity is present. Engine.status() deliberately returns its partial object if one
            // getter throws, so a syntactically valid fragment can otherwise erase the last
            // complete proof. v1.0.8 status had no instance id and remains readable for the narrow
            // legacy shutdown path.
            val currentIdentityWithoutInstance = instanceId.isEmpty() &&
                (o.has("rendererInstanceId") || CURRENT_IDENTITY_FIELDS.any { o.has(it) })
            if (currentIdentityWithoutInstance || (instanceId.isNotEmpty() &&
                (!validInstanceId(instanceId) || CURRENT_STATUS_REQUIRED_FIELDS.any { !o.has(it) })
            )) {
                return null
            }
            Good(
                timestamp,
                HelperStatus(
                    alive = true,
                    ageMs = 0,
                    pid = pid,
                    uid = uid,
                    owner = owner,
                    rendererInstanceId = instanceId,
                    identityResolved = true,
                    ledCount = o.optInt("ledCount", 0),
                    sessionOpen = o.optBoolean("session", false),
                    blackClearPending = o.optBoolean("blackClearPending", false),
                    blackClearTerminal = o.optBoolean("blackClearTerminal", false),
                    blackClearResult = o.optString("blackClearResult", "not_requested"),
                    blackClearAttemptResult = o.optString(
                        "blackClearAttemptResult",
                        "not_requested",
                    ),
                    blackClearStage = o.optString("blackClearStage", "idle"),
                    blackClearTimestampElapsedMs = o.optLong("blackClearTimestampElapsedMs", 0),
                    blackClearCycleId = o.optLong("blackClearCycleId", 0),
                    blackClearCycleSource = o.optString("blackClearCycleSource", "automatic"),
                    blackClearAttemptsUsed = o.optInt("blackClearAttemptsUsed", 0),
                    blackClearAttemptsRemaining = o.optInt("blackClearAttemptsRemaining", 0),
                    blackClearStopAttemptAvailable = o.optBoolean(
                        "blackClearStopAttemptAvailable",
                        false,
                    ),
                    blackClearCloseFailures = o.optInt("blackClearCloseFailures", 0),
                    blackClearUnreleasedFatal = o.optBoolean(
                        "blackClearUnreleasedFatal",
                        false,
                    ),
                    lightMinUpdatePeriodMs = o.optLong("lightMinUpdatePeriodMs", 0),
                    blackClearStrategy = o.optString("blackClearStrategy", "unknown"),
                    blackClearStrategyVersion = o.optInt("blackClearStrategyVersion", 0),
                    rendererVersionCode = o.optInt("rendererVersionCode", -1),
                    rendererVersionName = o.optString("rendererVersionName", ""),
                    rendererContractVersion = o.optInt("rendererContractVersion", -1),
                    rendererImplementationRevision = o.optInt(
                        "rendererImplementationRevision",
                        -1,
                    ),
                    rendererStatusSchemaVersion = o.optInt(
                        "rendererStatusSchemaVersion",
                        o.optInt("version", -1),
                    ),
                    rendererClearAlgorithmVersion = o.optInt(
                        "rendererClearAlgorithmVersion",
                        -1,
                    ),
                    rendererServiceVersion = o.optInt("rendererServiceVersion", -1),
                    // AdbHelper reaches its status loop only after Engine.start() succeeds.
                    rendererReady = o.optBoolean("rendererReady", true),
                    mode = o.optString("mode", "-"),
                    ambientRemainingMs = o.optLong("ambientRemainingMs", 0),
                    ambientHeld = o.optBoolean("ambientHeld", false),
                    resting = o.optBoolean("resting", false),
                    dutyPct = o.optInt("dutyPct", 0),
                    appliedStateRevision = o.optLong("appliedStateRevision", 0),
                    receivedStateRevision = o.optLong(
                        "receivedStateRevision",
                        o.optLong("appliedStateRevision", 0),
                    ),
                    settledStateRevision = o.optLong("settledStateRevision", 0),
                    releasedStateRevision = o.optLong("releasedStateRevision", 0),
                    lastSeenManualBlackClearRequestId = o.optLong(
                        "lastSeenManualBlackClearRequestId",
                        0,
                    ),
                    lastAcceptedManualBlackClearRequestId = o.optLong(
                        "lastAcceptedManualBlackClearRequestId",
                        0,
                    ),
                    privacyObserverEnabled = o.optBoolean("privacyObserverEnabled", false),
                    privacyObserverState = o.optString("privacyObserverState", "stopped"),
                    privacyPhase = o.optString("privacyPhase", "inactive"),
                ),
            )
        } catch (_: Throwable) {
            null
        }

        companion object {
            private const val STATUS_STALE_AFTER_MS = 4_000L
            private const val MAX_FORGOTTEN_IDENTITIES = 64
            private const val ROOT_UID = 0
            private const val SHELL_UID = 2000
            private val INSTANCE_ID = Regex("[A-Za-z0-9._:-]{1,96}")
            private val CURRENT_IDENTITY_FIELDS = arrayOf(
                "rendererContractVersion",
                "rendererImplementationRevision",
                "rendererStatusSchemaVersion",
                "rendererClearAlgorithmVersion",
                "blackClearUnreleasedFatal",
            )
            private val CURRENT_STATUS_REQUIRED_FIELDS = arrayOf(
                "ledCount",
                "session",
                "blackClearPending",
                "blackClearTerminal",
                "blackClearUnreleasedFatal",
                "blackClearResult",
                "blackClearAttemptResult",
                "blackClearStage",
                "blackClearTimestampElapsedMs",
                "blackClearCycleId",
                "blackClearCycleSource",
                "blackClearAttemptsUsed",
                "blackClearAttemptsRemaining",
                "blackClearStopAttemptAvailable",
                "blackClearCloseFailures",
                "lightMinUpdatePeriodMs",
                "blackClearStrategy",
                "blackClearStrategyVersion",
                "receivedStateRevision",
                "settledStateRevision",
                "releasedStateRevision",
                "lastSeenManualBlackClearRequestId",
                "lastAcceptedManualBlackClearRequestId",
                "privacyObserverEnabled",
                "privacyObserverState",
                "privacyPhase",
                "rendererContractVersion",
                "rendererImplementationRevision",
                "rendererStatusSchemaVersion",
                "rendererClearAlgorithmVersion",
            )
            fun validInstanceId(value: String): Boolean = INSTANCE_ID.matches(value)
            private fun ownerMatchesUid(owner: String, uid: Int): Boolean =
                (owner == "adb" && uid == SHELL_UID) || (owner == "root" && uid == ROOT_UID)
        }
    }

    /** Builds an alert payload for a rule; [durationMs] of 0 holds until cleared. */
    fun alertJson(
        id: Long,
        pattern: Pattern,
        color: Int,
        durationMs: Int,
        speedMs: Int,
        brightness: Float,
        source: AlertSource,
    ): JSONObject = JSONObject().apply {
        put("id", id)
        put("pattern", pattern.key)
        put("color", color.toUInt().toLong())
        put("durationMs", durationMs)
        put("speedMs", speedMs)
        put("brightness", brightness.toDouble())
        put("source", source.key)
        put("spread", true)
        put("randomIntervalMs", 500)
        put("randomPerLed", true)
        put("randomSmooth", true)
    }

    /** Monotonic-ish alert ids so the renderer can tell a new alert from a re-push. */
    fun nextAlertId(): Long = SystemClock.elapsedRealtime()
}
