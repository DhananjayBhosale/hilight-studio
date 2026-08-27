package com.hilight.core;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The render loop, shared by all privileged hosts.
 *
 * State is one JSON document: {enabled, priority, ambient:{...}, alert:{id, durationMs, ...}}.
 * The alert layer wins while it lasts; a durationMs of 0 holds until the alert is replaced or the
 * "alert" key disappears. Everything else falls through to ambient.
 *
 * Ticks at the hardware's minimum update period (33 ms, ~30 fps).
 *
 * Nothing here runs forever, and the protections live here rather than in the UI so that no bug or
 * hostile state document can bypass them:
 *
 * <ul>
 *   <li>the ambient look has a deadline ("ambientTimeoutMs", 30 s by default) and blanks itself when
 *       it passes; only a state document with "arm" set — a deliberate user action — starts a new
 *       window, so alerts and background pushes cannot extend it</li>
 *   <li>an alert with no duration is held only up to that same cap, and a finite alert is clamped to
 *       {@link #ALERT_MAX_MS}</li>
 *   <li>{@link #MAX_DUTY} caps how much of any {@link #DUTY_WINDOW_MS} window the array may be lit;
 *       past that it rests until the window rolls over, so repeated alerts cannot keep it on</li>
 *   <li>brightness tapers to {@link #TAPER_FLOOR} once the array has been continuously lit for
 *       {@link #TAPER_AFTER_MS}, which limits sustained current through the LEDs</li>
 * </ul>
 *
 * These figures are deliberately conservative: stock HiLight only flashes briefly, so there is no
 * published guidance on how long this array is meant to run.
 */
public final class Engine {

    public static final long FRAME_MS = SafetyGuard.FRAME_MS;

    /** Hard ceiling for a single alert, whatever the app asks for. */
    public static final long ALERT_MAX_MS = 60_000;
    public static final long DEFAULT_AMBIENT_TIMEOUT_MS = 30_000;
    /** Renderer-side ceiling; raw bridge documents cannot bypass the app's five-minute limit. */
    public static final long MAX_AMBIENT_TIMEOUT_MS = 300_000;

    /** Duty-cycle guard: at most half of any ten-minute window may be lit. */
    public static final long DUTY_WINDOW_MS = SafetyGuard.DUTY_WINDOW_MS;
    public static final double MAX_DUTY = SafetyGuard.MAX_DUTY;
    /** Sustained-current guard: taper brightness after this much unbroken light. */
    public static final long TAPER_AFTER_MS = SafetyGuard.TAPER_AFTER_MS;
    public static final long TAPER_RAMP_MS = SafetyGuard.TAPER_RAMP_MS;
    public static final double TAPER_FLOOR = SafetyGuard.TAPER_FLOOR;
    static final int USER_PRIORITY_MIN = -10;
    static final int USER_PRIORITY_MAX = 10;
    private static final long LIFECYCLE_CLEAR_TIMEOUT_MS = 4_000;
    private static final long LIFECYCLE_CLEAR_POLL_MS = 25;

    /** Supplies time for renderer intervals. Values must be monotonic and include deep sleep. */
    interface ElapsedRealtimeClock {
        long nowMs();
    }

    private final LightsBackend lights;
    private final ElapsedRealtimeClock elapsedRealtimeClock;
    private final Renderer renderer = new Renderer();
    private final SafetyGuard safety;
    private final OutputGate gate = new OutputGate();
    private final Object lock = new Object();
    private final PrivacyScheduler privacyScheduler = new PrivacyScheduler();
    private final Map<String, JSONObject> privacyConfigs = new HashMap<>();
    private final AppOpsWatcher privacyWatcher;

    private Thread thread;
    private volatile boolean running;

    private JSONObject state = new JSONObject();
    private JSONObject alert;
    private long alertId = -1;
    private boolean lastFrameWasAlert;
    private boolean renderingPrivacy;
    private String renderedPrivacyRule;
    private PrivacyScheduler.Phase privacyPhase = PrivacyScheduler.Phase.INACTIVE;
    /** Most recent document accepted by {@link #setState(String)}. */
    private long appliedStateRevision;
    /** Most recent document acted on by the render thread. */
    private long settledStateRevision;
    /** Most recent document for which the renderer has closed its session and finished cleanup. */
    private long releasedStateRevision;
    /** Last valid positive manual cleanup request observed, accepted or rejected. */
    private long lastSeenManualBlackClearRequestId;
    /** Last manual cleanup request that actually armed a new bounded cycle. */
    private long lastAcceptedManualBlackClearRequestId;
    /** Monotonic identity for each valid state document accepted by setState. */
    private long stateGeneration;
    /** State generation whose visible frame most recently crossed into LightsBackend.push(). */
    private long lastVisibleAttemptGeneration = -1;
    /** Exact active output config that most recently attempted a visible frame. */
    private JSONObject lastVisibleConfig;
    private boolean stopped;

    private double dim = 1.0;
    private long ambientTimeoutMs = DEFAULT_AMBIENT_TIMEOUT_MS;

    public Engine() {
        this(
                new LightsBackend(),
                android.os.SystemClock::elapsedRealtime,
                new SafetyGuard()
        );
    }

    /** Host-test constructor for the renderer/session boundary. */
    Engine(LightsBackend lights) {
        this(lights, android.os.SystemClock::elapsedRealtime, new SafetyGuard());
    }

    /** Host-test constructor with a deterministic renderer clock. */
    Engine(LightsBackend lights, ElapsedRealtimeClock elapsedRealtimeClock) {
        this(lights, elapsedRealtimeClock, new SafetyGuard());
    }

    /** Host-test constructor with deterministic time and safety limits. */
    Engine(
            LightsBackend lights,
            ElapsedRealtimeClock elapsedRealtimeClock,
            SafetyGuard safety
    ) {
        this.lights = lights;
        this.elapsedRealtimeClock = elapsedRealtimeClock;
        this.safety = safety;
        privacyWatcher = new AppOpsWatcher(active -> {
            synchronized (lock) {
                privacyScheduler.updateActive(active, elapsedRealtimeClock.nowMs());
            }
        });
    }

    public void start() throws Exception {
        synchronized (lock) {
            // A stop that wins the lifecycle lock cancels startup permanently. Conversely, a stop
            // arriving during startup waits until the thread is published, then shuts it down; it
            // can never be followed by this method resurrecting the renderer.
            if (stopped) throw new IllegalStateException("renderer was already stopped");
            if (running || thread != null) throw new IllegalStateException("renderer already started");
            running = false;
            try {
                lights.connect();
                // Before advertising readiness, a newly loaded renderer must complete all three
                // bounded low-priority cleanup passes for state that a dead/stale predecessor may
                // have left behind. Framework completion is diagnostic evidence only; it does not
                // prove physical darkness.
                if (!prepareForReady()) {
                    throw new IllegalStateException(
                            "startup LED cleanup did not reach a closed terminal state");
                }
                Log.i("connected: " + lights.ledCount() + " HiLight LEDs");
                running = true;
                thread = new Thread(this::loop, "hilight-render");
                thread.setDaemon(false);
                thread.start();
            } catch (Throwable startupFailure) {
                // A failed start must not strand a Binder session. Preserve the original failure
                // while making the same bounded best-effort release used by normal host shutdown.
                try {
                    stop();
                } catch (Throwable cleanupFailure) {
                    startupFailure.addSuppressed(cleanupFailure);
                    try {
                        lights.closeSession();
                    } catch (Throwable finalCloseFailure) {
                        startupFailure.addSuppressed(finalCloseFailure);
                    }
                }
                if (startupFailure instanceof Exception) throw (Exception) startupFailure;
                if (startupFailure instanceof Error) throw (Error) startupFailure;
                throw new RuntimeException(startupFailure);
            }
        }
    }

    /**
     * Blanks the array and hands it back.
     *
     * Clearing `running` inside the lock matters: outside it, a tick that had already passed the
     * `while (running)` check could run after the session was closed, reopen it and push a live
     * frame, leaving the array lit by the very call that was meant to darken it.
     */
    public void stop() {
        synchronized (lock) {
            if (stopped) return;
            stopped = true;
            running = false;
            privacyWatcher.stop();
            // A stop is an explicit retry even if a prior release was accepted by the framework but
            // the physical panel stayed latched.
            boolean heldSession = lights.isSessionOpen();
            lights.requestBlackClearNow();
            lights.forceBlack();
            boolean released = !lights.isSessionOpen() || lights.closeSession();
            if (heldSession && released) {
                // v1.0.8 cleared only before release. Reclaim once after release as well so the
                // framework must send fresh black states through a new session.
                lights.requestBlackClearNow();
                lights.forceBlack();
            }
            if (!driveLifecycleClearToTerminal()) {
                Log.w("shutdown LED cleanup did not reach a closed terminal state before timeout");
            }
            markReleasedIfTerminal();
        }
    }

    public int ledCount() { return lights.ledCount(); }

    /** Replaces the whole state document. Safe to call from any thread. */
    public void setState(String json) {
        JSONObject o;
        try {
            o = new JSONObject(json);
        } catch (Exception e) {
            Log.w("bad state json: " + e);
            return;
        }
        synchronized (lock) {
            long elapsedRealtime = elapsedRealtimeClock.nowMs();
            stateGeneration++;
            boolean previouslyEnabled = state.optBoolean("enabled", false) ||
                    state.optBoolean("privacyOutputEnabled", false);
            boolean enabled = o.optBoolean("enabled", false) ||
                    o.optBoolean("privacyOutputEnabled", false);
            state = o;
            // A visible-to-dark transition starts one bounded cleanup cycle. Rewritten copies of the
            // same disabled document carry a new receipt revision, but must not mint fresh hardware
            // attempts: the render loop keeps driving any cycle that is already pending.
            if (previouslyEnabled && !enabled) lights.requestBlackClear();
            readPrivacyRules(o.optJSONArray("privacyRules"));
            if (o.optBoolean("privacyObserverEnabled", false)) {
                privacyWatcher.start();
            } else {
                privacyWatcher.stop();
                privacyScheduler.clearActive();
                privacyPhase = PrivacyScheduler.Phase.INACTIVE;
                renderingPrivacy = false;
                renderedPrivacyRule = null;
            }
            ambientTimeoutMs = Math.max(
                    1_000,
                    Math.min(
                            MAX_AMBIENT_TIMEOUT_MS,
                            o.optLong("ambientTimeoutMs", DEFAULT_AMBIENT_TIMEOUT_MS)
                    )
            );
            dim = Math.max(0.02, Math.min(1.0, o.optDouble("dim", 1.0)));
            // Only a deliberate user action ("arm") may start a fresh window. Automatic pushes — an
            // alert firing, a foreground override, the app being backgrounded — must not, or the array
            // could be kept lit indefinitely in 30-second increments.
            //
            // Defaulting to false matters: a document that omits the key must not arm. The app always
            // sends it, but the bootstrap file the app drops for a not-yet-running helper is just
            // {"enabled":false}, and defaulting to true let that open a window nobody asked for.
            if (o.optBoolean("arm", false)) {
                gate.armAmbient(elapsedRealtime, ambientTimeoutMs);
            }
            JSONObject a = o.optJSONObject("alert");
            if (a == null) {
                if (alert != null) Log.i("alert cleared");
                alert = null;
                alertId = -1;
                // clears the blank latch too, so a cancelled alert cannot leave the array lit
                gate.clearAlert();
                renderer.reset();
            } else {
                long id = a.optLong("id", -1);
                if (id != alertId) {
                    alertId = id;
                    alert = a;
                    long asked = a.optLong("durationMs", 4000);
                    // an open-ended alert (a "while this app is open" hold) still gets the global cap
                    long dur = asked <= 0 ? ambientTimeoutMs : Math.min(asked, ALERT_MAX_MS);
                    gate.startAlert(elapsedRealtime, dur);
                    renderer.reset();
                    Log.i("alert " + id + " " + a.optString("pattern", "pulse") + " for " + dur + "ms"
                            + (dur != asked ? " (asked " + asked + ", capped)" : ""));
                }
            }
            appliedStateRevision = o.optLong("stateRevision", appliedStateRevision);
            maybeAcceptManualBlackClear(o);
        }
    }

    public String status() {
        JSONObject o = new JSONObject();
        try {
            synchronized (lock) {
                o.put("pid", processPid());
                o.put("uid", processUid());
                o.put("ts", System.currentTimeMillis());
                o.put("ledCount", lights.ledCount());
                o.put("session", lights.isSessionOpen());
                o.put("blackClearPending", lights.isBlackClearPending());
                o.put("blackClearTerminal", lights.isBlackClearTerminal());
                o.put("blackClearUnreleasedFatal", lights.isUnreleasedFatal());
                o.put("blackClearResult", enumKey(lights.lastClearResult()));
                o.put("blackClearAttemptResult", enumKey(lights.lastClearAttemptResult()));
                o.put("blackClearStage", enumKey(lights.lastClearStage()));
                o.put("blackClearTimestampElapsedMs", lights.lastClearTimestampMs());
                o.put("blackClearCycleId", lights.clearCycleId());
                o.put("blackClearCycleSource", enumKey(lights.clearCycleSource()));
                o.put("blackClearAttemptsUsed", lights.clearAttemptsUsed());
                o.put("blackClearAttemptsRemaining", lights.cleanupBorrowsRemaining());
                o.put("blackClearStopAttemptAvailable", lights.stopClearAttemptAvailable());
                o.put("blackClearCloseFailures", lights.closeFailureCount());
                o.put("lightMinUpdatePeriodMs", lights.minUpdatePeriodMs());
                o.put("blackClearStrategy", lights.clearStrategyId());
                o.put("blackClearStrategyVersion", lights.clearStrategyVersion());
                o.put("priority", lights.sessionPriority());
                JSONObject amb = state.optJSONObject("ambient");
                o.put("mode", amb == null ? "off" : amb.optString("mode", "off"));
                o.put("alertId", alertId);
                o.put("timeoutMs", ambientTimeoutMs);
                o.put("dim", dim);
                o.put("ambientRemainingMs", gate.ambientRemainingMs(elapsedRealtimeClock.nowMs()));
                o.put("ambientHeld", gate.isAmbientHeld());
                o.put("resting", safety.isResting());
                o.put("dutyPct", safety.dutyPercent());
                o.put("receivedStateRevision", appliedStateRevision);
                // Kept for older app builds. Historically this meant the state parser had accepted
                // the document; it was never proof that the render thread had released the LEDs.
                o.put("appliedStateRevision", appliedStateRevision);
                o.put("settledStateRevision", settledStateRevision);
                o.put("releasedStateRevision", releasedStateRevision);
                o.put("lastSeenManualBlackClearRequestId", lastSeenManualBlackClearRequestId);
                o.put("lastAcceptedManualBlackClearRequestId",
                        lastAcceptedManualBlackClearRequestId);
                o.put("privacyObserverEnabled", state.optBoolean("privacyObserverEnabled", false));
                o.put("privacyObserverState", privacyWatcher.state().name().toLowerCase(Locale.ROOT));
                o.put("privacyPhase", privacyPhase.name().toLowerCase(Locale.ROOT));
                o.put("rendererContractVersion", RendererContract.CONTRACT_VERSION);
                o.put("rendererImplementationRevision", RendererContract.IMPLEMENTATION_REVISION);
                o.put("rendererStatusSchemaVersion", RendererContract.STATUS_SCHEMA_VERSION);
                o.put("rendererClearAlgorithmVersion", RendererContract.CLEAR_ALGORITHM_VERSION);
                o.put("version", RendererContract.STATUS_SCHEMA_VERSION);
            }
        } catch (Exception e) {
            // a status document is never worth crashing over
            Log.w("status construction failed: " + e);
        }
        return o.toString();
    }

    private void loop() {
        while (running) {
            try {
                tick();
                Thread.sleep(FRAME_MS);
            } catch (InterruptedException e) {
                if (!running) return;
                Log.w("render thread interrupted while active; continuing");
            } catch (Throwable t) {
                Log.w("frame failed: " + t);
                try {
                    Thread.sleep(250);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }
    }

    private void tick() {
        tick(elapsedRealtimeClock.nowMs(), true);
    }

    /**
     * Deterministic host-test entrypoint. The wall value is intentionally ignored: changing the
     * epoch clock must not alter a renderer deadline, animation phase, or safety window.
     */
    void tickForTest(long ignoredWallTimeMs, long elapsedRealtime) {
        tick(elapsedRealtime, false);
    }

    private void tick(long elapsedRealtime, boolean requireRunning) {
        synchronized (lock) {
            // stop() may have closed the session after the loop condition was sampled.
            if (requireRunning && !running) return;
            boolean enabled = state.optBoolean("enabled", false);
            boolean privacyOutputEnabled = state.optBoolean("privacyOutputEnabled", false);
            int priority = clampUserPriority(state.optInt("priority", 0));

            OutputGate.Layer layer = gate.next(elapsedRealtime);
            PrivacyScheduler.Decision privacy =
                    privacyScheduler.decision(elapsedRealtime);
            privacyPhase = privacy.phase;

            boolean privacyOwnsOutput = privacyOutputEnabled &&
                    (privacy.phase == PrivacyScheduler.Phase.LIT ||
                            privacy.phase == PrivacyScheduler.Phase.COOLDOWN);
            if (privacyOutputEnabled && privacy.phase == PrivacyScheduler.Phase.LIT) {
                gate.noteExternalOutput();
            }
            if (!enabled && !privacyOwnsOutput) {
                leavePrivacyRenderer();
                blankAndRelease(elapsedRealtime, "released HiLight to the system");
                return;
            }

            if (lastFrameWasAlert && layer != OutputGate.Layer.ALERT) {
                alert = null;
                renderer.reset();
                // deliberately no re-arm here: an alert must not extend the ambient window
            }
            lastFrameWasAlert = layer == OutputGate.Layer.ALERT;

            // Existing state documents had no source field, so they retain the old alert-first
            // behavior. New foreground holds identify themselves and yield to privacy activity.
            boolean finiteAlert = layer == OutputGate.Layer.ALERT &&
                    !"foreground".equals(alert == null ? "" : alert.optString("source", "legacy"));

            if (!finiteAlert && privacyOutputEnabled &&
                    privacy.phase == PrivacyScheduler.Phase.COOLDOWN) {
                renderingPrivacy = false;
                renderedPrivacyRule = null;
                renderer.reset();
                blankAndRelease(
                        elapsedRealtime,
                        "privacy cooldown — released HiLight to the system"
                );
                return;
            }

            JSONObject cfg;
            long t;
            if (!finiteAlert && privacyOutputEnabled && privacy.phase == PrivacyScheduler.Phase.LIT) {
                cfg = privacyConfigs.get(privacy.ruleId);
                if (cfg == null) {
                    renderingPrivacy = false;
                    renderedPrivacyRule = null;
                    return;
                }
                if (!renderingPrivacy || !privacy.ruleId.equals(renderedPrivacyRule)) renderer.reset();
                renderingPrivacy = true;
                renderedPrivacyRule = privacy.ruleId;
                t = privacy.phaseElapsedMs;
            } else switch (layer) {
                case ALERT:
                    leavePrivacyRenderer();
                    cfg = alert;
                    t = gate.alertElapsed(elapsedRealtime);
                    break;
                case AMBIENT:
                    leavePrivacyRenderer();
                    cfg = state.optJSONObject("ambient");
                    t = elapsedRealtime;
                    break;
                case BLANK:
                    // Blank, then let go. Holding an all-black session would keep winning over the
                    // system's own HiLight effects, so calls and Gemini would stay dark for as long
                    // as this process lived — and it outlives the app, so only a reboot fixed it.
                    leavePrivacyRenderer();
                    blankAndRelease(
                            elapsedRealtime,
                            "nothing left to show — released HiLight to the system"
                    );
                    return;
                default:                                    // IDLE: dark, and already handed back
                    leavePrivacyRenderer();
                    // BLANK is emitted once. If that attempt was throttled or failed, the pending
                    // clear still needs a driver; forceBlack is a zero-I/O no-op once it is accepted.
                    blankAndRelease(
                            elapsedRealtime,
                            "idle cleanup — released HiLight to the system"
                    );
                    return;
            }
            int[] frame = renderer.frame(cfg, t, Math.max(1, lights.ledCount()));
            int[] output = protect(frame, elapsedRealtime);

            // A threshold-dark trough inside an active animation is not the end of that effect. It
            // must close the normal render session so Android can use the LEDs, but must not spend
            // the three borrowed post-release passes or acknowledge the state as released. Terminal
            // darkness (explicit off, safety rest, gate expiry and disabled state) keeps the full
            // recovery path.
            if (!FrameVisibility.isVisible(output)) {
                if (isTransientAnimationDark(cfg)
                        && !safety.isResting()
                        && lastVisibleAttemptGeneration == stateGeneration
                        && cfg == lastVisibleConfig) {
                    transientDarkAndRelease(elapsedRealtime);
                } else {
                    blankAndRelease(
                            elapsedRealtime,
                            "dark terminal frame — released HiLight to the system"
                    );
                }
                return;
            }

            // The session is taken only while there is something visible to show, and reopened on
            // demand: a rule firing lands here and gets it back before the first lit frame.
            if (!lights.isSessionOpen() || priority != lights.sessionPriority()) {
                if (lights.isSessionOpen() && !lights.closeSession()) return;
                if (!lights.openSession(priority)) return;
            }
            lastVisibleAttemptGeneration = stateGeneration;
            lastVisibleConfig = cfg;
            if (!lights.push(output)) return;
            markSettled();
        }
    }

    /**
     * Applies the hardware protections to a frame: rests the array when it has been lit for too much
     * of the current window, and tapers brightness under sustained light.
     */
    private int[] protect(int[] frame, long elapsedRealtime) {
        return safety.apply(frame, elapsedRealtime, dim);
    }

    /**
     * Tells the guard the array is dark on a frame that is not pushed.
     *
     * The sustained-light taper only unwinds when the guard is handed a dark frame, and the frames
     * skipped while the array is already blank never reached it. Without this, a long ambient run
     * left the taper pinned, so the next look came back at the taper floor no matter how long the
     * array had actually been resting.
     */
    private void noteDark(long elapsedRealtime) {
        safety.apply(BLANK, elapsedRealtime, dim);
    }

    /** Hands the array back to Android, if we are holding it. */
    private boolean release(String why) {
        if (!lights.isSessionOpen()) return false;
        if (!lights.closeSession()) {
            Log.w("could not release HiLight session: " + why);
            return false;
        }
        Log.i(why);
        return true;
    }

    private void leavePrivacyRenderer() {
        if (renderingPrivacy) renderer.reset();
        renderingPrivacy = false;
        renderedPrivacyRule = null;
    }

    private void blankAndRelease(long elapsedRealtime, String why) {
        lights.forceBlack();
        if (release(why)) {
            // The reported latch survives a valid black state and session close. Borrowing only
            // after release forces LightsService through a fresh session instead of repeating the
            // v1.0.8 pre-release sequence. Further idle retries are bounded in LightsBackend.
            lights.requestBlackClear();
            lights.forceBlack();
        }
        noteDark(elapsedRealtime);
        markReleasedIfTerminal();
    }

    /** Releases a temporary animation trough without consuming or completing its cleanup cycle. */
    private void transientDarkAndRelease(long elapsedRealtime) {
        if (lights.isSessionOpen()) {
            if (lights.closeTransientDarkSession()) {
                Log.i("transient dark frame — released HiLight to the system");
                markSettled();
            } else {
                Log.w("could not release HiLight session: transient dark frame");
            }
        }
        noteDark(elapsedRealtime);
    }

    private void markSettled() {
        settledStateRevision = appliedStateRevision;
    }

    /**
     * Consumes an opaque one-shot recovery request from a full disabled state document.
     *
     * <p>Every new positive id is remembered before validation. Thus an enabled, alert-bearing,
     * privacy-output, open-session, or in-flight request cannot be replayed later after conditions
     * become safer. The app must issue a new id. The backend independently requires a closed,
     * terminal cycle, so this cannot reset an attempt budget already in progress.</p>
     */
    private void maybeAcceptManualBlackClear(JSONObject candidate) {
        if (!candidate.has("manualBlackClearRequestId")
                || candidate.isNull("manualBlackClearRequestId")) return;
        long requestId = candidate.optLong("manualBlackClearRequestId", 0);
        if (requestId <= 0 || requestId <= lastSeenManualBlackClearRequestId) return;
        lastSeenManualBlackClearRequestId = requestId;

        boolean fullyDisabled = !candidate.optBoolean("enabled", false)
                && !candidate.optBoolean("privacyOutputEnabled", false)
                && (!candidate.has("alert") || candidate.isNull("alert"));
        if (!fullyDisabled || lights.isSessionOpen() || !lights.isBlackClearTerminal()) return;
        if (lights.requestBlackClearCycle()) {
            lastAcceptedManualBlackClearRequestId = requestId;
        }
    }

    /**
     * Records release only after cleanup reaches a terminal state. This is the handoff fence: state
     * receipt, a dead heartbeat, or a successful Binder call alone cannot authorize a second renderer.
     */
    private void markReleasedIfTerminal() {
        if (lights.isSessionOpen() || !lights.isBlackClearTerminal()) return;
        markSettled();
        releasedStateRevision = appliedStateRevision;
    }

    /** Bounded lifecycle-only driver used before readiness and during process shutdown. */
    boolean prepareForReady() {
        return driveLifecycleClearToTerminal();
    }

    /** Bounded lifecycle-only driver used before readiness and during process shutdown. */
    private boolean driveLifecycleClearToTerminal() {
        long deadlineNs = System.nanoTime() + LIFECYCLE_CLEAR_TIMEOUT_MS * 1_000_000L;
        while (System.nanoTime() < deadlineNs) {
            lights.forceBlack();
            if (!lights.isSessionOpen() && lights.isBlackClearTerminal()) return true;
            if (lights.isUnreleasedFatal()) return false;

            // forceBlack normally closes its borrowed session. This extra close path handles a
            // pre-existing render session and bounded retries after an I/O failure. LightsBackend
            // owns one final automatic override after its normal close budget; if that fails it
            // enters an explicit terminal-but-unreleased state instead of looping indefinitely.
            if (lights.isSessionOpen()) {
                lights.closeSession();
            }
            if (!lights.isSessionOpen() && lights.isBlackClearTerminal()) return true;
            if (lights.isUnreleasedFatal()) return false;
            try {
                Thread.sleep(LIFECYCLE_CLEAR_POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return !lights.isSessionOpen() && lights.isBlackClearTerminal();
    }

    private static boolean isTransientAnimationDark(JSONObject cfg) {
        if (cfg == null) return false;
        String mode = cfg.optString("mode", cfg.optString("pattern", "off"));
        switch (mode) {
            case "breathe":
            case "blink":
            case "pulse":
            case "chase":
            case "comet":
            case "wave":
            case "rainbow":
            case "random":
                return true;
            case "custom":
                return cfg.optLong("rotateMs", 0) > 50;
            default:
                return false;
        }
    }

    private static String enumKey(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }

    private static int processPid() {
        try {
            return android.os.Process.myPid();
        } catch (RuntimeException ignored) {
            return -1;
        }
    }

    private static int processUid() {
        try {
            return android.os.Process.myUid();
        } catch (RuntimeException ignored) {
            return -1;
        }
    }

    private void readPrivacyRules(JSONArray array) {
        List<PrivacyScheduler.Rule> rules = new ArrayList<>();
        privacyConfigs.clear();
        if (array != null) {
            for (int i = 0; i < array.length(); i++) {
                JSONObject cfg = array.optJSONObject(i);
                if (cfg == null) continue;
                String id = cfg.optString("id", "");
                PrivacyScheduler.Activity activity = privacyActivity(cfg.optString("activity", ""));
                String pkg = cfg.optString("pkg", PrivacyScheduler.ANY_APP);
                if (id.isEmpty() || activity == null) continue;
                long lightMs = Math.max(1_000, Math.min(60_000, cfg.optLong("lightMs", 10_000)));
                long cooldownMs = Math.max(1_000, Math.min(60_000, cfg.optLong("cooldownMs", 10_000)));
                rules.add(new PrivacyScheduler.Rule(id, activity, pkg, lightMs, cooldownMs));
                privacyConfigs.put(id, cfg);
            }
        }
        privacyScheduler.setRules(rules);
    }

    private static PrivacyScheduler.Activity privacyActivity(String key) {
        if ("microphone".equals(key)) return PrivacyScheduler.Activity.MICROPHONE;
        if ("camera".equals(key)) return PrivacyScheduler.Activity.CAMERA;
        return null;
    }

    static int clampUserPriority(int priority) {
        return Math.max(USER_PRIORITY_MIN, Math.min(USER_PRIORITY_MAX, priority));
    }

    private static final int[] BLANK = {0};
}
