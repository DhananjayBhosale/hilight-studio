package com.hilight.studio

/** Fixed vocabulary only: never include shell output, file paths or notification data. */
enum class RootStartupPhase { IDLE, PERMISSION, CLEANUP, LAUNCH, READINESS }
enum class RootFailureCode { NONE, PERMISSION_DENIED, TIMEOUT, COMMAND_FAILED, READINESS_FAILED, UNKNOWN }

data class RootAttemptDiagnostics(
    val phase: RootStartupPhase = RootStartupPhase.IDLE,
    val failure: RootFailureCode = RootFailureCode.NONE,
)

data class RootConnectionDiagnostics(
    val state: RootBackend.State,
    val starting: Boolean,
    val attempt: RootAttemptDiagnostics,
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
