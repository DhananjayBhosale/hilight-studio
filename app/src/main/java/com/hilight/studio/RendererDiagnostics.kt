package com.hilight.studio

import android.os.SystemClock
import org.json.JSONObject
import java.util.Locale

/**
 * Builds the small, privacy-safe renderer snapshot attached to stuck-LED reports.
 *
 * This is deliberately an allowlist over [HelperStatus]. Adding data to renderer status does not
 * add it here automatically. In particular, notification details, package names, remembered chat
 * names, accounts, stable device identifiers, and logs are never inputs to this formatter.
 */
object RendererDiagnostics {
    const val SCHEMA_VERSION = 2

    fun format(
        status: HelperStatus,
        selectedTransport: Transport,
        activeTransport: Transport,
        appVersionName: String,
        appVersionCode: Long,
        deviceModel: String,
        buildId: String,
        sdkInt: Int,
        capturedAtEpochMs: Long = System.currentTimeMillis(),
        capturedAtElapsedRealtimeMs: Long = SystemClock.elapsedRealtime(),
    ): String = JSONObject().apply {
        put("schemaVersion", SCHEMA_VERSION)
        put("capturedAtEpochMs", capturedAtEpochMs)
        put("app", JSONObject().apply {
            put("versionName", safeVersion(appVersionName))
            put("versionCode", appVersionCode)
        })
        put("device", JSONObject().apply {
            put("model", safeBuildLabel(deviceModel))
            put("buildId", safeBuildLabel(buildId))
            put("sdkInt", sdkInt)
        })
        put("transport", JSONObject().apply {
            put("selected", selectedTransport.token())
            put("active", activeTransport.token())
        })
        put("renderer", JSONObject().apply {
            put("alive", status.alive)
            put("statusAgeMs", status.ageMs)
            put("pid", status.pid)
            put("uid", status.uid)
            put("instanceId", safeInstanceId(status.rendererInstanceId))
            put("ready", status.rendererReady)
            put("compatibility", status.rendererCompatibility.name.lowercase(Locale.ROOT))
            put("stale", status.rendererStale)
            put("versionName", safeVersion(status.rendererVersionName))
            put("versionCode", status.rendererVersionCode)
            put("contractVersion", status.rendererContractVersion)
            put("implementationRevision", status.rendererImplementationRevision)
            put("statusSchemaVersion", status.rendererStatusSchemaVersion)
            put("clearAlgorithmVersion", status.rendererClearAlgorithmVersion)
            put("serviceVersion", status.rendererServiceVersion)
        })
        put("session", JSONObject().apply {
            put("open", status.sessionOpen)
            put("ledCount", status.ledCount)
            put("minUpdatePeriodMs", status.lightMinUpdatePeriodMs)
        })
        put("cleanup", JSONObject().apply {
            put("pending", status.blackClearPending)
            put("terminal", status.blackClearTerminal)
            put("result", safeEnum(status.blackClearResult, CLEAR_RESULTS))
            put("attemptResult", safeEnum(status.blackClearAttemptResult, CLEAR_RESULTS))
            put("stage", safeEnum(status.blackClearStage, CLEAR_STAGES))
            put("strategy", safeEnum(status.blackClearStrategy, CLEAR_STRATEGIES))
            put("strategyVersion", status.blackClearStrategyVersion)
            put("cycleId", status.blackClearCycleId)
            put("cycleSource", safeEnum(status.blackClearCycleSource, CLEAR_CYCLE_SOURCES))
            put("attemptsUsed", status.blackClearAttemptsUsed)
            put("attemptsRemaining", status.blackClearAttemptsRemaining)
            put("stopAttemptAvailable", status.blackClearStopAttemptAvailable)
            put("closeFailures", status.blackClearCloseFailures)
            put("unreleasedFatal", status.blackClearUnreleasedFatal)
            put(
                "lastEventAgeMs",
                if (status.blackClearTimestampElapsedMs > 0) {
                    (capturedAtElapsedRealtimeMs - status.blackClearTimestampElapsedMs)
                        .coerceAtLeast(0)
                } else {
                    -1
                },
            )
            put("lastSeenManualRequestId", status.lastSeenManualBlackClearRequestId)
            put("lastAcceptedManualRequestId", status.lastAcceptedManualBlackClearRequestId)
        })
        put("state", JSONObject().apply {
            put("appliedRevision", status.appliedStateRevision)
            put("receivedRevision", status.receivedStateRevision)
            put("settledRevision", status.settledStateRevision)
            put("releasedRevision", status.releasedStateRevision)
            put("privacyObserverEnabled", status.privacyObserverEnabled)
        })
    }.toString(2)

    private fun Transport.token(): String = name.lowercase(Locale.ROOT)

    private fun safeVersion(value: String): String =
        value.takeIf { it.length in 1..40 && VERSION.matches(it) } ?: "unknown"

    private fun safeEnum(value: String, allowed: Set<String>): String =
        value.lowercase(Locale.ROOT).takeIf(allowed::contains) ?: "unknown"

    private fun safeBuildLabel(value: String): String = value
        .asSequence()
        .filter { it.code in 0x20..0x7e }
        .take(80)
        .joinToString("")
        .ifBlank { "unknown" }

    private fun safeInstanceId(value: String): String =
        value.takeIf { it.length in 1..96 && INSTANCE_ID.matches(it) } ?: "unknown"

    private val VERSION = Regex("[A-Za-z0-9._+\\-]+")
    private val INSTANCE_ID = Regex("[A-Za-z0-9._:\\-]+")

    private val CLEAR_RESULTS = setOf(
        "not_requested",
        "deferred",
        "binder_accepted_unverified",
        "framework_effective_unverified",
        "shadowed",
        "io_failed",
        "completed_unverified",
        "exhausted",
    )

    private val CLEAR_STAGES = setOf(
        "none",
        "idle",
        "requested",
        "waiting_retry",
        "open_session",
        "alpha_black_write",
        "alpha_black_readback",
        "wait_after_alpha_black",
        "canonical_black_write",
        "canonical_black_readback",
        "wait_after_canonical_black",
        "close_session",
        "closed",
        "no_lights",
        "close_exhausted",
    )

    private val CLEAR_STRATEGIES = setOf(
        "alpha_black_then_zero",
        "alpha_black_then_close",
        "unknown",
    )

    private val CLEAR_CYCLE_SOURCES = setOf("automatic", "manual")
}
