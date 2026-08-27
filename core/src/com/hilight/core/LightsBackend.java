package com.hilight.core;

import android.hardware.lights.Light;
import android.hardware.lights.LightState;
import android.os.Binder;
import android.os.IBinder;

import java.lang.reflect.Method;
import java.util.List;

/**
 * Thin wrapper over the hidden ILightsManager binder interface.
 *
 * Reflection rather than the public android.hardware.lights.LightsManager because that needs a
 * Context, and both callers here (the adb-launched helper and the Shizuku user service) are plain
 * processes. The binder route also exposes openSession(token, priority), which the public API does
 * not.
 *
 * Requires android.permission.CONTROL_DEVICE_LIGHTS, which is signature|privileged — this class only
 * works in a process running as the shell UID (2000) or root.
 */
public final class LightsBackend {

    // The app exposes priorities only in [-10, 10]. Keep cleanup well below that range without
    // using an arithmetic-overflow sentinel in case a vendor LightsService sorts by subtraction.
    static final int CLEANUP_PRIORITY = -1_000;
    static final long CLEANUP_RETRY_MS = 1_000;
    /** Caps untrusted vendor metadata so two settle waits cannot defeat lifecycle bounds. */
    static final long MAX_CLEAR_SETTLE_MS = 250;
    static final int MAX_CLEANUP_BORROWS = 3;
    static final int MAX_CLOSE_FAILURES = 3;

    /**
     * Truthful software evidence only. None of these values claims that physical LEDs are dark.
     */
    enum ClearResult {
        NOT_REQUESTED,
        DEFERRED,
        BINDER_ACCEPTED_UNVERIFIED,
        FRAMEWORK_EFFECTIVE_UNVERIFIED,
        SHADOWED,
        IO_FAILED,
        COMPLETED_UNVERIFIED,
        EXHAUSTED
    }

    enum ClearStage {
        NONE,
        REQUESTED,
        WAITING_RETRY,
        OPEN_SESSION,
        ALPHA_BLACK_WRITE,
        ALPHA_BLACK_READBACK,
        WAIT_AFTER_ALPHA_BLACK,
        CANONICAL_BLACK_WRITE,
        WAIT_AFTER_CANONICAL_BLACK,
        CANONICAL_BLACK_READBACK,
        CLOSE_SESSION,
        CLOSED,
        NO_LIGHTS,
        CLOSE_EXHAUSTED
    }

    /** What armed the current bounded cycle. This describes software intent, not physical output. */
    enum ClearCycleSource {
        AUTOMATIC,
        MANUAL
    }

    private enum ProbeResult { UNAVAILABLE, EFFECTIVE, SHADOWED }

    @FunctionalInterface
    interface Clock {
        long nowMs();
    }

    /** Small seam around the hidden binder API; production uses reflection, host tests use a fake. */
    interface SessionIo {
        boolean open(int priority) throws Exception;
        boolean close() throws Exception;
        boolean set(int[] ids, int[] colors) throws Exception;

        /** Null means this platform cannot expose effective framework state. */
        default Integer get(int id) throws Exception { return null; }
    }

    private SessionIo io;
    private final Clock clock;
    private final ForcedBlackClear.Sleeper sleeper;
    private int[] ids = new int[0];
    private boolean sessionOpen;
    private int sessionPriority = Integer.MIN_VALUE;
    private long minUpdatePeriodMs = Engine.FRAME_MS;
    /** A cycle is armed at startup and by the first visible write attempt of each output episode. */
    private boolean clearRequested = true;
    private long lastBorrowAttemptMs = Long.MIN_VALUE;
    private int cleanupBorrowsRemaining = MAX_CLEANUP_BORROWS;
    private int clearAttemptsUsed;
    private long clearCycleId = 1;
    private ClearCycleSource clearCycleSource = ClearCycleSource.AUTOMATIC;
    private boolean preReleaseAttempted;
    /**
     * True after the first visible write attempt for the current output episode. The attempt, not
     * Binder acknowledgement, arms cleanup because a failed transaction does not prove that the
     * framework or vendor driver ignored the frame.
     */
    private boolean lastAcceptedFrameVisible;
    private boolean cycleHadFrameworkEvidence;
    private boolean stopAttemptAvailable = true;
    private boolean stopAttemptRequested;
    /** One final close call beyond the ordinary retry budget, reset for each newly opened session. */
    private boolean finalCloseOverrideAvailable = true;
    /**
     * Terminal core state: every bounded close call failed and this process may still own a token.
     * Only host destruction can be relied upon after this point, so readiness/release must remain
     * fenced even though the cleanup driver itself is finished.
     */
    private boolean unreleasedFatal;

