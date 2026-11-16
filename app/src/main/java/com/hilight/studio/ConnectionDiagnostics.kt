package com.hilight.studio

/** Fixed vocabulary only: never include shell output, file paths or notification data. */
enum class RootStartupPhase { IDLE, PERMISSION, CLEANUP, LAUNCH, READINESS }
enum class RootFailureCode {
    NONE, PERMISSION_DENIED, TIMEOUT, COMMAND_FAILED, READINESS_FAILED, UNKNOWN,
    RENDERER_STUCK, OTHER_RENDERER_RUNNING,
}

data class RootAttemptDiagnostics(
    val phase: RootStartupPhase = RootStartupPhase.IDLE,
    val failure: RootFailureCode = RootFailureCode.NONE,
)

data class RootConnectionDiagnostics(
    val state: RootBackend.State,
    val starting: Boolean,
    val attempt: RootAttemptDiagnostics,
)

/** Shizuku transport snapshot; fixed vocabulary only, never exception text. */
data class ShizukuConnectionDiagnostics(
    val state: ShizukuBackend.State,
    val installed: Boolean,
    val managerAlive: Boolean,
    val serverVersion: Int,
    /** granted, denied, unavailable (manager refused this app) or unknown (not asked). */
    val permission: String,
    val fenced: Boolean,
    /** none, too_old, dead_binder, renderer_incompatible, not_attached or other. */
    val error: String,
)

/** What may be holding the LEDs dark right now, from the app's side. */
data class GateDiagnostics(
    val notificationAccess: Boolean,
    /** Guard keeping the always-on look dark, or "none". */
    val ambientSuppression: String,
    /** Guard keeping notification flashes dark, or "none". */
    val alertSuppression: String,
    val quietHoursEnabled: Boolean,
    val inQuietWindow: Boolean,
    val dndSuppressesSignals: Boolean,
    val faceDownOnly: Boolean,
    val faceDown: Boolean,
    val screenOffOnly: Boolean,
    val screenOn: Boolean,
)

/** Capture on Store's main thread, before reading renderer files on the IO dispatcher. */
data class LifecycleDiagnostics(
    val enabled: Boolean,
    val rootTransition: Boolean,
    val coldDiscoveryPending: Boolean,
    val handoffAwaitingRetry: Boolean,
    val sourceExitConfirmed: Boolean,
    val handoffSource: Transport?,
    val handoffTarget: Transport?,
    val fenced: Boolean,
)

internal fun rootRetryVisible(state: RootBackend.State, connected: Boolean): Boolean =
    !connected && state in setOf(RootBackend.State.AVAILABLE, RootBackend.State.RUNNING)
