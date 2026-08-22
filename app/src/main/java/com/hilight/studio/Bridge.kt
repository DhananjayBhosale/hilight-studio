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

    fun readStatus(ctx: Context): HelperStatus {
        val f = statusFile(ctx)
        if (!f.exists()) return HelperStatus(alive = false)
        return try {
            val o = JSONObject(f.readText())
            val age = System.currentTimeMillis() - o.optLong("ts", 0)
            HelperStatus(
                // the helper writes a heartbeat every second
                alive = age in -5_000..4_000,
                ageMs = age,
                pid = o.optInt("pid", -1),
                uid = o.optInt("uid", -1),
                owner = o.optString("owner", "adb"),
                ledCount = o.optInt("ledCount", 0),
                sessionOpen = o.optBoolean("session", false),
                mode = o.optString("mode", "-"),
                ambientRemainingMs = o.optLong("ambientRemainingMs", 0),
                ambientHeld = o.optBoolean("ambientHeld", false),
                resting = o.optBoolean("resting", false),
                dutyPct = o.optInt("dutyPct", 0),
                appliedStateRevision = o.optLong("appliedStateRevision", 0),
                privacyObserverEnabled = o.optBoolean("privacyObserverEnabled", false),
                privacyObserverState = o.optString("privacyObserverState", "stopped"),
                privacyPhase = o.optString("privacyPhase", "inactive"),
            )
        } catch (t: Throwable) {
            Log.w(TAG, "unreadable status", t)
            HelperStatus(alive = false)
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

    /** The charging gauge as an alert: the rule's look plus the level it should draw, 0..1. */
    fun chargingAlertJson(id: Long, rule: ChargingRule, level: Float): JSONObject = JSONObject().apply {
        put("id", id)
        put("pattern", Pattern.BATTERY.key)
        put("durationMs", rule.durationMs)
        put("speedMs", rule.stepMs)
        put("fill", rule.fill.key)
        put("blinkTip", rule.blinkTip)
        put("brightness", rule.brightness.toDouble())
        put("source", AlertSource.CHARGING.key)
        put("level", level.coerceIn(0f, 1f).toDouble())
        put("byLevel", rule.colorMode == ChargingColorMode.LEVEL)
        put("oddScale", rule.oddLedScale.toDouble())
        if (rule.colorMode == ChargingColorMode.PER_LED) {
            put("colors", JSONArray().also { a -> rule.perLed.forEach { a.put(it.toUInt().toLong()) } })
        } else {
            put("color", rule.color.toUInt().toLong())
        }
    }

    /** Monotonic-ish alert ids so the renderer can tell a new alert from a re-push. */
    fun nextAlertId(): Long = SystemClock.elapsedRealtime()
}
