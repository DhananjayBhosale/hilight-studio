package com.hilight.studio

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.util.UUID

/** Root equivalent of the ADB file-bridge gate: visible state always needs a fresh exact identity. */
internal fun safeRootPush(json: String, status: HelperStatus): AdbPushDecision {
    val currentInstance = status.owner == "root" && status.uid == 0 && status.alive &&
        status.identityResolved && status.rendererReady &&
        status.rendererCompatibility == RendererCompatibility.CURRENT &&
        !status.blackClearUnreleasedFatal &&
        Bridge.BridgeStatusCache.validInstanceId(status.rendererInstanceId)
    if (currentInstance) {
        return AdbPushDecision(
            Bridge.targetRenderer(json, status.rendererInstanceId),
            AdbPushSafety.TARGETED_CURRENT,
        )
    }
    val parsed = runCatching { JSONObject(json) }.getOrNull()
    val explicitlyDisabled = parsed?.has("enabled") == true &&
        !parsed.optBoolean("enabled", true) &&
        !parsed.optBoolean("privacyOutputEnabled", false)
    if (explicitlyDisabled) {
        return AdbPushDecision(json, AdbPushSafety.BROADCAST_DISABLED)
    }
    val revision = parsed?.optLong("stateRevision", 0L)?.coerceAtLeast(0L) ?: 0L
    return AdbPushDecision(
        Bridge.incompatibleRendererSafeIdleJson(revision),
        AdbPushSafety.SANITIZED_DISABLED,
    )
}

internal fun rootStateAfterExactStopFailure(
    sourceOwner: String,
    current: RootBackend.State,
): RootBackend.State = if (sourceOwner == "root") RootBackend.State.ERROR else current

/** Throttled re-check of su presence; never replaces a start failure the user must see. */
internal fun shouldRecheckRootPresence(
    state: RootBackend.State,
    presenceErrored: Boolean,
    sinceLastCheckMs: Long,
): Boolean =
    (state == RootBackend.State.UNAVAILABLE || (state == RootBackend.State.ERROR && presenceErrored)) &&
        sinceLastCheckMs >= ROOT_PRESENCE_RECHECK_MS

internal const val ROOT_PRESENCE_RECHECK_MS = 5_000L

/** A failed exact-stop command, keeping its [RootCommand] exit code for diagnostics. */
internal class RootStopFailed(val code: Int, context: String) :
    IllegalStateException("$context: ${RootCommand.stopFailure(code)}")

internal fun rootStopFailureCode(code: Int): RootFailureCode = when (code) {
    RootCommand.STOP_RENDERER_STUCK -> RootFailureCode.RENDERER_STUCK
    RootCommand.STOP_OTHER_RENDERER -> RootFailureCode.OTHER_RENDERER_RUNNING
    else -> RootFailureCode.COMMAND_FAILED
}

/** A fresh cached heartbeat also protects a live helper during a torn status-file read. */
internal fun rootHeartbeatBlocksRecovery(status: HelperStatus): Boolean =
    status.alive

/** Direct root transport. The existing file bridge and AdbHelper remain the renderer. */
class RootBackend(private val ctx: Context) : Backend {

    enum class State { CHECKING, UNAVAILABLE, AVAILABLE, REQUESTING, STARTING, RUNNING, DENIED, ERROR }

    override val transport = Transport.ROOT
    private val main = Handler(Looper.getMainLooper())
    private val _state = MutableStateFlow(State.CHECKING)
    val state: StateFlow<State> = _state.asStateFlow()

    @Volatile private var ownedPid = -1
    @Volatile private var ownedInstanceId = ""
    @Volatile private var starting = false
    @Volatile private var checkingPresence = false
    @Volatile private var lastPresenceCheckMs = Long.MIN_VALUE / 2
    // ERROR from the su presence check itself (not from a failed start) is safe to re-check.
    @Volatile private var presenceErrored = false
    @Volatile private var lastError: String? = null
    // Retain the last attempt across presence checks; reset only on a new attempt or success.
    @Volatile private var attempt = RootAttemptDiagnostics()

    fun diagnostics(): RootConnectionDiagnostics = RootConnectionDiagnostics(_state.value, starting, attempt)

    var onStateChanged: (() -> Unit)? = null

    init {
        refreshPresence()
    }

    override fun push(json: String) {
        Bridge.writeState(ctx, safeRootPush(json, status()).json)
    }

    override fun status(): HelperStatus {
        val status = Bridge.readStatus(ctx)
        // Preserve an expired heartbeat's exact PID/instance so stop() after an app restart can
        // still prove process exit. Staleness removes readiness, never ownership identity.
        return if (status.pid > 0 && status.owner == "root" && status.uid == 0) status
        else HelperStatus(alive = false, owner = "root")
    }