    private ClearResult lastClearResult = ClearResult.NOT_REQUESTED;
    private ClearResult lastAttemptResult = ClearResult.NOT_REQUESTED;
    private ClearStage lastClearStage = ClearStage.REQUESTED;
    private long lastClearTimestampMs;

    /** Restored after a temporary cleanup session closes; retained if that close must be retried. */
    private int priorityBeforeCleanup = Integer.MIN_VALUE;
    private boolean cleanupSessionAwaitingClose;
    private boolean cleanupSessionWasReserved;
    private ClearResult cleanupSessionEvidence = ClearResult.NOT_REQUESTED;
    private boolean sessionIoFailed;
    private int closeFailures;
    private long lastCloseAttemptMs = Long.MIN_VALUE;

    public LightsBackend() {
        this(android.os.SystemClock::elapsedRealtime, Thread::sleep);
    }

    private LightsBackend(Clock clock, ForcedBlackClear.Sleeper sleeper) {
        this.clock = clock;
        this.sleeper = sleeper;
    }

    /** Host-test constructor; avoids loading Android's Binder and LightState stubs. */
    LightsBackend(
            SessionIo io,
            int[] ids,
            long minUpdatePeriodMs,
            Clock clock,
            ForcedBlackClear.Sleeper sleeper
    ) {
        this(clock, sleeper);
        this.io = io;
        this.ids = ids.clone();
        this.minUpdatePeriodMs = minUpdatePeriodMs;
    }

    public void connect() throws Exception {
        // The injected host-test backend is already connected. Production instances start with no
        // SessionIo and continue through the reflected system-service setup below.
        if (io != null) return;
        IBinder b = (IBinder) Class.forName("android.os.ServiceManager")
                .getMethod("getService", String.class).invoke(null, "lights");
        if (b == null) throw new IllegalStateException("lights service missing");
        Class<?> iface = Class.forName("android.hardware.lights.ILightsManager");
        Object service = Class.forName("android.hardware.lights.ILightsManager$Stub")
                .getMethod("asInterface", IBinder.class).invoke(null, b);
        Method getLights = iface.getMethod("getLights");
        Method openSession = iface.getMethod("openSession", IBinder.class, int.class);
        Method closeSession = iface.getMethod("closeSession", IBinder.class);
        Method setLightStates = iface.getMethod(
                "setLightStates", IBinder.class, int[].class, LightState[].class);
        Method maybeGetLightState;
        try {
            maybeGetLightState = iface.getMethod("getLightState", int.class);
        } catch (NoSuchMethodException ignored) {
            maybeGetLightState = null;
        }
        Method getLightState = maybeGetLightState;

        io = new SessionIo() {
            private IBinder token;

            @Override
            public boolean open(int priority) throws Exception {
                if (token != null) return false;
                IBinder freshToken = new Binder();
                openSession.invoke(service, freshToken, priority);
                token = freshToken;
                return true;
            }

            @Override
            public boolean close() throws Exception {
                if (token == null) return true;
                closeSession.invoke(service, token);
                token = null;
                return true;
            }

            @Override
            public boolean set(int[] lightIds, int[] colors) throws Exception {
                if (token == null) return false;
                LightState[] states = new LightState[lightIds.length];
                for (int i = 0; i < lightIds.length; i++) {
                    states[i] = new LightState.Builder()
                            .setColor(colors[i % colors.length])
                            .build();
                }
                setLightStates.invoke(service, token, lightIds, states);
                return true;
            }

            @Override
            public Integer get(int id) throws Exception {
                if (getLightState == null) return null;
                LightState state = (LightState) getLightState.invoke(service, id);
                return state == null ? null : state.getColor();
            }
        };

        @SuppressWarnings("unchecked")
        List<Light> all = (List<Light>) getLights.invoke(service);
        int n = 0;
        for (Light l : all) if (l.getType() == Light.LIGHT_TYPE_APPLICATION) n++;
        ids = new int[n];
        int k = 0;
        for (Light l : all) {
            if (l.getType() != Light.LIGHT_TYPE_APPLICATION) continue;
            ids[k++] = l.getId();
            minUpdatePeriodMs = Math.max(minUpdatePeriodMs, l.getMinUpdatePeriodMillis());
        }
        describe(all);
    }

