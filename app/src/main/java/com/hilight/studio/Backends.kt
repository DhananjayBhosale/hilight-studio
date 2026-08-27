package com.hilight.studio

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.RemoteException
import android.util.Log
import androidx.annotation.StringRes
import com.hilight.core.IHiLightService
import com.hilight.core.RendererContract
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import rikka.shizuku.Shizuku

private val SHIZUKU_COMPLETE_STATUS_FIELDS = arrayOf(
    "pid",
    "uid",
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
    "rendererVersionCode",
    "rendererVersionName",
    "rendererContractVersion",
    "rendererImplementationRevision",
    "rendererStatusSchemaVersion",
    "rendererClearAlgorithmVersion",
    "rendererServiceVersion",
    "rendererReady",
)

/** A partial status must not replace the last internally consistent sample for this binder. */
internal fun isCompleteShizukuRendererStatus(status: JSONObject): Boolean =
    status.optInt("pid", -1) > 0 &&
        SHIZUKU_COMPLETE_STATUS_FIELDS.none { !status.has(it) || status.isNull(it) }

/** Partial/invalid live reads retain the last complete sample from the same binder. */
internal fun retainedShizukuStatus(
    live: JSONObject?,
    lastCompleteForBinder: JSONObject?,
): JSONObject? = if (live != null && isCompleteShizukuRendererStatus(live)) {
    live
} else {
    lastCompleteForBinder
}

internal enum class ShizukuPeekAction { WAIT_FOR_CALLBACK, CREATE_ONCE, FENCE }

/** A create is authorized only by the first no-create peek proving that no service exists. */
internal fun shizukuPeekAction(
    result: Int,
    priorPeekReportedExisting: Boolean,
    retry: Boolean,
): ShizukuPeekAction = when {
    result >= 0 -> ShizukuPeekAction.WAIT_FOR_CALLBACK
    retry || priorPeekReportedExisting -> ShizukuPeekAction.FENCE
    else -> ShizukuPeekAction.CREATE_ONCE
}

/** A late callback may validate only when it still belongs to an intended peek/create attempt. */
internal fun shouldRemoveLateShizukuCandidate(
    disconnectRequested: Boolean,
    ownershipFenced: Boolean,
    expectedPeekOrCreateCallback: Boolean,
): Boolean = disconnectRequested || (ownershipFenced && !expectedPeekOrCreateCallback)

/** Renderer A can release ownership only after A and every quarantined renderer are exactly dead. */
internal fun <T> canCompleteTrackedShizukuExit(
    primary: T?,
    confirmedDead: Set<T>,
    quarantined: Set<T>,
): Boolean = primary != null && primary in confirmedDead && quarantined.isEmpty()

/** A newer handoff may share the exact-death result only for the same proven Shizuku process. */
internal fun canJoinShizukuTermination(
    callbackStillPending: Boolean,
    source: HelperStatus,
    current: HelperStatus,
): Boolean = callbackStillPending && source.owner == "shizuku" && current.owner == "shizuku" &&
    source.identityResolved && current.identityResolved && source.pid > 0 && current.pid == source.pid

/** Null means the daemon is safe to use; any text is a diagnostic-only rejection reason. */
internal fun shizukuRendererCompatibilityFailure(
    status: JSONObject,
    expectedServiceVersion: Int,
    expectedAppVersion: Int,
): String? {
    if (!isCompleteShizukuRendererStatus(status)) {
        val missing = SHIZUKU_COMPLETE_STATUS_FIELDS.filter {
            !status.has(it) || status.isNull(it)
        }
        return "renderer status incomplete (${missing.joinToString()})"
    }
    val contract = status.optInt("rendererContractVersion", -1)
    val implementation = status.optInt("rendererImplementationRevision", -1)
    val schema = status.optInt(
        "rendererStatusSchemaVersion",
        status.optInt("version", -1),
    )
    val clearAlgorithm = status.optInt("rendererClearAlgorithmVersion", -1)
    val actualServiceVersion = status.optInt("rendererServiceVersion", -1)
    val actualAppVersion = status.optInt("rendererVersionCode", -1)

    if (!RendererContract.isCompatible(contract, implementation, schema, clearAlgorithm)) {
        return "renderer contract mismatch " +
            "(contract=$contract, implementation=$implementation, schema=$schema, " +
            "clear=$clearAlgorithm)"
    }
    if (actualServiceVersion != expectedServiceVersion ||
        actualAppVersion != expectedAppVersion
    ) {
        return "renderer build mismatch (service=$actualServiceVersion, app=$actualAppVersion)"
    }
    if (!status.optBoolean("rendererReady", false) || status.optInt("ledCount", 0) <= 0) {
        return "renderer engine is not ready"
    }
    return null
}

internal enum class AdbPushSafety {
    TARGETED_CURRENT,
    BROADCAST_DISABLED,
    SANITIZED_DISABLED,
}

internal data class AdbPushDecision(val json: String, val safety: AdbPushSafety)

/** Exact binder-generation identity delivered only after that user-service process has died. */
internal data class ShizukuServiceExit(
    val connectionGeneration: Long,
    val status: HelperStatus,
)

/**
 * A visible file-bridge document is safe only when addressed to one live, current helper instance.
 * Unknown, malformed and heartbeat-expired identities receive a minimal unscoped idle instead.
 */
internal fun safeAdbPush(json: String, status: HelperStatus): AdbPushDecision {
    val currentInstance = status.owner == "adb" && status.uid == 2_000 && status.alive &&
        status.identityResolved && status.rendererReady &&
        Bridge.BridgeStatusCache.validInstanceId(status.rendererInstanceId) &&
        !status.rendererStale && !status.blackClearUnreleasedFatal
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
        // Deliberately unscoped: every legacy/duplicate bridge renderer must stand down.
        parsed.remove("bridgeRendererInstanceId")
        return AdbPushDecision(parsed.toString(), AdbPushSafety.BROADCAST_DISABLED)
    }

    val revision = parsed?.optLong("stateRevision", 0L)?.coerceAtLeast(0L) ?: 0L
    return AdbPushDecision(
        Bridge.incompatibleRendererSafeIdleJson(revision),
        AdbPushSafety.SANITIZED_DISABLED,
    )
}

/** How the privileged renderer is reached. */
enum class Transport(@StringRes val labelRes: Int) {
    /** Root is automatic; otherwise prefer Shizuku and fall back to an adb-started helper. */
    AUTO(R.string.transport_auto),
    SHIZUKU(R.string.transport_shizuku),
    ADB(R.string.transport_adb),
    ROOT(R.string.transport_root),
}

/** A privileged renderer the app can push state to. */
interface Backend {
    val transport: Transport
    fun push(json: String)
    fun status(): HelperStatus
}