    fun errorText(): String? = lastError

    /**
     * Re-checks for su while root is unavailable. su can appear after this process started: the
     * root manager grants HiLight later, or finishes mounting after a reboot or app update. A
     * once-per-process check left root unavailable until a hard app restart.
     */
    fun recheckPresenceIfUnavailable(nowMs: Long = SystemClock.elapsedRealtime()) {
        if (shouldRecheckRootPresence(_state.value, presenceErrored, nowMs - lastPresenceCheckMs)) {
            refreshPresence()
        }
    }

    fun refreshPresence() {
        if (starting || checkingPresence) return
        checkingPresence = true
        lastPresenceCheckMs = SystemClock.elapsedRealtime()
        Thread({
            try {
                if (_state.value == State.RUNNING) {
                    // An explicit retry may recover a lost renderer without force-closing the app.
                    // A fresh or returning heartbeat cancels recovery. An expired cache plus a
                    // persistently torn file must still allow Retry: AVAILABLE delegates restart
                    // to Store's staged-idle and exact PID/instance stop fence, not straight to launch.
                    repeat(COLD_STATUS_SAMPLES) { sample ->
                        val current = status()
                        if (rootHeartbeatBlocksRecovery(current)) return@Thread
                        if (sample + 1 < COLD_STATUS_SAMPLES) {
                            Thread.sleep(COLD_STATUS_SAMPLE_INTERVAL_MS)
                        }
                    }
                }
                val available = runPlain("command -v su").code == 0
                update(if (available) State.AVAILABLE else State.UNAVAILABLE)
            } catch (t: Throwable) {
                lastError = t.message ?: t.javaClass.simpleName
                update(State.ERROR, fromPresenceCheck = true)
            } finally {
                checkingPresence = false
            }
        }, "hilight-root-check").apply { isDaemon = true }.start()
    }

    /** Starts only after Store has staged an output-disabled state with [stagedRevision]. */
    fun ensureStarted(stagedRevision: Long, onComplete: (Boolean) -> Unit) {
        if (starting) return
        starting = true
        attempt = RootAttemptDiagnostics(RootStartupPhase.PERMISSION)
        Thread({
            var ok = false
            var phase = "root permission check"
            try {
                update(State.REQUESTING)
                val identity = runSu("id", 60)
                if (identity.code != 0 || !identity.output.contains("uid=0")) {
                    attempt = RootAttemptDiagnostics(RootStartupPhase.PERMISSION, RootFailureCode.PERMISSION_DENIED)
                    lastError = "Root permission was not granted"
                    update(State.DENIED)
                    return@Thread
                }

                phase = "previous renderer cleanup"
                attempt = RootAttemptDiagnostics(RootStartupPhase.CLEANUP)
                update(State.STARTING)
                releaseAndStopBridgeRenderer(stagedRevision)
                val instanceId = "root-${UUID.randomUUID()}"
                phase = "renderer launch"
                attempt = RootAttemptDiagnostics(RootStartupPhase.LAUNCH)
                val launch = runSu(
                    RootCommand.start(Bridge.DEVICE_DIR, instanceId, ctx.applicationInfo.sourceDir), 10,
                )
                check(launch.code == 0) { "launch failed (exit ${launch.code})" }
                val pid = launch.output.lineSequence()
                    .map { it.trim() }
                    .lastOrNull { it.toIntOrNull()?.let { n -> n > 0 } == true }
                    ?.toIntOrNull()
                    ?: throw IllegalStateException("root helper returned no pid")
                ownedPid = pid
                ownedInstanceId = instanceId

                phase = "renderer readiness"
                attempt = RootAttemptDiagnostics(RootStartupPhase.READINESS)
                val deadline = SystemClock.elapsedRealtime() + 10_000
                while (SystemClock.elapsedRealtime() < deadline) {
                    val status = Bridge.readStatus(ctx)
                    if (status.alive && status.owner == "root" && status.uid == 0 &&
                        status.pid == pid && status.provesReleasedRevision(stagedRevision)
                        && status.rendererInstanceId == instanceId
                    ) {
                        ok = true
                        lastError = null
                        attempt = RootAttemptDiagnostics()
                        update(State.RUNNING)
                        return@Thread
                    }
                    Thread.sleep(100)
                }
                throw IllegalStateException("root helper did not become ready")
            } catch (t: Throwable) {
                attempt = attempt.copy(failure = when {
                    t is RootProcess.CommandTimeout -> RootFailureCode.TIMEOUT
                    t is RootStopFailed -> rootStopFailureCode(t.code)
                    attempt.phase == RootStartupPhase.READINESS -> RootFailureCode.READINESS_FAILED
                    attempt.phase in setOf(RootStartupPhase.CLEANUP, RootStartupPhase.LAUNCH) -> RootFailureCode.COMMAND_FAILED
                    else -> RootFailureCode.UNKNOWN
                })
                lastError = "$phase: ${t.message ?: t.javaClass.simpleName}"
                cleanupOwned()
                update(State.ERROR)
            } finally {
                starting = false
                val result = ok || _state.value == State.RUNNING
                main.post { onComplete(result) }
            }
        }, "hilight-root-start").apply { isDaemon = true }.start()
    }