    /**
     * Logs what the framework reports about every light it hands us.
     *
     * Worth the few lines: it is the first thing needed to tell "this device has no addressable
     * array" apart from "the renderer never got hold of it", which is otherwise guesswork from a bug
     * report, and it is the only place these capabilities are observable — `dumpsys lights` shows
     * ids and colours but nothing about control.
     */
    private void describe(List<Light> all) {
        for (Light l : all) {
            if (l.getType() != Light.LIGHT_TYPE_APPLICATION) continue;
            String period;
            try {
                period = l.getMinUpdatePeriodMillis() + "ms";
            } catch (Throwable t) {
                period = "unknown";
            }
            Log.i("light id=" + l.getId()
                    + " ordinal=" + l.getOrdinal()
                    + " type=" + l.getType()
                    + " rgb=" + l.hasRgbControl()
                    + " brightness=" + l.hasBrightnessControl()
                    + " animation=" + l.hasAnimationControl()
                    + " minUpdatePeriod=" + period);
        }
    }

    public int ledCount() { return ids.length; }

    public boolean isSessionOpen() { return sessionOpen; }

    public int sessionPriority() { return sessionPriority; }

    boolean isBlackClearPending() {
        return !unreleasedFatal && (clearRequested || cleanupSessionAwaitingClose);
    }

    boolean isBlackClearTerminal() {
        return unreleasedFatal || (!clearRequested && !cleanupSessionAwaitingClose);
    }

    boolean isUnreleasedFatal() { return unreleasedFatal; }

    int cleanupBorrowsRemaining() { return cleanupBorrowsRemaining; }

    int clearAttemptsUsed() { return clearAttemptsUsed; }

    boolean stopClearAttemptAvailable() { return stopAttemptAvailable; }

    long clearCycleId() { return clearCycleId; }

    ClearCycleSource clearCycleSource() { return clearCycleSource; }

    ClearResult lastClearResult() { return lastClearResult; }

    ClearResult lastClearAttemptResult() { return lastAttemptResult; }

    ClearStage lastClearStage() { return lastClearStage; }

    long lastClearTimestampMs() { return lastClearTimestampMs; }

    int closeFailureCount() { return closeFailures; }

    long minUpdatePeriodMs() { return minUpdatePeriodMs; }

    String clearStrategyId() { return ForcedBlackClear.STRATEGY_ID; }

    int clearStrategyVersion() { return ForcedBlackClear.STRATEGY_VERSION; }

    /**
     * Keeps a currently armed cycle moving. Duplicate idle documents deliberately cannot create a
     * new budget: only the first visible write attempt of a new output episode may arm another
     * automatic cycle.
     */
    void requestBlackClear() { /* coalesced by the already-armed cycle */ }

    /**
     * Explicit manual rearm. Unlike idle document replay, this creates a fresh bounded
     * cycle only after the prior one is terminal and every session has been released.
     */
    boolean requestBlackClearCycle() {
        if (sessionOpen || !isBlackClearTerminal()) return false;
        armClearCycle(ClearCycleSource.MANUAL);
        lastAcceptedFrameVisible = false;
        return true;
    }