/**
 * Talks to the adb-started [com.hilight.core.AdbHelper] through the two JSON files.
 *
 * The app must own the directory and both files: on external storage a file keeps its creator's UID,
 * and a file the shell created is unreadable here.
 */
class AdbBackend(private val ctx: Context) : Backend {

    override val transport = Transport.ADB

    override fun push(json: String) {
        Bridge.writeState(ctx, safeAdbPush(json, status()).json)
    }

    override fun status(): HelperStatus {
        val status = Bridge.readStatus(ctx)
        return if (!status.alive || status.owner == "adb") status
        else HelperStatus(alive = false, owner = "adb")
    }
}

/**
 * Talks to [HiLightUserService], which Shizuku launches into a shell-UID process.
 *
 * Shizuku itself is started once per boot by the user, either from on-device wireless debugging (no
 * computer needed) or from adb.
 */
class ShizukuBackend(private val ctx: Context) : Backend {

    enum class State { NOT_INSTALLED, NOT_RUNNING, NEEDS_PERMISSION, CONNECTING, CONNECTED, FAILED }

    override val transport = Transport.SHIZUKU

    private val _state = MutableStateFlow(State.NOT_RUNNING)
    val state: StateFlow<State> = _state.asStateFlow()
    private val main = Handler(Looper.getMainLooper())

    private var service: IHiLightService? = null
    /** Raw text from a failure. Comes from the framework, so it is not translated. */
    private var lastError: String? = null

    /** Our own explanation of a failure, when we have one to give. */
    @StringRes
    private var lastErrorRes: Int? = null

    /** Latest staged document; Store explicitly decides when ownership makes replay safe. */
    private var pending: String? = null

    /** One automatic remove/rebind is allowed before an incompatible renderer fails closed. */
    private var compatibilityRebindAttempted = false
    private var awaitingCompatibilityDisconnect = false
    private var rebindAfterCompatibilityDisconnect = false

    /** A create-capable bind is issued at most once, and only after a no-create peek proved absent. */
    private var connectionAttemptActive = false
    private var peekAwaitingCallback = false
    private var peekRetryAttempted = false
    private var peekReportedExisting = false
    private var createBindIssued = false

    /** An explicit disconnect request wins over every late peek/create callback. */
    private var startupRemovalPending = false

    /** Reject callbacks from a removed connection epoch until a new bind intent starts a peek. */
    private var connectionRemovalTombstone = false

    /** Normal unbind/manager-death cleanup also waits for exact service-binder death. */
    private var normalExitPending = false
    private var managerDeathExitPending = false

    /**
     * ServiceConnection disconnect callbacks do not identify which binder disconnected. Track each
     * process directly so a late callback from renderer A can never be mistaken for renderer B.
     */
    private var nextConnectionGeneration = 0L
    private val binderGenerations = LinkedHashMap<IBinder, Long>()
    private val binderDeathRecipients = LinkedHashMap<IBinder, IBinder.DeathRecipient>()
    private val confirmedDeadBinders = HashSet<IBinder>()
    private val directDestroyBinders = HashSet<IBinder>()
    private val directDestroyAttempts = HashMap<IBinder, Int>()
    private val quarantinedServices = LinkedHashMap<IBinder, IHiLightService>()
    private var untrackableCandidatePresent = false

    /** A partial status cannot erase the last complete sample for the same exact binder. */
    private var lastCompleteStatusBinder: IBinder? = null
    private var lastCompleteStatus: JSONObject? = null

    /**
     * A rejected daemon may still own a visible framework session until its binder is confirmed
     * disconnected. Keep both the binder and its last status so FAILED cannot be mistaken for safe
     * fallback availability.
     */
    private var incompatibleService: IHiLightService? = null
    private var incompatibleStatus: JSONObject? = null
    private val _unresolvedIncompatibleRenderer = MutableStateFlow(false)
    val unresolvedIncompatibleRenderer: StateFlow<Boolean> =
        _unresolvedIncompatibleRenderer.asStateFlow()

    private var releaseTerminationBinder: IBinder? = null
    private var releaseTerminationCallback: ((Boolean) -> Unit)? = null
    private val releaseTerminationWaiters = ArrayList<(Boolean) -> Unit>()
    private val releaseTerminationTimeout = Runnable {
        if (releaseTerminationBinder == null) return@Runnable
        failReleasedServiceTermination("user-service exit was not confirmed before timeout")
    }

    private val compatibilityRemovalTimeout = Runnable {
        if (!awaitingCompatibilityDisconnect) return@Runnable
        awaitingCompatibilityDisconnect = false
        _state.value = State.FAILED
        (service ?: incompatibleService)?.let { requestDirectDestroy(it) }
        Log.w(
            TAG,
            "user service did not disconnect before timeout; direct destroy requested and " +
                "fallback remains blocked",
        )
        onAvailabilityChanged?.invoke()
    }

    private val peekCallbackTimeout = Runnable {
        if (!connectionAttemptActive || !peekAwaitingCallback) return@Runnable
        if (!peekRetryAttempted) {
            // Shizuku stores ServiceConnection callbacks in a HashSet. A second peek with this same
            // object requests the callback again without registering it twice or creating service.
            peekRetryAttempted = true
            performPeek(retry = true)
        } else {
            fenceUnknownConnection("existing user service did not deliver its binder after two peeks")
        }
    }

    private val startupCallbackTimeout = Runnable {
        if (!connectionAttemptActive || !createBindIssued) return@Runnable
        fenceUnknownConnection("created user service did not deliver its binder")
    }

    /**
     * Called whenever availability changes in either direction.
     *
     * On connect: a fresh user service holds no state, so the current look has to be pushed at once
     * or the LEDs stay dark until the user touches a control. On loss: the store needs to re-push
     * through whatever transport is left, for the same reason.
     */
    var onAvailabilityChanged: (() -> Unit)? = null

    /**
     * Invoked once after the exact tracked service binder dies for normal unbind/removal or a
     * timed-out handoff. The generation and retained status let Store reject a delayed exit from an
     * older renderer. Successful release/fatal handoffs use only their existing per-call callback.
     */
    internal var onConfirmedServiceExit: ((ShizukuServiceExit) -> Unit)? = null

    private val expectedServiceVersion =
        RendererContract.shizukuServiceVersion(BuildConfig.VERSION_CODE)

    private val args = Shizuku.UserServiceArgs(
        ComponentName(BuildConfig.APPLICATION_ID, HiLightUserService::class.java.name)
    )
        .daemon(true)                       // survive the app process going away
        .processNameSuffix("hilight")
        .debuggable(BuildConfig.DEBUG)
        .version(expectedServiceVersion)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (binder == null) {
                lastErrorRes = R.string.shizuku_error_dead_binder
                fenceUnknownConnection("Shizuku delivered a null user-service binder")
                return
            }
            val callbackBelongedToAttempt = connectionAttemptActive || peekAwaitingCallback ||
                createBindIssued || peekReportedExisting
            cancelConnectionTimeouts()
            connectionAttemptActive = false
            peekAwaitingCallback = false