    fun stop() {
        val pid = ownedPid.takeIf { it > 0 } ?: status().pid.takeIf { it > 0 } ?: return
        Thread({
            val instanceId = ownedInstanceId.ifEmpty { status().rendererInstanceId }
            val stopped = runCatching {
                runSu(RootCommand.stop(pid, "root", instanceId), PROCESS_EXIT_TIMEOUT_SECONDS)
            }
                .getOrNull()?.code == 0
            if (stopped) {
                ownedPid = -1
                Bridge.forgetStatusInstance(instanceId)
                ownedInstanceId = ""
                update(State.AVAILABLE)
            } else {
                lastError = "Could not stop the PID-validated root renderer"
                update(State.ERROR)
            }
        }, "hilight-root-stop").apply { isDaemon = true }.start()
    }

    /** Stops a source only after its exact process instance has acknowledged the release revision. */
    fun stopReleasedRenderer(
        source: HelperStatus,
        revision: Long,
        onComplete: (Boolean) -> Unit,
    ) {
        stopExactRenderer(
            source,
            source.provesReleasedRevision(revision, source.rendererInstanceId),
            onComplete,
        )
    }

    /** Fatal close exhaustion cannot acknowledge release; process death is the only safe fence. */
    fun stopFatalRenderer(source: HelperStatus, onComplete: (Boolean) -> Unit) {
        stopExactRenderer(source, source.blackClearUnreleasedFatal, onComplete)
    }

    private fun stopExactRenderer(
        source: HelperStatus,
        allowed: Boolean,
        onComplete: (Boolean) -> Unit,
    ) {
        if (!allowed || source.pid <= 0 ||
            (source.owner != "root" && source.owner != "adb") ||
            !Bridge.BridgeStatusCache.validInstanceId(source.rendererInstanceId)
        ) {
            main.post { onComplete(false) }
            return
        }
        Thread({
            val stopped = runCatching {
                runSu(
                    RootCommand.stop(
                        source.pid,
                        source.owner,
                        source.rendererInstanceId,
                    ),
                    PROCESS_EXIT_TIMEOUT_SECONDS,
                ).code == 0
            }.getOrDefault(false)
            if (stopped) {
                if (source.owner == "root") {
                    ownedPid = -1
                    ownedInstanceId = ""
                }
                Bridge.forgetStatusInstance(source.rendererInstanceId)
                if (source.owner == "root") update(State.AVAILABLE)
            } else {
                lastError = if (source.owner == "root") {
                    "Could not stop the exact PID-validated root renderer"
                } else {
                    "Could not stop the exact PID-validated ADB renderer"
                }
                // Failure to stop an external ADB helper does not invalidate root permission or a
                // separately running root renderer. Store retains the exact ADB fence and retries
                // while this authority remains available. Root-owned uncertainty is still ERROR.
                update(rootStateAfterExactStopFailure(source.owner, _state.value))
            }
            main.post { onComplete(stopped) }
        }, "hilight-root-exit").apply { isDaemon = true }.start()
    }

    private fun cleanupOwned() {
        val pid = ownedPid
        if (pid <= 0) return
        val instanceId = ownedInstanceId
        val stopped = runCatching {
            runSu(RootCommand.stop(pid, "root", instanceId), PROCESS_EXIT_TIMEOUT_SECONDS)
        }
            .getOrNull()?.code == 0
        if (stopped) {
            ownedPid = -1
            Bridge.forgetStatusInstance(instanceId)
            ownedInstanceId = ""
        }
        else lastError = "${lastError.orEmpty()}; could not stop the PID-validated root renderer"
    }