    /** Requests the one reserved stop/manual attempt for this visible-output cycle. */
    void requestBlackClearNow() {
        if (unreleasedFatal) return;
        if (!stopAttemptAvailable) return;
        stopAttemptRequested = true;
        clearRequested = true;
        lastBorrowAttemptMs = Long.MIN_VALUE;
        lastCloseAttemptMs = Long.MIN_VALUE;
        record(ClearResult.DEFERRED, ClearStage.REQUESTED);
    }

    public boolean openSession(int priority) {
        if (sessionOpen) return sessionPriority == priority;
        if (unreleasedFatal) return false;
        try {
            if (io == null || !io.open(priority)) return false;
            sessionOpen = true;
            sessionPriority = priority;
            sessionIoFailed = false;
            closeFailures = 0;
            lastCloseAttemptMs = Long.MIN_VALUE;
            finalCloseOverrideAvailable = true;
            return true;
        } catch (Exception e) {
            Log.w("openSession failed: " + e);
            return false;
        }
    }

    public boolean closeSession() {
        if (!sessionOpen) return true;
        if (unreleasedFatal) return false;
        boolean clearClose = cleanupSessionAwaitingClose || preReleaseAttempted;
        boolean finalOverride = closeFailures >= MAX_CLOSE_FAILURES;
        if (finalOverride && !finalCloseOverrideAvailable) return false;
        if (closeFailures > 0 && elapsedSince(lastCloseAttemptMs) < CLEANUP_RETRY_MS) return false;
        if (finalOverride) finalCloseOverrideAvailable = false;
        lastCloseAttemptMs = clock.nowMs();
        if (clearClose) record(lastClearResult, ClearStage.CLOSE_SESSION);
        try {
            if (io == null || !io.close()) {
                onCloseFailure(clearClose, finalOverride);
                return false;
            }
        } catch (Exception e) {
            Log.w("closeSession failed: " + e);
            onCloseFailure(clearClose, finalOverride);
            return false;
        }
        boolean finishedCleanupAttempt = cleanupSessionAwaitingClose;
        boolean closedCleanupSession = sessionPriority == CLEANUP_PRIORITY;
        sessionOpen = false;
        sessionIoFailed = false;
        closeFailures = 0;
        lastCloseAttemptMs = Long.MIN_VALUE;
        finalCloseOverrideAvailable = true;
        if (closedCleanupSession) sessionPriority = priorityBeforeCleanup;
        else preReleaseAttempted = false;
        if (finishedCleanupAttempt) finishBorrowedAttempt();
        return true;
    }

    /**
     * Handles an RGB-dark trough inside an otherwise active animation.
     *
     * <p>This deliberately does not borrow a cleanup session, spend the pending automatic cycle,
     * mark it terminal, or clear {@link #lastAcceptedFrameVisible}. It sends the safe black
     * stimulus at most once through the currently open normal session and then closes that session.
     * A later visible frame may reopen a normal session without minting another cleanup cycle.</p>
     */
    boolean closeTransientDarkSession() {
        if (!sessionOpen) return true;
        if (sessionPriority == CLEANUP_PRIORITY) return closeSession();
        if (unreleasedFatal) return false;

        if (!preReleaseAttempted && !sessionIoFailed) {
            preReleaseAttempted = true;
            ClearResult evidence = applyBlackStimulus();
            lastAttemptResult = evidence;
            record(evidence, lastClearStage);
        } else if (sessionIoFailed) {
            preReleaseAttempted = true;
            record(ClearResult.DEFERRED, ClearStage.CLOSE_SESSION);
        }
        return closeSession();
    }