            val candidate = IHiLightService.Stub.asInterface(binder)
            if (trackServiceBinder(candidate) == null) {
                fenceUntrackableCandidate(candidate)
                return
            }
            if (releaseTerminationBinder != null) {
                if (releaseTerminationBinder == binder) return
                _state.value = State.FAILED
                Log.w(TAG, "renderer connected while prior service exit was unresolved")
                quarantineUnexpectedCandidate(candidate)
                return
            }
            service?.let { current ->
                if (current.asBinder() == binder) return
                _state.value = State.FAILED
                Log.w(TAG, "second renderer connected while current renderer still owns output")
                quarantineUnexpectedCandidate(candidate)
                return
            }
            incompatibleService?.let { fenced ->
                if (fenced.asBinder() == binder) return
                _state.value = State.FAILED
                Log.w(TAG, "new user service arrived before fenced binder died")
                quarantineUnexpectedCandidate(candidate)
                return
            }
            if (shouldRemoveLateShizukuCandidate(
                    disconnectRequested = startupRemovalPending || connectionRemovalTombstone,
                    ownershipFenced = _unresolvedIncompatibleRenderer.value,
                    expectedPeekOrCreateCallback = callbackBelongedToAttempt,
                )
            ) {
                // A queued callback cannot undo Disconnect or resolve an unrelated binder-less fence.
                retainCandidateForRemoval(
                    candidate,
                    rebindAfterExit = false,
                    normalExit = startupRemovalPending,
                )
                return
            }
            if (!binder.pingBinder()) {
                // pingBinder=false is not exit proof. Keep this identity fenced until its own
                // DeathRecipient fires; a generic ServiceConnection callback is binder-ambiguous.
                lastErrorRes = R.string.shizuku_error_dead_binder
                retainCandidateForRemoval(candidate, rebindAfterExit = true)
                return
            }
            val check = checkRenderer(candidate)
            if (check.failure != null || check.status == null) {
                rejectIncompatibleRenderer(
                    candidate,
                    check.status,
                    check.failure ?: "renderer identity unavailable",
                )
                return
            }