    /**
     * Stops only a bridge renderer that has proved it processed the staged idle document. The TERM
     * signal runs AdbHelper's shutdown hook, which performs one final Engine.stop cleanup. If a known
     * renderer disappears before acknowledging release, root takeover fails closed.
     */
    private fun releaseAndStopBridgeRenderer(stagedRevision: Long) {
        val deadline = SystemClock.elapsedRealtime() + RELEASE_TIMEOUT_MS
        var observedPid = -1
        var observedOwner = ""
        var unresolvedSamples = 0
        while (SystemClock.elapsedRealtime() < deadline) {
            val status = Bridge.readStatus(ctx)
            if (!status.identityResolved && unresolvedSamples < COLD_STATUS_SAMPLES) {
                unresolvedSamples++
                if (unresolvedSamples < COLD_STATUS_SAMPLES) {
                    Thread.sleep(COLD_STATUS_SAMPLE_INTERVAL_MS)
                    continue
                }
                // Three unresolved reads over 300 ms are a bounded absence check only when there is
                // no retained PID. A cached identity below still goes through exact /proc stopping.
            } else if (status.identityResolved) {
                unresolvedSamples = COLD_STATUS_SAMPLES
            }
            if (!status.alive) {
                // A stale heartbeat can belong to a live but hung helper. If it left an identity,
                // resolve that exact PID instead of treating heartbeat expiry as release proof.
                val pid = status.pid.takeIf { it > 0 } ?: observedPid
                val owner = status.owner.takeIf { it == "adb" || it == "root" } ?: observedOwner
                if (status.fromPreviousBoot && observedPid <= 0) {
                    // Issue #19: after every reboot the status file still named the last boot's
                    // renderer, and stopping it through su timed out under KernelSU. Nothing from
                    // a previous boot can be running; a live renderer still holds the singleton
                    // lock, so a wrong guess fails to start rather than running two renderers.
                    Bridge.forgetStatusInstance(status.rendererInstanceId)
                    return
                }
                if (pid > 0 && (owner == "adb" || owner == "root")) {
                    val stopped = runSu(
                        RootCommand.stop(pid, owner, status.rendererInstanceId),
                        PROCESS_EXIT_TIMEOUT_SECONDS,
                    )
                    if (stopped.code != 0) {
                        throw RootStopFailed(stopped.code, "expired renderer")
                    }
                    Bridge.forgetStatusInstance(status.rendererInstanceId)
                    return
                }
                if (observedPid > 0) {
                    throw IllegalStateException("existing renderer disappeared before release proof")
                }
                return
            }
            if (status.owner != "adb" && status.owner != "root") {
                throw IllegalStateException("unexpected bridge renderer owner")
            }
            if (status.pid <= 0) throw IllegalStateException("existing renderer returned no pid")
            observedPid = status.pid
            observedOwner = status.owner
            // Old helpers cannot produce the new release fence. Exact PID/owner validation followed
            // by TERM is the upgrade path; the current helper's synchronous startup cleanup handles
            // any panel state that survived the stale process.
            if (status.rendererStale) {
                val stopped = runSu(
                    RootCommand.stop(status.pid, status.owner, status.rendererInstanceId),
                    PROCESS_EXIT_TIMEOUT_SECONDS,
                )
                if (stopped.code != 0) {
                    throw RootStopFailed(stopped.code, "stale ${status.owner} renderer")
                }
                Bridge.forgetStatusInstance(status.rendererInstanceId)
                return
            }
            if (status.blackClearUnreleasedFatal) {
                val stopped = runSu(
                    RootCommand.stop(status.pid, status.owner, status.rendererInstanceId),
                    PROCESS_EXIT_TIMEOUT_SECONDS,
                )
                if (stopped.code != 0) {
                    throw RootStopFailed(stopped.code, "fatal ${status.owner} renderer")
                }
                Bridge.forgetStatusInstance(status.rendererInstanceId)
                return
            }
            if (status.provesReleasedRevision(stagedRevision)) {
                val stopped = runSu(
                    RootCommand.stop(status.pid, status.owner, status.rendererInstanceId),
                    PROCESS_EXIT_TIMEOUT_SECONDS,
                )
                if (stopped.code != 0) {
                    throw RootStopFailed(stopped.code, "released ${status.owner} renderer")
                }
                Bridge.forgetStatusInstance(status.rendererInstanceId)
                return
            }
            Thread.sleep(100)
        }
        throw IllegalStateException("existing renderer did not release before root takeover")
    }

    private fun update(state: State, fromPresenceCheck: Boolean = false) {
        presenceErrored = fromPresenceCheck && state == State.ERROR
        _state.value = state
        main.post { onStateChanged?.invoke() }
    }

    private fun runPlain(command: String, timeoutSeconds: Long = 3): RootProcess.Result =
        RootProcess.run(listOf("sh", "-c", command), timeoutSeconds)

    private fun runSu(command: String, timeoutSeconds: Long): RootProcess.Result =
        RootProcess.run(listOf("su", "-c", command), timeoutSeconds)

    companion object {
        private const val RELEASE_TIMEOUT_MS = 12_000L
        // RootCommand.stop bounds its own wait (TERM, then KILL) to about eight seconds before
        // the read-only scan. This outer limit only catches a stuck su or shell.
        private const val PROCESS_EXIT_TIMEOUT_SECONDS = 15L
        private const val COLD_STATUS_SAMPLES = 3
        private const val COLD_STATUS_SAMPLE_INTERVAL_MS = 150L
    }
}