    /**
     * Forces two real black writes through Android before a session is closed.
     *
     * LightsService deduplicates the complete ARGB integer, while LightState explicitly ignores its
     * alpha channel. An alpha-only black first changes the framework state without lighting RGB,
     * then canonical black changes it again. Waiting for the hardware's advertised update period
     * keeps the second write from being coalesced by the Pixel light driver. This is a production-
     * safe mitigation candidate for the reported Pixel 11 latch; only affected-device observation
     * can establish whether the physical LEDs actually went dark.
     */
    ClearResult forceBlack() {
        if (unreleasedFatal) return lastClearResult;
        // forceBlack is reserved for semantic terminal/off cleanup. Transient animation troughs use
        // closeTransientDarkSession(), so resetting here lets a visible state that interrupts an
        // in-flight terminal cycle arm a fresh full budget without rearming on ordinary retries.
        lastAcceptedFrameVisible = false;
        if (cleanupSessionAwaitingClose) return retryCleanupClose();
        if (!clearRequested) return ClearResult.NOT_REQUESTED;
        if (ids.length == 0) {
            clearRequested = false;
            record(ClearResult.NOT_REQUESTED, ClearStage.NO_LIGHTS);
            return lastClearResult;
        }

        // While the normal render session still exists, send the black stimulus once. Repeated
        // close failures must never cause another pair of writes on every 30 fps idle tick.
        if (sessionOpen && sessionPriority != CLEANUP_PRIORITY) {
            if (preReleaseAttempted || sessionIoFailed) {
                // Once a failed render I/O is being released for a clear request, its close belongs
                // to this clear cycle and may truthfully exhaust it.
                if (sessionIoFailed) preReleaseAttempted = true;
                record(ClearResult.DEFERRED, ClearStage.CLOSE_SESSION);
                return lastClearResult;
            }
            preReleaseAttempted = true;
            ClearResult evidence = applyBlackStimulus();
            lastAttemptResult = evidence;
            record(evidence, lastClearStage);
            return evidence;
        }

        // A lifecycle stop never replaces the ordinary three-pass recovery. Its reserve is an
        // additional final pass after that budget is spent, or the only pass when the ordinary
        // cycle had already reached a terminal state before stop was requested.
        boolean reserved = stopAttemptRequested
                && stopAttemptAvailable
                && cleanupBorrowsRemaining <= 0;
        if (!reserved && cleanupBorrowsRemaining <= 0) {
            finishAutomaticCycle();
            return lastClearResult;
        }
        if (elapsedSince(lastBorrowAttemptMs) < CLEANUP_RETRY_MS) {
            record(ClearResult.DEFERRED, ClearStage.WAITING_RETRY);
            return lastClearResult;
        }

        lastBorrowAttemptMs = clock.nowMs();
        clearAttemptsUsed++;
        if (reserved) {
            stopAttemptAvailable = false;
            stopAttemptRequested = false;
        } else {
            cleanupBorrowsRemaining--;
        }

        priorityBeforeCleanup = sessionPriority;
        record(ClearResult.DEFERRED, ClearStage.OPEN_SESSION);
        // Cleanup may clear an unclaimed stale frame. This priority is below every app-supported
        // priority; readback reports when another effective framework state shadows the probe.
        if (!openSession(CLEANUP_PRIORITY)) {
            lastAttemptResult = ClearResult.IO_FAILED;
            finishAttemptWithoutSession(ClearResult.IO_FAILED, reserved);
            return lastClearResult;
        }

        ClearResult evidence = applyBlackStimulus();
        lastAttemptResult = evidence;
        cleanupSessionEvidence = evidence;
        cleanupSessionWasReserved = reserved;
        cleanupSessionAwaitingClose = true;
        record(evidence, ClearStage.CLOSE_SESSION);
        if (!closeSession()) {
            lastAttemptResult = ClearResult.IO_FAILED;
            if (closeFailures < MAX_CLOSE_FAILURES) {
                record(ClearResult.IO_FAILED, ClearStage.CLOSE_SESSION);
            }
        }
        return lastClearResult;
    }

    /** Pushes one frame. [colors] is indexed per LED; a shorter array is repeated. */
    public boolean push(int[] colors) {
        boolean visible = FrameVisibility.isVisible(colors);
        if (visible && !lastAcceptedFrameVisible && !unreleasedFatal) {
            // Arm before crossing Binder. A failed call is ambiguous: it cannot prove that neither
            // LightsService nor the vendor driver observed the attempted visible frame.
            beginVisibleCycle();
            lastAcceptedFrameVisible = true;
        }
        boolean accepted = pushRaw(colors, true);
        return accepted;
    }