            service = candidate
            incompatibleService = null
            incompatibleStatus = null
            _unresolvedIncompatibleRenderer.value = false
            rebindAfterCompatibilityDisconnect = false
            awaitingCompatibilityDisconnect = false
            main.removeCallbacks(compatibilityRemovalTimeout)
            normalExitPending = false
            managerDeathExitPending = false
            startupRemovalPending = false
            compatibilityRebindAttempted = false
            clearConnectionAttemptState()
            lastCompleteStatusBinder = binder
            lastCompleteStatus = JSONObject(check.status.toString())
            lastError = null
            lastErrorRes = null
            _state.value = State.CONNECTED
            val rendererStatus = check.status
            val rendererBuild = rendererStatus.optInt("rendererVersionCode", -1)
            val rendererName = rendererStatus.optString("rendererVersionName", "")
            val buildLabel = if (rendererBuild >= 0) "$rendererName ($rendererBuild)" else "unknown"
            Log.i(
                TAG,
                "user service connected, ${runCatching { service?.ledCount() }.getOrNull()} LEDs, " +
                    "renderer build=$buildLabel, app build=${BuildConfig.VERSION_NAME} " +
                    "(${BuildConfig.VERSION_CODE}), renderer service=$expectedServiceVersion",
            )
            // Store replays only after fencing and terminating any previous renderer. Replaying the
            // cached visible document here would race that ownership handoff.
            onAvailabilityChanged?.invoke()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            // Shizuku does not include the disconnected binder in this callback. It is useful only
            // as an advisory signal; exact process-death authority comes from each binder's own
            // DeathRecipient, otherwise renderer B could accidentally release renderer A's fence.
            Log.d(TAG, "user-service connection reported disconnect; awaiting exact binder death")
        }
    }

    private val permissionListener =
        Shizuku.OnRequestPermissionResultListener { _, grantResult ->
            if (grantResult == PackageManager.PERMISSION_GRANTED) bind() else {
                _state.value = State.NEEDS_PERMISSION
            }
        }

    init {
        Shizuku.addRequestPermissionResultListener(permissionListener)
        Shizuku.addBinderReceivedListenerSticky { refresh() }
        Shizuku.addBinderDeadListener {
            handleManagerBinderDeath()
        }
        refresh()
    }

    fun isInstalled(): Boolean = runCatching {
        ctx.packageManager.getPackageInfo(SHIZUKU_PKG, 0)
        true
    }.getOrDefault(false)

    /** Re-evaluates availability, and connects when it can be done without user interaction. */
    fun refresh() {
        if (_unresolvedIncompatibleRenderer.value || releaseTerminationBinder != null) return
        service?.let { current ->
            if (current.asBinder().pingBinder()) return
            // A failed ping is not a confirmed process exit. Preserve the binder as an ownership
            // fence and remove it before attempting any replacement.
            service = null
            incompatibleService = current
            incompatibleStatus = lastCompleteStatus.takeIf {
                lastCompleteStatusBinder == current.asBinder()
            }
            awaitingCompatibilityDisconnect = true
            rebindAfterCompatibilityDisconnect = true
            _unresolvedIncompatibleRenderer.value = true
            _state.value = State.CONNECTING
            onAvailabilityChanged?.invoke()
            requestTrackedRemoval(current)
            return
        }
        if (connectionAttemptActive || _state.value == State.CONNECTING) return
        if (!Shizuku.pingBinder()) {
            if (!isInstalled()) {
                _state.value = State.NOT_INSTALLED
                return
            }
            // Shizuku hands its binder to an app when that app's process starts. A Shizuku started
            // *after* this process therefore stays invisible until the app is reopened — verified on
            // device, and the reason the Setup card says so. There is no app-side pull for this:
            // ShizukuProvider.requestBinderForNonProviderProcess() only talks to the app's own
            // provider, not to the manager.
            _state.value = State.NOT_RUNNING
            return
        }
        val serverVersion = runCatching { Shizuku.getVersion() }.getOrElse {
            _state.value = State.FAILED
            lastError = it.message
            lastErrorRes = null
            return
        }
        if (serverVersion < MIN_SHIZUKU_SERVER_VERSION) {
            _state.value = State.FAILED
            lastErrorRes = R.string.shizuku_error_too_old
            return
        }
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            _state.value = State.NEEDS_PERMISSION
            return
        }
        bind()
    }

    /** Asks Shizuku for access; the user confirms in Shizuku's own dialog. */
    fun requestPermission() {
        if (_unresolvedIncompatibleRenderer.value || releaseTerminationBinder != null) return
        if (!Shizuku.pingBinder()) {
            refresh()
            return
        }
        if (runCatching { Shizuku.getVersion() }.getOrDefault(-1) <
            MIN_SHIZUKU_SERVER_VERSION
        ) {
            _state.value = State.FAILED
            lastErrorRes = R.string.shizuku_error_too_old
            return
        }
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) bind()
        else Shizuku.requestPermission(PERMISSION_REQUEST)
    }

    private fun bind() {
        if (_unresolvedIncompatibleRenderer.value || releaseTerminationBinder != null) return
        if (connectionAttemptActive || _state.value == State.CONNECTED) return
        beginConnectionAttempt(keepOwnershipFence = false)
    }

    /** Every create-capable bind is preceded by a no-create peek using this same connection. */
    private fun beginConnectionAttempt(keepOwnershipFence: Boolean) {
        if (!runCatching { Shizuku.pingBinder() }.getOrDefault(false)) {
            if (keepOwnershipFence) {
                fenceUnknownConnection("Shizuku manager unavailable before replacement peek")
            } else {
                _state.value = State.NOT_RUNNING
            }
            return
        }
        if (runCatching { Shizuku.getVersion() }.getOrDefault(-1) <
            MIN_SHIZUKU_SERVER_VERSION
        ) {
            if (keepOwnershipFence) _unresolvedIncompatibleRenderer.value = true
            _state.value = State.FAILED
            lastErrorRes = R.string.shizuku_error_too_old
            return
        }
        cancelConnectionTimeouts()
        connectionAttemptActive = true
        peekAwaitingCallback = false
        peekRetryAttempted = false
        peekReportedExisting = false
        createBindIssued = false
        startupRemovalPending = false
        connectionRemovalTombstone = false
        if (!keepOwnershipFence) _unresolvedIncompatibleRenderer.value = false
        _state.value = State.CONNECTING
        performPeek(retry = false)
    }

    private fun performPeek(retry: Boolean) {
        if (!connectionAttemptActive) return
        peekAwaitingCallback = true
        main.removeCallbacks(peekCallbackTimeout)
        val result = runCatching { Shizuku.peekUserService(args, connection) }.getOrElse {
            fenceUnknownConnection("peekUserService failed: ${it.javaClass.simpleName}")
            return
        }
        // The framework may deliver an existing binder inline before peekUserService returns.
        if (!connectionAttemptActive) return
        when (shizukuPeekAction(result, peekReportedExisting, retry)) {
            ShizukuPeekAction.CREATE_ONCE -> {
                peekAwaitingCallback = false
                issueSingleCreateBind()
            }
            ShizukuPeekAction.FENCE -> {
                peekAwaitingCallback = false
                // If a prior peek saw a process, later absence without its actual disconnect
                // callback is not authority to create a replacement.
                fenceUnknownConnection("peek result changed before disconnect was confirmed")
            }
            ShizukuPeekAction.WAIT_FOR_CALLBACK -> {
                peekReportedExisting = true
                main.postDelayed(peekCallbackTimeout, PEEK_CALLBACK_TIMEOUT_MS)
            }
        }
    }

    private fun issueSingleCreateBind() {
        if (!connectionAttemptActive || createBindIssued) return
        createBindIssued = true
        main.removeCallbacks(peekCallbackTimeout)
        val result = runCatching { Shizuku.bindUserService(args, connection) }
        if (result.isFailure) {
            // A RemoteException can happen after the manager accepted the create request. Keep the
            // route fenced instead of assuming there is no service process.
            fenceUnknownConnection(
                "bindUserService failed: ${result.exceptionOrNull()?.javaClass?.simpleName}",
            )
            return
        }
        // A newly created service may likewise connect before bindUserService returns.
        if (!connectionAttemptActive) return
        main.postDelayed(startupCallbackTimeout, STARTUP_CALLBACK_TIMEOUT_MS)
    }

    private fun fenceUnknownConnection(reason: String) {
        cancelConnectionTimeouts()
        connectionAttemptActive = false
        peekAwaitingCallback = false
        _unresolvedIncompatibleRenderer.value = true
        _state.value = State.FAILED
        lastError = reason
        lastErrorRes = null
        Log.w(TAG, "$reason; fallback remains blocked")
        onAvailabilityChanged?.invoke()
    }

    private fun cancelConnectionTimeouts() {
        main.removeCallbacks(peekCallbackTimeout)
        main.removeCallbacks(startupCallbackTimeout)
    }

    private fun clearConnectionAttemptState() {
        connectionAttemptActive = false
        peekAwaitingCallback = false
        peekRetryAttempted = false
        peekReportedExisting = false
        createBindIssued = false
    }

    /** Stable identity Store records alongside a Shizuku source before beginning a handoff. */
    fun currentConnectionGeneration(): Long {
        val binder = service?.asBinder() ?: incompatibleService?.asBinder()
            ?: releaseTerminationBinder ?: return 0L
        return binderGenerations[binder] ?: 0L
    }

    private fun trackServiceBinder(candidate: IHiLightService): Long? {
        val binder = candidate.asBinder()
        binderGenerations[binder]?.let { return it }

        val generation = ++nextConnectionGeneration
        val recipient = IBinder.DeathRecipient {
            main.post { handleExactServiceBinderDeath(binder) }
        }
        binderGenerations[binder] = generation
        binderDeathRecipients[binder] = recipient
        confirmedDeadBinders.remove(binder)
        try {
            binder.linkToDeath(recipient, 0)
        } catch (_: RemoteException) {
            // linkToDeath reports RemoteException only when the exact binder is already dead.
            main.post { handleExactServiceBinderDeath(binder) }
        } catch (t: Throwable) {
            // Without a binder-specific death signal the route must remain fenced; a generic
            // ServiceConnection disconnect cannot identify this process.
            Log.e(TAG, "could not track exact user-service binder death", t)
            binderDeathRecipients.remove(binder)
            binderGenerations.remove(binder)
            confirmedDeadBinders.remove(binder)
            directDestroyBinders.remove(binder)
            directDestroyAttempts.remove(binder)
            return null
        }
        return generation
    }

    /** A non-dead binder that cannot install DeathRecipient can never authorize fallback. */
    private fun fenceUntrackableCandidate(candidate: IHiLightService) {
        val rawStatus = runCatching { JSONObject(candidate.status()) }.getOrNull()
        untrackableCandidatePresent = true
        connectionRemovalTombstone = true
        if (service == null && incompatibleService == null && releaseTerminationBinder == null) {
            incompatibleService = candidate
            incompatibleStatus = rawStatus?.let { JSONObject(it.toString()) }
        }
        _unresolvedIncompatibleRenderer.value = true
        _state.value = State.FAILED
        lastError = "could not track exact user-service binder death"
        lastErrorRes = null
        pushSafeIdleTo(candidate, rawStatus)
        onAvailabilityChanged?.invoke()
        Thread({
            runCatching { candidate.destroy() }.onFailure {
                Log.w(TAG, "untrackable user-service direct destroy failed", it)
            }
        }, "hilight-shizuku-untrackable-destroy").apply { isDaemon = true }.start()
    }

    private fun handleExactServiceBinderDeath(binder: IBinder) {
        if (!confirmedDeadBinders.add(binder)) return
        directDestroyBinders.remove(binder)

        if (quarantinedServices.remove(binder) != null) {
            clearBinderTracking(binder)
            if (!maybeCompleteExactServiceExit()) restoreActiveServiceAfterQuarantine()
            return
        }
        maybeCompleteExactServiceExit()
    }

    /** Completes only when the primary and every unexpected candidate are proved dead. */
    private fun maybeCompleteExactServiceExit(): Boolean {
        val primary = releaseTerminationBinder ?: incompatibleService?.asBinder()
            ?: service?.asBinder() ?: return false
        if (untrackableCandidatePresent) return false
        if (!canCompleteTrackedShizukuExit(
                primary,
                confirmedDeadBinders,
                quarantinedServices.keys,
            )
        ) return false

        val exit = exactExitFor(primary)
        if (releaseTerminationBinder == primary) {
            completeReleasedServiceTermination(exit)
        } else {
            completeNormalServiceExit(primary, exit)
        }
        return true
    }

    /** A stayed compatible and live while unexpected B was destroyed; restore A only after B dies. */
    private fun restoreActiveServiceAfterQuarantine() {
        if (quarantinedServices.isNotEmpty() || untrackableCandidatePresent ||
            releaseTerminationBinder != null || incompatibleService != null ||
            startupRemovalPending || connectionRemovalTombstone
        ) return
        val current = service ?: return
        val binder = current.asBinder()
        if (binder in confirmedDeadBinders || !binder.pingBinder()) return
        _unresolvedIncompatibleRenderer.value = false
        _state.value = State.CONNECTED
        onAvailabilityChanged?.invoke()
    }

    private fun exactExitFor(binder: IBinder): ShizukuServiceExit {
        val raw = when {
            incompatibleService?.asBinder() == binder -> incompatibleStatus
            lastCompleteStatusBinder == binder -> lastCompleteStatus
            else -> null
        }
        val status = raw?.let { helperStatusFromJson(it, binderAlive = false) }
            ?: HelperStatus(
                alive = false,
                identityResolved = false,
                owner = "shizuku",
            )
        return ShizukuServiceExit(
            connectionGeneration = binderGenerations[binder] ?: 0L,
            status = status.copy(alive = false),
        )
    }

    private fun clearBinderTracking(binder: IBinder) {
        binderDeathRecipients.remove(binder)?.let { recipient ->
            runCatching { binder.unlinkToDeath(recipient, 0) }
        }
        binderGenerations.remove(binder)
        confirmedDeadBinders.remove(binder)
        directDestroyBinders.remove(binder)
        directDestroyAttempts.remove(binder)
    }

    private data class RendererCheck(val status: JSONObject?, val failure: String?)

    /** Reads and validates identity before the renderer can receive pending LED state. */
    private fun checkRenderer(candidate: IHiLightService): RendererCheck {
        val raw = runCatching { candidate.status() }.getOrElse {
            return RendererCheck(null, "renderer status failed: ${it.javaClass.simpleName}")
        }
        val status = runCatching { JSONObject(raw) }.getOrElse {
            return RendererCheck(null, "renderer status was not valid JSON")
        }
        val failure = shizukuRendererCompatibilityFailure(
            status,
            expectedServiceVersion,
            BuildConfig.VERSION_CODE,
        )
        return RendererCheck(status, failure)
    }

    private fun rejectIncompatibleRenderer(
        candidate: IHiLightService,
        status: JSONObject?,
        reason: String,
    ) {
        cancelConnectionTimeouts()
        connectionAttemptActive = false
        service = null
        incompatibleService = candidate
        incompatibleStatus = status?.let { JSONObject(it.toString()) }
        if (lastCompleteStatusBinder != candidate.asBinder()) clearLastCompleteStatus()
        _unresolvedIncompatibleRenderer.value = true
        lastError = null
        lastErrorRes = R.string.shizuku_error_renderer_incompatible
        rebindAfterCompatibilityDisconnect = !compatibilityRebindAttempted
        if (rebindAfterCompatibilityDisconnect) compatibilityRebindAttempted = true
        awaitingCompatibilityDisconnect = true
        _state.value = State.CONNECTING
        Log.w(TAG, "$reason; sending safe idle and removing exact old user service")
        pushSafeIdleTo(candidate, status)
        // Fence every other transport before asking Shizuku to remove the rejected daemon.
        onAvailabilityChanged?.invoke()
        requestTrackedRemoval(candidate)
    }

    /** Retains a late/dead candidate as the sole primary fence until that exact binder dies. */
    private fun retainCandidateForRemoval(
        candidate: IHiLightService,
        rebindAfterExit: Boolean,
        normalExit: Boolean = false,
    ) {
        val binder = candidate.asBinder()
        val rawStatus = runCatching { JSONObject(candidate.status()) }.getOrNull()
        service = null
        incompatibleService = candidate
        incompatibleStatus = rawStatus?.let { JSONObject(it.toString()) }
        if (lastCompleteStatusBinder != binder) clearLastCompleteStatus()
        normalExitPending = normalExit || normalExitPending
        awaitingCompatibilityDisconnect = true
        rebindAfterCompatibilityDisconnect = rebindAfterExit && !startupRemovalPending
        _unresolvedIncompatibleRenderer.value = true
        _state.value = if (normalExitPending) State.CONNECTING else State.FAILED
        pushSafeIdleTo(candidate, rawStatus)
        onAvailabilityChanged?.invoke()
        requestTrackedRemoval(candidate)
    }

    fun unbind() {
        if (releaseTerminationBinder != null) return
        connectionRemovalTombstone = true
        val fenced = incompatibleService
        if (fenced != null) {
            startupRemovalPending = true
            rebindAfterCompatibilityDisconnect = false
            normalExitPending = true
            awaitingCompatibilityDisconnect = true
            _unresolvedIncompatibleRenderer.value = true
            _state.value = State.CONNECTING
            requestTrackedRemoval(fenced)
            return
        }
        val current = service
        if (current != null) {
            startupRemovalPending = true
            service = null
            incompatibleService = current
            incompatibleStatus = lastCompleteStatus.takeIf {
                lastCompleteStatusBinder == current.asBinder()
            }
            normalExitPending = true
            awaitingCompatibilityDisconnect = true
            rebindAfterCompatibilityDisconnect = false
            _unresolvedIncompatibleRenderer.value = true
            _state.value = State.CONNECTING
            pushSafeIdleTo(current, incompatibleStatus)
            onAvailabilityChanged?.invoke()
            requestTrackedRemoval(current)
            return
        }
        if (connectionAttemptActive || _unresolvedIncompatibleRenderer.value) {
            startupRemovalPending = true
            normalExitPending = true
            awaitingCompatibilityDisconnect = true
            fenceUnknownConnection("unbind requested while user-service startup was unresolved")
            _state.value = State.CONNECTING
            main.removeCallbacks(compatibilityRemovalTimeout)
            main.postDelayed(compatibilityRemovalTimeout, REMOVE_CONFIRM_TIMEOUT_MS)
            runCatching { Shizuku.unbindUserService(args, connection, true) }.onFailure {
                Log.w(TAG, "could not cancel unresolved user-service startup", it)
            }
            return
        }
        _state.value = State.NOT_RUNNING
    }

    private fun pushSafeIdleTo(candidate: IHiLightService, status: JSONObject?) {
        val lastRevision = maxOf(
            status?.optLong("receivedStateRevision", 0L) ?: 0L,
            status?.optLong("settledStateRevision", 0L) ?: 0L,
            status?.optLong("releasedStateRevision", 0L) ?: 0L,
        )
        val revision = if (lastRevision == Long.MAX_VALUE) lastRevision else lastRevision + 1L
        val idle = Bridge.incompatibleRendererSafeIdleJson(revision)
        pending = idle
        runCatching { candidate.setState(idle) }.onFailure {
            Log.w(TAG, "best-effort safe idle push failed", it)
        }
    }

    private fun requestTrackedRemoval(candidate: IHiLightService) {
        main.removeCallbacks(compatibilityRemovalTimeout)
        main.postDelayed(compatibilityRemovalTimeout, REMOVE_CONFIRM_TIMEOUT_MS)
        val managerAvailable = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        if (managerAvailable) {
            val result = runCatching { Shizuku.unbindUserService(args, connection, true) }
            if (result.isSuccess) return
            Log.w(TAG, "removeUserService failed; trying direct binder destroy", result.exceptionOrNull())
        }
        requestDirectDestroy(candidate)
    }

    private fun requestDirectDestroy(candidate: IHiLightService) {
        val binder = candidate.asBinder()
        if (binder !in binderGenerations || binder in confirmedDeadBinders) return
        val attempts = directDestroyAttempts[binder] ?: 0
        if (attempts >= MAX_DIRECT_DESTROY_ATTEMPTS) return
        if (!directDestroyBinders.add(binder)) return
        directDestroyAttempts[binder] = attempts + 1
        Thread({
            val result = runCatching { candidate.destroy() }.onFailure {
                Log.w(TAG, "direct destroy failed; waiting for exact service-binder death", it)
            }
            main.post {
                directDestroyBinders.remove(binder)
                val stillTracked = binder in binderGenerations &&
                    binder !in confirmedDeadBinders
                if (stillTracked && directDestroyAttempts.getOrDefault(binder, 0) <
                    MAX_DIRECT_DESTROY_ATTEMPTS
                ) {
                    if (result.isFailure) {
                        Log.w(TAG, "retrying direct user-service destroy after transient failure")
                    }
                    main.postDelayed(
                        { requestDirectDestroy(candidate) },
                        DIRECT_DESTROY_RETRY_MS,
                    )
                }
            }
        }, "hilight-shizuku-destroy").apply { isDaemon = true }.start()
    }

    private fun quarantineUnexpectedCandidate(candidate: IHiLightService) {
        val binder = candidate.asBinder()
        trackServiceBinder(candidate)
        quarantinedServices[binder] = candidate
        _unresolvedIncompatibleRenderer.value = true
        _state.value = State.FAILED
        pushSafeIdleTo(candidate, null)
        onAvailabilityChanged?.invoke()
        requestDirectDestroy(candidate)
    }

    private fun handleManagerBinderDeath() {
        cancelConnectionTimeouts()
        connectionAttemptActive = false

        if (releaseTerminationBinder != null) {
            _state.value = State.FAILED
            (service ?: incompatibleService)?.let { requestDirectDestroy(it) }
            return
        }

        val candidate = service ?: incompatibleService
        if (candidate != null) {
            val snapshot = if (service != null) {
                lastCompleteStatus.takeIf { lastCompleteStatusBinder == candidate.asBinder() }
            } else {
                incompatibleStatus
            }
            service = null
            incompatibleService = candidate
            incompatibleStatus = snapshot?.let { JSONObject(it.toString()) }
            managerDeathExitPending = true
            normalExitPending = false
            awaitingCompatibilityDisconnect = true
            rebindAfterCompatibilityDisconnect = false
            _unresolvedIncompatibleRenderer.value = true
            _state.value = State.FAILED
            Log.w(TAG, "Shizuku manager died; retaining and directly destroying service binder")
            pushSafeIdleTo(candidate, snapshot)
            onAvailabilityChanged?.invoke()
            main.removeCallbacks(compatibilityRemovalTimeout)
            main.postDelayed(compatibilityRemovalTimeout, REMOVE_CONFIRM_TIMEOUT_MS)
            requestDirectDestroy(candidate)
            return
        }

        if (_unresolvedIncompatibleRenderer.value || peekReportedExisting || createBindIssued) {
            fenceUnknownConnection("Shizuku manager died while service existence was unresolved")
            return
        }
        _state.value = State.NOT_RUNNING
        onAvailabilityChanged?.invoke()
    }

    private fun completeNormalServiceExit(binder: IBinder, exit: ShizukuServiceExit) {
        val shouldRebind = rebindAfterCompatibilityDisconnect && !startupRemovalPending
        connectionRemovalTombstone = true
        clearConnectionAttemptState()
        main.removeCallbacks(compatibilityRemovalTimeout)
        if (service?.asBinder() == binder) service = null
        if (incompatibleService?.asBinder() == binder) {
            incompatibleService = null
            incompatibleStatus = null
        }
        if (lastCompleteStatusBinder == binder) clearLastCompleteStatus()
        clearBinderTracking(binder)
        awaitingCompatibilityDisconnect = false
        rebindAfterCompatibilityDisconnect = false
        normalExitPending = false
        managerDeathExitPending = false
        startupRemovalPending = false

        if (shouldRebind) {
            _state.value = State.CONNECTING
            _unresolvedIncompatibleRenderer.value = true
        } else {
            _unresolvedIncompatibleRenderer.value = false
            _state.value = if (lastErrorRes == R.string.shizuku_error_renderer_incompatible) {
                State.FAILED
            } else {
                State.NOT_RUNNING
            }
            compatibilityRebindAttempted = false
        }
        // Notify Store with the dead process identity even when an in-transport replacement follows.
        // The unresolved fence stays raised until that replacement itself validates as current.
        notifyConfirmedNormalExit(exit)
        if (shouldRebind) main.post { beginConnectionAttempt(keepOwnershipFence = true) }
    }

    private fun notifyConfirmedNormalExit(exit: ShizukuServiceExit) {
        val callback = onConfirmedServiceExit
        if (callback != null) callback(exit) else onAvailabilityChanged?.invoke()
    }

    private fun clearLastCompleteStatus() {
        lastCompleteStatusBinder = null
        lastCompleteStatus = null
    }

    /**
     * Uses the bound shell-UID service to stop the exact release-acknowledged adb source. The RPC is
     * intentionally not a command runner: it can only identify and stop HiLight adb helpers.
     */
    fun stopReleasedAdbRenderers(
        source: HelperStatus,
        revision: Long,
        onComplete: (Boolean) -> Unit,
    ) {
        val destination = service
        if (destination == null || source.owner != "adb" || source.pid <= 0 ||
            !Bridge.BridgeStatusCache.validInstanceId(source.rendererInstanceId) ||
            !source.provesReleasedRevision(revision, source.rendererInstanceId)
        ) {
            main.post { onComplete(false) }
            return
        }
        Thread({
            val stopped = runCatching {
                destination.stopAdbRenderers(source.pid, source.rendererInstanceId)
            }.getOrDefault(false)
            if (stopped) Bridge.forgetStatusInstance(source.rendererInstanceId)
            main.post { onComplete(stopped) }
        }, "hilight-adb-exit").apply { isDaemon = true }.start()
    }

    /** Upgrade path for v1.0.8: exact legacy PID only, then RPC verifies no helper remains. */
    fun stopLegacyAdbRenderer(source: HelperStatus, onComplete: (Boolean) -> Unit) {
        val destination = service
        if (destination == null || source.owner != "adb" || source.pid <= 0 ||
            source.rendererInstanceId.isNotEmpty() || !source.rendererStale
        ) {
            main.post { onComplete(false) }
            return
        }
        Thread({
            val stopped = runCatching {
                destination.stopAdbRenderers(source.pid, "")
            }.getOrDefault(false)
            main.post { onComplete(stopped) }
        }, "hilight-legacy-adb-exit").apply { isDaemon = true }.start()
    }

    /** A heartbeat-expired identity is never absence proof; the RPC confirms exact process exit. */
    fun stopUnresponsiveAdbRenderers(source: HelperStatus, onComplete: (Boolean) -> Unit) {
        val destination = service
        val instance = source.rendererInstanceId
        if (destination == null || source.owner != "adb" || source.pid <= 0 || source.alive ||
            (instance.isNotEmpty() && !Bridge.BridgeStatusCache.validInstanceId(instance))
        ) {
            main.post { onComplete(false) }
            return
        }
        Thread({
            val stopped = runCatching {
                destination.stopAdbRenderers(source.pid, instance)
            }.getOrDefault(false)
            if (stopped && instance.isNotEmpty()) Bridge.forgetStatusInstance(instance)
            main.post { onComplete(stopped) }
        }, "hilight-unresponsive-adb-exit").apply { isDaemon = true }.start()
    }

    fun stopFatalAdbRenderers(source: HelperStatus, onComplete: (Boolean) -> Unit) {
        val destination = service
        if (destination == null || source.owner != "adb" || source.pid <= 0 ||
            !source.blackClearUnreleasedFatal ||
            !Bridge.BridgeStatusCache.validInstanceId(source.rendererInstanceId)
        ) {
            main.post { onComplete(false) }
            return
        }
        Thread({
            val stopped = runCatching {
                destination.stopAdbRenderers(source.pid, source.rendererInstanceId)
            }.getOrDefault(false)
            if (stopped) Bridge.forgetStatusInstance(source.rendererInstanceId)
            main.post { onComplete(stopped) }
        }, "hilight-fatal-adb-exit").apply { isDaemon = true }.start()
    }

    /** Removes this exact released Shizuku process and completes only after binder disconnect. */
    @Synchronized
    fun stopReleasedService(
        source: HelperStatus,
        revision: Long,
        onComplete: (Boolean) -> Unit,
    ) {
        if (releaseTerminationBinder != null) {
            val current = status()
            if (canJoinShizukuTermination(
                    callbackStillPending = releaseTerminationCallback != null,
                    source = source,
                    current = current,
                )
            ) {
                releaseTerminationWaiters += onComplete
            } else {
                main.post { onComplete(false) }
            }
            return
        }
        val current = status()
        val binder = service?.asBinder()
        if (binder == null || !binder.pingBinder() || source.owner != "shizuku" ||
            source.pid <= 0 ||
            current.pid != source.pid || !current.provesReleasedRevision(revision)
        ) {
            main.post { onComplete(false) }
            return
        }
        service?.let { trackServiceBinder(it) }
        pending = null
        connectionRemovalTombstone = true
        releaseTerminationBinder = binder
        releaseTerminationCallback = onComplete
        _unresolvedIncompatibleRenderer.value = true
        _state.value = State.CONNECTING
        main.removeCallbacks(releaseTerminationTimeout)
        main.postDelayed(releaseTerminationTimeout, RELEASE_CONFIRM_TIMEOUT_MS)
        val removed = runCatching { Shizuku.unbindUserService(args, connection, true) }
        if (removed.isFailure) {
            service?.let { requestDirectDestroy(it) }
            failReleasedServiceTermination("removeUserService failed during release handoff")
        }
    }

    @Synchronized
    fun stopFatalService(source: HelperStatus, onComplete: (Boolean) -> Unit) {
        if (releaseTerminationBinder != null) {
            val current = status()
            if (canJoinShizukuTermination(
                    callbackStillPending = releaseTerminationCallback != null,
                    source = source,
                    current = current,
                )
            ) {
                releaseTerminationWaiters += onComplete
            } else {
                main.post { onComplete(false) }
            }
            return
        }
        val current = status()
        val binder = service?.asBinder()
        if (binder == null || !binder.pingBinder() || source.owner != "shizuku" ||
            source.pid <= 0 ||
            current.pid != source.pid || !current.blackClearUnreleasedFatal
        ) {
            main.post { onComplete(false) }
            return
        }
        service?.let { trackServiceBinder(it) }
        pending = null
        connectionRemovalTombstone = true
        releaseTerminationBinder = binder
        releaseTerminationCallback = onComplete
        _unresolvedIncompatibleRenderer.value = true
        _state.value = State.CONNECTING
        main.removeCallbacks(releaseTerminationTimeout)
        main.postDelayed(releaseTerminationTimeout, RELEASE_CONFIRM_TIMEOUT_MS)
        val removed = runCatching { Shizuku.unbindUserService(args, connection, true) }
        if (removed.isFailure) {
            service?.let { requestDirectDestroy(it) }
            failReleasedServiceTermination("removeUserService failed during fatal handoff")
        }
    }

    @Synchronized
    private fun completeReleasedServiceTermination(exit: ShizukuServiceExit) {
        val callback = releaseTerminationCallback
        val timedOut = callback == null
        val callbacks = buildList {
            if (callback != null) add(callback)
            addAll(releaseTerminationWaiters)
        }
        val binder = releaseTerminationBinder ?: return
        main.removeCallbacks(releaseTerminationTimeout)
        releaseTerminationBinder = null
        releaseTerminationCallback = null
        releaseTerminationWaiters.clear()
        clearConnectionAttemptState()
        service = null
        incompatibleService = null
        incompatibleStatus = null
        _unresolvedIncompatibleRenderer.value = false
        clearLastCompleteStatus()
        clearBinderTracking(binder)
        startupRemovalPending = false
        connectionRemovalTombstone = true
        awaitingCompatibilityDisconnect = false
        normalExitPending = false
        managerDeathExitPending = false
        _state.value = State.NOT_RUNNING
        if (callbacks.isNotEmpty()) callbacks.forEach { it(true) }
        else if (timedOut) notifyConfirmedNormalExit(exit)
    }

    @Synchronized
    private fun failReleasedServiceTermination(reason: String) {
        val callback = releaseTerminationCallback ?: return
        val callbacks = buildList {
            add(callback)
            addAll(releaseTerminationWaiters)
        }
        main.removeCallbacks(releaseTerminationTimeout)
        // Keep the exact binder/service identity. Timeout and ping failure are not exit proof.
        releaseTerminationCallback = null
        releaseTerminationWaiters.clear()
        _state.value = State.FAILED
        Log.w(TAG, reason)
        (service ?: incompatibleService)?.let { requestDirectDestroy(it) }
        callbacks.forEach { it(false) }
    }

    /**
     * Stages and best-effort sends the strictly disabled compatibility document. Even a successful
     * Binder call is not release proof, so the unresolved ownership fence remains in place.
     */
    fun stageAndTryPushSafeIdle(json: String) {
        val safe = runCatching { JSONObject(json) }.getOrNull()?.let { o ->
            o.keys().asSequence().toSet() == setOf(
                "v",
                "stateRevision",
                "enabled",
                "arm",
                "privacyObserverEnabled",
                "privacyOutputEnabled",
            ) && o.optBoolean("enabled", true).not() &&
                o.optBoolean("arm", true).not() &&
                o.optBoolean("privacyObserverEnabled", true).not() &&
                o.optBoolean("privacyOutputEnabled", true).not()
        } == true
        if (!safe) {
            Log.e(TAG, "refusing non-idle state for incompatible renderer")
            return
        }
        pending = json
        val rejected = incompatibleService.takeIf {
            _unresolvedIncompatibleRenderer.value
        } ?: return
        runCatching { rejected.setState(json) }.onFailure {
            Log.w(TAG, "best-effort idle push to incompatible renderer failed", it)
        }
    }

    /** Our own explanation, as a resource id, or null when the failure came with its own text. */
    fun errorRes(): Int? = lastErrorRes

    /** Untranslated text straight from the failure, when there is any. */
    fun errorText(): String? = lastError

    override fun push(json: String) {
        pending = json
        val s = service ?: return
        runCatching { s.setState(json) }.onFailure {
            Log.w(TAG, "setState failed", it)
            service = null
            incompatibleService = s
            incompatibleStatus = lastCompleteStatus.takeIf {
                lastCompleteStatusBinder == s.asBinder()
            }
            awaitingCompatibilityDisconnect = true
            rebindAfterCompatibilityDisconnect = true
            _unresolvedIncompatibleRenderer.value = true
            _state.value = State.CONNECTING
            onAvailabilityChanged?.invoke()
            requestTrackedRemoval(s)
        }
    }

    override fun status(): HelperStatus {
        val s = service ?: incompatibleService ?: return HelperStatus(alive = false)
        val binder = s.asBinder()
        val binderAlive = binder.pingBinder()
        val liveStatus = runCatching { JSONObject(s.status()) }.getOrNull()
        val o = if (s === service) {
            val previous = lastCompleteStatus.takeIf { lastCompleteStatusBinder == binder }
            val retained = retainedShizukuStatus(liveStatus, previous)
            if (liveStatus != null && retained === liveStatus) {
                lastCompleteStatusBinder = binder
                lastCompleteStatus = JSONObject(liveStatus.toString())
            }
            retained
        } else if (liveStatus != null && isCompleteShizukuRendererStatus(liveStatus)) {
            incompatibleStatus = JSONObject(liveStatus.toString())
            liveStatus
        } else {
            incompatibleStatus ?: liveStatus
        }
        return if (o == null) {
            HelperStatus(alive = binderAlive, identityResolved = false, owner = "shizuku")
        } else helperStatusFromJson(o, binderAlive)
    }

    private fun helperStatusFromJson(o: JSONObject, binderAlive: Boolean): HelperStatus =
        runCatching {
            HelperStatus(
                alive = binderAlive,
                identityResolved = isCompleteShizukuRendererStatus(o),
                ageMs = 0,
                pid = o.optInt("pid", -1),
                uid = o.optInt("uid", -1),
                owner = "shizuku",
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
                rendererImplementationRevision = o.optInt("rendererImplementationRevision", -1),
                rendererStatusSchemaVersion = o.optInt(
                    "rendererStatusSchemaVersion",
                    o.optInt("version", -1),
                ),
                rendererClearAlgorithmVersion = o.optInt("rendererClearAlgorithmVersion", -1),
                rendererServiceVersion = o.optInt("rendererServiceVersion", -1),
                rendererReady = o.optBoolean("rendererReady", false),
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
            )
        }.getOrDefault(
            HelperStatus(alive = binderAlive, identityResolved = false, owner = "shizuku"),
        )

    companion object {
        const val SHIZUKU_PKG = "moe.shizuku.privileged.api"
        const val PERMISSION_REQUEST = 4242
        internal const val MIN_SHIZUKU_SERVER_VERSION = 12
        internal const val PEEK_CALLBACK_TIMEOUT_MS = 4_000L
        internal const val STARTUP_CALLBACK_TIMEOUT_MS = 15_000L
        internal const val REMOVE_CONFIRM_TIMEOUT_MS = 12_000L
        /** Engine stop is bounded at four seconds; Store's handoff remains the outer fence. */
        internal const val RELEASE_CONFIRM_TIMEOUT_MS = 6_500L
        private const val DIRECT_DESTROY_RETRY_MS = 5_000L
        private const val MAX_DIRECT_DESTROY_ATTEMPTS = 2
        private const val TAG = "HiLightShizuku"
    }
}