    private boolean pushRaw(int[] colors, boolean closeOnFailure) {
        if (!sessionOpen || ids.length == 0 || colors.length == 0 || io == null) return false;
        if (unreleasedFatal) return false;
        if (sessionIoFailed) {
            if (closeOnFailure) closeSession();
            return false;
        }
        try {
            if (io.set(ids, colors)) return true;
        } catch (Exception e) {
            Log.w("setLightStates failed: " + e);
        }
        sessionIoFailed = true;
        // Tell the service the session is over before dropping the local flag. A transient binder
        // failure does not mean the far side closed anything, and simply forgetting the session here
        // left the caller reopening a token the service still held open.
        if (closeOnFailure) closeSession();
        return false;
    }

    private ClearResult applyBlackStimulus() {
        final ProbeResult[] alphaProbe = {ProbeResult.UNAVAILABLE};
        final ProbeResult[] canonicalProbe = {ProbeResult.UNAVAILABLE};
        final int[] acceptedWrites = {0};
        boolean accepted = ForcedBlackClear.apply(
                ForcedBlackClear.Stimulus.ALPHA_BLACK_THEN_ZERO,
                Math.max(1, Math.min(minUpdatePeriodMs, MAX_CLEAR_SETTLE_MS)),
                color -> {
                    ClearStage writeStage = color == ForcedBlackClear.RETRY_COLOR
                            ? ClearStage.ALPHA_BLACK_WRITE
                            : ClearStage.CANONICAL_BLACK_WRITE;
                    record(lastClearResult, writeStage);
                    if (!pushRaw(new int[]{color}, false)) return false;
                    acceptedWrites[0]++;
                    return true;
                },
                millis -> {
                    record(lastClearResult, acceptedWrites[0] == 1
                            ? ClearStage.WAIT_AFTER_ALPHA_BLACK
                            : ClearStage.WAIT_AFTER_CANONICAL_BLACK);
                    sleeper.sleep(millis);
                },
                color -> {
                    if (color == ForcedBlackClear.RETRY_COLOR) {
                        record(lastClearResult, ClearStage.ALPHA_BLACK_READBACK);
                        alphaProbe[0] = probeColor(ForcedBlackClear.RETRY_COLOR);
                    } else {
                        record(lastClearResult, ClearStage.CANONICAL_BLACK_READBACK);
                        canonicalProbe[0] = probeColor(ForcedBlackClear.CANONICAL_COLOR);
                    }
                }
        );
        if (!accepted) return ClearResult.IO_FAILED;
        if (alphaProbe[0] == ProbeResult.SHADOWED
                || canonicalProbe[0] == ProbeResult.SHADOWED) {
            return ClearResult.SHADOWED;
        }
        if (alphaProbe[0] == ProbeResult.EFFECTIVE
                && canonicalProbe[0] == ProbeResult.EFFECTIVE) {
            return ClearResult.FRAMEWORK_EFFECTIVE_UNVERIFIED;
        }
        return ClearResult.BINDER_ACCEPTED_UNVERIFIED;
    }

    private ProbeResult probeColor(int expectedColor) {
        boolean unavailable = false;
        for (int id : ids) {
            Integer color;
            try {
                color = io.get(id);
            } catch (Exception e) {
                Log.w("getLightState failed: " + e);
                unavailable = true;
                continue;
            }
            if (color == null) {
                unavailable = true;
                continue;
            }
            if (color != expectedColor) return ProbeResult.SHADOWED;
        }
        return unavailable ? ProbeResult.UNAVAILABLE : ProbeResult.EFFECTIVE;
    }

    private ClearResult retryCleanupClose() {
        if (closeSession()) return lastClearResult;
        if (!unreleasedFatal) {
            record(ClearResult.IO_FAILED, ClearStage.CLOSE_SESSION);
        }
        return lastClearResult;
    }

    private void finishBorrowedAttempt() {
        ClearResult evidence = cleanupSessionEvidence;
        boolean reserved = cleanupSessionWasReserved;
        cleanupSessionAwaitingClose = false;
        cleanupSessionWasReserved = false;
        cleanupSessionEvidence = ClearResult.NOT_REQUESTED;
        // A transient close error belongs to the in-flight attempt. Once that same session closes,
        // restore the stimulus evidence so `attemptResult=io_failed` never appears beside `closed`.
        lastAttemptResult = evidence;
        noteFrameworkEvidence(evidence);
        if (reserved) {
            clearRequested = false;
            finishTerminalCycle();
        } else if (cleanupBorrowsRemaining <= 0) {
            if (!keepReservedStopAttemptPending(evidence, ClearStage.CLOSED)) {
                finishAutomaticCycle();
            }
        } else {
            clearRequested = true;
            record(evidence, ClearStage.CLOSED);
        }
    }

    private void finishAttemptWithoutSession(ClearResult result, boolean reserved) {
        if (reserved) {
            clearRequested = false;
            finishTerminalCycle();
        } else if (cleanupBorrowsRemaining <= 0) {
            if (!keepReservedStopAttemptPending(result, ClearStage.OPEN_SESSION)) {
                finishAutomaticCycle();
            }
        } else {
            clearRequested = true;
            record(result, ClearStage.OPEN_SESSION);
        }
    }

    private void finishAutomaticCycle() {
        clearRequested = false;
        finishTerminalCycle();
    }

    private boolean keepReservedStopAttemptPending(ClearResult result, ClearStage stage) {
        if (!stopAttemptRequested || !stopAttemptAvailable) return false;
        clearRequested = true;
        record(result, stage);
        return true;
    }

    private void finishTerminalCycle() {
        lastAcceptedFrameVisible = false;
        record(cycleHadFrameworkEvidence
                        ? ClearResult.COMPLETED_UNVERIFIED
                        : ClearResult.EXHAUSTED,
                sessionOpen ? ClearStage.CLOSE_EXHAUSTED : ClearStage.CLOSED);
    }

    private void noteFrameworkEvidence(ClearResult result) {
        if (result == ClearResult.BINDER_ACCEPTED_UNVERIFIED
                || result == ClearResult.FRAMEWORK_EFFECTIVE_UNVERIFIED) {
            cycleHadFrameworkEvidence = true;
        }
    }

    private void beginVisibleCycle() {
        armClearCycle(ClearCycleSource.AUTOMATIC);
    }

    private void armClearCycle(ClearCycleSource source) {
        clearCycleId++;
        clearCycleSource = source;
        clearRequested = true;
        lastBorrowAttemptMs = Long.MIN_VALUE;
        cleanupBorrowsRemaining = MAX_CLEANUP_BORROWS;
        clearAttemptsUsed = 0;
        preReleaseAttempted = false;
        cycleHadFrameworkEvidence = false;
        stopAttemptAvailable = true;
        stopAttemptRequested = false;
        lastAttemptResult = ClearResult.NOT_REQUESTED;
        record(ClearResult.NOT_REQUESTED, ClearStage.REQUESTED);
    }

    private void onCloseFailure(boolean clearClose, boolean finalOverride) {
        closeFailures++;
        sessionIoFailed = true;
        if (finalOverride) {
            unreleasedFatal = true;
            clearRequested = false;
            lastAttemptResult = ClearResult.IO_FAILED;
            record(ClearResult.EXHAUSTED, ClearStage.CLOSE_EXHAUSTED);
            return;
        }
        if (clearClose) {
            lastAttemptResult = ClearResult.IO_FAILED;
            record(ClearResult.IO_FAILED, ClearStage.CLOSE_SESSION);
        }
    }

    private void record(ClearResult result, ClearStage stage) {
        lastClearResult = result;
        lastClearStage = stage;
        lastClearTimestampMs = clock.nowMs();
    }

    private long elapsedSince(long thenMs) {
        return thenMs == Long.MIN_VALUE ? Long.MAX_VALUE : clock.nowMs() - thenMs;
    }
}
