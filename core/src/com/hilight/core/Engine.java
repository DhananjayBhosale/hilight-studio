package com.hilight.core;

import org.json.JSONObject;

/**
 * The render loop, shared by both privileged hosts.
 *
 * State is one JSON document: {enabled, priority, ambient:{...}, alert:{id, durationMs, ...}}.
 * The alert layer wins while it lasts; a durationMs of 0 holds until the alert is replaced or the
 * "alert" key disappears. Everything else falls through to ambient.
 *
 * Ticks at the hardware's minimum update period (66 ms, ~15 fps).
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

    /** Device-validated waits for the preemptive black effect and its static-black drain. */
    static final long BLACK_EFFECT_WAIT_MS = 2 * FRAME_MS;
    static final long BLACK_STATIC_DRAIN_MS = FRAME_MS;
    /** Hard ceiling for a single alert, whatever the app asks for. */
    public static final long ALERT_MAX_MS = 60_000;
    /** Matches the app's five-minute ambient timeout maximum. */
    public static final long MAX_AMBIENT_TIMEOUT_MS = 300_000;
    public static final long DEFAULT_AMBIENT_TIMEOUT_MS = 30_000;

    /** Duty-cycle guard: at most half of any ten-minute window may be lit. */
    public static final long DUTY_WINDOW_MS = SafetyGuard.DUTY_WINDOW_MS;
    public static final double MAX_DUTY = SafetyGuard.MAX_DUTY;
    /** Sustained-current guard: taper brightness after this much unbroken light. */
    public static final long TAPER_AFTER_MS = SafetyGuard.TAPER_AFTER_MS;
    public static final long TAPER_RAMP_MS = SafetyGuard.TAPER_RAMP_MS;
    public static final double TAPER_FLOOR = SafetyGuard.TAPER_FLOOR;

    private final LightsBackend lights = new LightsBackend();
    private final Renderer renderer = new Renderer();
    private final SafetyGuard safety = new SafetyGuard();
    private final OutputGate gate = new OutputGate();
    private final Object lock = new Object();
    private long seedCounter;

    private Thread thread;
    private volatile boolean running;

    private JSONObject state = new JSONObject();
    private JSONObject alert;
    private long alertId = -1;
    private boolean lastFrameWasAlert;
    private long ambientGeneration;

    private double dim = 1.0;
    private long ambientTimeoutMs = DEFAULT_AMBIENT_TIMEOUT_MS;

    private OutputGate.Layer lastLayer;
    private Candidate candidate;
    private Submission submission;
    private boolean lastPushedBlack;
    private boolean forceStaticPush;

    private static final class Candidate {
        final OutputGate.Layer layer;
        final long alertId;
        final long ambientGeneration;
        final String config;
        final long quantumMs;
        final int ledCount;
        final SequenceCompiler.Plan eligibility;
        private final long randomSeed;

        Candidate(OutputGate.Layer layer, long alertId, long ambientGeneration,
                  String config, long quantumMs, int ledCount, long randomSeed,
                  SequenceCompiler.Plan eligibility) {
            this.layer = layer;
            this.alertId = alertId;
            this.ambientGeneration = ambientGeneration;
            this.config = config;
            this.quantumMs = quantumMs;
            this.ledCount = ledCount;
            this.randomSeed = randomSeed;
            this.eligibility = eligibility;
        }
    }

    private static final class Submission {
        final Candidate candidate;
        final double bucket;
        final int priority;
        final long coverageEnd;
        final long periodMs;

        Submission(Candidate candidate, double bucket, int priority,
                   long coverageEnd, long periodMs) {
            this.candidate = candidate;
            this.bucket = bucket;
            this.priority = priority;
            this.coverageEnd = coverageEnd;
            this.periodMs = periodMs;
        }
    }

    public void start() throws Exception {
        lights.connect();
        Log.i("connected: " + lights.ledCount() + " HiLight LEDs");
        running = true;
        thread = new Thread(this::loop, "hilight-render");
        thread.setDaemon(false);
        thread.start();
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
            running = false;
            invalidateEffects();
            terminateActiveEffectToBlack();
            lights.closeSession();
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
            JSONObject oldAmbient = state.optJSONObject("ambient");
            String oldAmbientConfig = oldAmbient == null ? null : oldAmbient.toString();
            state = o;
            ambientTimeoutMs = clampAmbientTimeout(
                    o.optLong("ambientTimeoutMs", DEFAULT_AMBIENT_TIMEOUT_MS));
            dim = Math.max(0.02, Math.min(1.0, o.optDouble("dim", 1.0)));
            JSONObject newAmbient = o.optJSONObject("ambient");
            String newAmbientConfig = newAmbient == null ? null : newAmbient.toString();
            if (!same(oldAmbientConfig, newAmbientConfig)) ambientGeneration++;

            // Only a deliberate user action ("arm") may start a fresh window. Automatic pushes — an
            // alert firing, a foreground override, the app being backgrounded — must not, or the array
            // could be kept lit indefinitely in 30-second increments.
            //
            // Defaulting to false matters: a document that omits the key must not arm. The app always
            // sends it, but the bootstrap file the app drops for a not-yet-running helper is just
            // {"enabled":false}, and defaulting to true let that open a window nobody asked for.
            if (o.optBoolean("arm", false)) gate.armAmbient(System.currentTimeMillis(), ambientTimeoutMs);

            JSONObject a = o.optJSONObject("alert");
            if (a == null) {
                boolean hadAlert = alert != null || gate.isAlertHeld();
                if (alert != null) Log.i("alert cleared");
                alert = null;
                alertId = -1;
                // clears the blank latch too, so a cancelled alert cannot leave the array lit
                gate.clearAlert();
                renderer.reset();
                if (hadAlert) invalidateEffects();
            } else {
                long id = a.optLong("id", -1);
                if (id != alertId || !same(a.toString(), alert == null ? null : alert.toString())) {
                    alertId = id;
                    alert = a;
                    long asked = a.optLong("durationMs", 4000);
                    // an open-ended alert (a "while this app is open" hold) still gets the global cap
                    long dur = asked <= 0 ? ambientTimeoutMs : Math.min(asked, ALERT_MAX_MS);
                    gate.startAlert(System.currentTimeMillis(), dur);
                    renderer.reset();
                    invalidateEffects();
                    Log.i("alert " + id + " " + a.optString("pattern", "pulse") + " for " + dur + "ms"
                            + (dur != asked ? " (asked " + asked + ", capped)" : ""));
                }
            }
        }
    }

    public String status() {
        JSONObject o = new JSONObject();
        try {
            synchronized (lock) {
                o.put("pid", android.os.Process.myPid());
                o.put("uid", android.os.Process.myUid());
                o.put("ts", System.currentTimeMillis());
                o.put("ledCount", lights.ledCount());
                o.put("session", lights.isSessionOpen());
                o.put("priority", lights.sessionPriority());
                JSONObject amb = state.optJSONObject("ambient");
                o.put("mode", amb == null ? "off" : amb.optString("mode", "off"));
                o.put("alertId", alertId);
                o.put("timeoutMs", ambientTimeoutMs);
                o.put("dim", dim);
                o.put("ambientRemainingMs", gate.ambientRemainingMs(System.currentTimeMillis()));
                o.put("ambientHeld", gate.isAmbientHeld());
                o.put("resting", safety.isResting());
                o.put("dutyPct", safety.dutyPercent());
                o.put("hardwareEffect", lights.isEffectActive());
                o.put("hardwareEffectSupport", lights.supportsEffects());
                o.put("version", 1);
            }
        } catch (Exception ignored) {
            // a status document is never worth crashing over
        }
        return o.toString();
    }

    private void loop() {
        boolean interrupted = false;
        try {
            while (running) {
                try {
                    tick();
                    Thread.sleep(FRAME_MS);
                } catch (InterruptedException e) {
                    interrupted = true;
                    break;
                } catch (Throwable t) {
                    Log.w("frame failed: " + t);
                    try {
                        Thread.sleep(250);
                    } catch (InterruptedException e) {
                        interrupted = true;
                        break;
                    }
                }
            }
        } finally {
            synchronized (lock) {
                running = false;
                invalidateEffects();
                // Last defense against closing/leaving a colored effect on a thread exit.
                terminateActiveEffectToBlack();
                lights.closeSession();
            }
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private void tick() {
        synchronized (lock) {
            if (!running) return;                   // stop() may have closed the session already
            boolean enabled = state.optBoolean("enabled", false);
            int priority = state.optInt("priority", 0);
            long now = now();

            if (!enabled) {
                invalidateEffects();
                release("released HiLight to the system");
                noteDark(now);
                return;
            }

            OutputGate.Layer layer = gate.next(now);
            if (layer != lastLayer) {
                invalidateEffects();
                lastLayer = layer;
            }
            if (lastFrameWasAlert && layer != OutputGate.Layer.ALERT) {
                alert = null;
                renderer.reset();
                // deliberately no re-arm here: an alert must not extend the ambient window
                invalidateEffects();
            }
            lastFrameWasAlert = layer == OutputGate.Layer.ALERT;

            // OutputGate keeps the endpoint frame at equality for compatibility; do not let that
            // zero-remaining frame become a colored software tail. Treat the exact deadline as the
            // dark transition and perform the same guarded release transaction.
            if (layer == OutputGate.Layer.AMBIENT && gate.ambientRemainingMs(now) <= 0) {
                invalidateEffects();
                noteDark(now);
                release("ambient window expired — released HiLight to the system");
                return;
            }

            JSONObject cfg;
            long t;
            switch (layer) {
                case ALERT:
                    cfg = alert;
                    t = gate.alertElapsed(now);
                    break;
                case AMBIENT:
                    cfg = state.optJSONObject("ambient");
                    t = now;
                    break;
                case BLANK:
                    invalidateEffects();
                    if (lights.isSessionOpen()) terminateActiveEffectToBlack();
                    return;
                default:                                    // IDLE: dark, now hand the array back
                    invalidateEffects();
                    noteDark(now);
                    release("nothing left to show — released HiLight to the system");
                    return;
            }

            // The session is taken only while there is something to show, and reopened on demand: a
            // rule firing lands here and gets it back before the first frame.
            if (!lights.isSessionOpen() || priority != lights.sessionPriority()) {
                invalidateEffects();
                if (lights.isSessionOpen()) {
                    terminateActiveEffectToBlack();
                    lights.closeSession();
                }
                lights.openSession(priority);
                lastPushedBlack = false;
            }

            int ledCount = Math.max(1, lights.ledCount());
            long quantum = lights.effectQuantumMs();
            if (quantum > 0 && cfg != null) {
                Candidate current = candidateFor(layer, cfg, quantum, ledCount);
                if (current.eligibility != null && current.eligibility.mayLight()) {
                    SafetyGuard.Decision decision = safety.observe(true, now, dim);
                    double bucket = scaleBucket(decision.scale);
                    if (!decision.resting && bucket > 0.0
                            && submitHardware(current, cfg, t, bucket, priority,
                            gateRemaining(layer, now), now)) {
                        return;
                    }
                    // A resting/zero plan, or a scaled plan that could not be built, must cancel
                    // any finite effect with this frame. The decision was already observed above.
                    renderSoftware(cfg, t, now, decision);
                    return;
                }
            }

            // Static, unsupported and compiler-capped patterns retain the ordinary path.
            renderSoftware(cfg, t, now, null);
        }
    }

    private Candidate candidateFor(OutputGate.Layer layer, JSONObject cfg,
                                   long quantum, int ledCount) {
        String config = cfg.toString();
        long id = layer == OutputGate.Layer.ALERT ? alertId : -1;
        if (candidate != null && candidate.layer == layer && candidate.alertId == id
                && candidate.ambientGeneration == ambientGeneration
                && candidate.quantumMs == quantum && candidate.ledCount == ledCount
                && same(candidate.config, config)) {
            return candidate;
        }
        long randomSeed = nextRandomSeed();
        SequenceCompiler.Plan eligibility = SequenceCompiler.compile(
                cfg, 0, ledCount, quantum, 1.0, randomSeed);
        candidate = new Candidate(layer, id, ambientGeneration, config, quantum, ledCount,
                randomSeed, eligibility);
        submission = null;
        return candidate;
    }

    private boolean submitHardware(Candidate current, JSONObject cfg, long phase,
                                   double bucket, int priority, long remainingMs, long now) {
        long period = current.eligibility.periodMs;
        long requiredCoverageEnd = requiredCoverageEnd(now, remainingMs);
        if (submission != null) {
            boolean candidateMatches = submission.candidate == current;
            boolean bucketMatches = submission.bucket == bucket;
            boolean priorityMatches = submission.priority == priority;
            boolean periodMatches = submission.periodMs == period;
            boolean localEffectActive = lights.isEffectActive();
            if (shouldReuseHardwareSubmission(candidateMatches, bucketMatches,
                    priorityMatches, periodMatches, localEffectActive, submission.coverageEnd,
                    requiredCoverageEnd, remainingMs, period)) {
                return true;
            }
        }

        int iterations = effectIterations(remainingMs, period);
        if (iterations <= 0) return false;
        long coverageEnd = coverageEnd(now, iterations, period);
        SequenceCompiler.Plan plan = SequenceCompiler.compile(cfg, phase,
                current.ledCount, current.quantumMs, bucket, current.randomSeed);
        if (plan == null || !plan.mayLight()) return false;
        if (!lights.startEffect(plan, iterations)) {
            submission = null;
            forceStaticPush = true;
            return false;
        }
        submission = new Submission(current, bucket, priority, coverageEnd, period);
        return true;
    }

    private long gateRemaining(OutputGate.Layer layer, long now) {
        return layer == OutputGate.Layer.ALERT
                ? gate.alertRemainingMs(now) : gate.ambientRemainingMs(now);
    }

    private void renderSoftware(JSONObject cfg, long t, long now, SafetyGuard.Decision decision) {
        submission = null;
        int[] frame = renderer.frame(cfg, t, Math.max(1, lights.ledCount()));
        int[] protectedFrame = decision == null
                ? protect(frame, now) : safety.apply(frame, decision);
        if (lights.isEffectActive() && isDark(protectedFrame)) {
            terminateActiveEffectToBlack();
        } else {
            pushStatic(protectedFrame, forceStaticPush || lights.isEffectActive());
        }
        forceStaticPush = false;
    }

    /**
     * Applies the hardware protections to a frame: rests the array when it has been lit for too much
     * of the current window, and tapers brightness under sustained light.
     */
    private int[] protect(int[] frame, long now) {
        return safety.apply(frame, now, dim);
    }

    /**
     * Tells the guard the array is dark on a frame that is not pushed.
     *
     * The sustained-light taper only unwinds when the guard is handed a dark frame, and the frames
     * skipped while the array is already blank never reached it. Without this, a long ambient run
     * left the taper pinned, so the next look came back at the taper floor no matter how long the
     * array had actually been resting.
     */
    private void noteDark(long now) {
        safety.apply(BLANK, now, dim);
    }

    /** Hands the array back to Android, if we are holding it. */
    private void release(String why) {
        invalidateEffects();
        if (!lights.isSessionOpen()) return;
        terminateActiveEffectToBlack();
        lights.closeSession();
        lastPushedBlack = false;
        Log.i(why);
    }

    /**
     * Cancels a colored hardware effect with the validated preemptive black effect, then drains a
     * static-black frame before the session can be released. This is intentionally one best-effort
     * transaction: a failed black submission falls back to static black and is never retried here.
     */
    private void terminateActiveEffectToBlack() {
        if (!lights.isSessionOpen()) return;

        // A successful prior transaction already ended with a static black push. Do not submit or
        // drain again when the following IDLE/release transition closes that same session.
        if (!lights.isEffectActive()) {
            if (lastPushedBlack) return;
            pushStatic(BLANK, true);
            boolean interrupted = sleepPreservingInterrupt(BLACK_STATIC_DRAIN_MS);
            if (interrupted) Thread.interrupted();
            if (interrupted) Thread.currentThread().interrupt();
            return;
        }

        boolean interrupted = false;
        SequenceCompiler.Plan terminator = SequenceCompiler.blackTerminator(
                lights.ledCount(), FRAME_MS);
        if (terminator != null && lights.startEffect(terminator, 1)) {
            interrupted |= sleepPreservingInterrupt(BLACK_EFFECT_WAIT_MS);
            if (interrupted) Thread.interrupted();
        }
        // This is also the fallback when the preemptive black submission is rejected or fails.
        pushStatic(BLANK, true);
        boolean staticDrainInterrupted = sleepPreservingInterrupt(BLACK_STATIC_DRAIN_MS);
        interrupted |= staticDrainInterrupted;
        if (staticDrainInterrupted) Thread.interrupted();
        if (interrupted) Thread.currentThread().interrupt();
    }

    private void invalidateEffects() {
        candidate = null;
        submission = null;
    }

    private void pushStatic(int[] frame, boolean force) {
        if (!lights.isSessionOpen()) return;
        boolean black = isDark(frame);
        if (black && !force && lastPushedBlack) return;
        lights.push(frame);
        lastPushedBlack = black;
    }

    private static boolean isDark(int[] frame) {
        for (int color : frame) {
            if (((color >> 16 & 0xFF) + (color >> 8 & 0xFF) + (color & 0xFF)) > 12) return false;
        }
        return true;
    }

    /** Returns whether a matching active effect covers the exact finite gate deadline. */
    static boolean isHardwareSubmissionLive(boolean candidateMatches, boolean bucketMatches,
                                             boolean priorityMatches, boolean periodMatches,
                                             boolean localEffectActive, long coverageEnd,
                                             long requiredCoverageEnd) {
        return candidateMatches && bucketMatches && priorityMatches && periodMatches
                && localEffectActive && coverageEnd >= requiredCoverageEnd;
    }

    /**
     * Returns whether the cached colored effect may remain in place for this frame.
     *
     * A taper bucket change normally requires a replacement. Reuse the current effect when a full
     * replacement cycle cannot fit before the gate deadline; otherwise the changed scale is allowed
     * to replace it normally. Matching buckets retain the ordinary reuse policy at any remaining
     * time.
     *
     * The period is passed separately from periodMatches because the cycle-fit rule depends on the
     * actual positive period, not merely on whether the cached period identity matches.
     */
    static boolean shouldReuseHardwareSubmission(boolean candidateMatches, boolean bucketMatches,
                                                  boolean priorityMatches, boolean periodMatches,
                                                  boolean localEffectActive, long coverageEnd,
                                                  long requiredCoverageEnd, long remainingMs,
                                                  long periodMs) {
        if (!candidateMatches || !priorityMatches || !periodMatches || !localEffectActive
                || coverageEnd < requiredCoverageEnd) {
            return false;
        }
        if (bucketMatches) return true;
        if (periodMs <= 0) return false;
        long nonNegativeRemainingMs = Math.max(0, remainingMs);
        return nonNegativeRemainingMs < periodMs;
    }

    static long requiredCoverageEnd(long now, long remainingMs) {
        return saturatingAdd(now, Math.max(0, remainingMs));
    }

    /** Bounds hostile or malformed state before it reaches the output gate. */
    static long clampAmbientTimeout(long requestedTimeoutMs) {
        if (requestedTimeoutMs < 1_000) return 1_000;
        return Math.min(requestedTimeoutMs, MAX_AMBIENT_TIMEOUT_MS);
    }

    /** Number of periods needed to cover the finite gate deadline, rounded upward. */
    static int effectIterations(long remainingMs, long periodMs) {
        if (remainingMs <= 0 || periodMs <= 0) return 0;
        long quotient = remainingMs / periodMs;
        long count = remainingMs % periodMs == 0 ? quotient : quotient + 1;
        return count >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) count;
    }

    /** Saturated end time for the finite effect actually submitted. */
    static long coverageEnd(long now, int iterations, long period) {
        if (iterations <= 0 || period <= 0) return now;
        long span = iterations > Long.MAX_VALUE / period ? Long.MAX_VALUE : iterations * period;
        return saturatingAdd(now, span);
    }

    private static long saturatingAdd(long first, long second) {
        if (second > 0 && first > Long.MAX_VALUE - second) return Long.MAX_VALUE;
        if (second < 0 && first < Long.MIN_VALUE - second) return Long.MIN_VALUE;
        return first + second;
    }

    /** Sleeps for the requested duration even when interrupted, preserving the status. */
    static boolean sleepPreservingInterrupt(long durationMs) {
        if (durationMs <= 0) return false;
        long deadline = System.nanoTime() + durationMs * 1_000_000L;
        boolean interrupted = false;
        for (;;) {
            long remainingNanos = deadline - System.nanoTime();
            if (remainingNanos <= 0) {
                if (interrupted) Thread.currentThread().interrupt();
                return interrupted;
            }
            long millis = remainingNanos / 1_000_000L;
            int nanos = (int) (remainingNanos % 1_000_000L);
            try {
                Thread.sleep(millis, nanos);
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
    }

    static double scaleBucket(double scale) {
        if (!Double.isFinite(scale) || scale <= 0) return 0;
        double bucket = Math.floor((scale + 1e-9) * 20.0) / 20.0;
        return bucket < 0.0 ? 0.0 : bucket > 1.0 ? 1.0 : bucket;
    }

    private static boolean same(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    private long nextRandomSeed() {
        long counter = ++seedCounter;
        return mixSeed(counter, System.nanoTime(), System.identityHashCode(this));
    }

    /** Deterministic mixer for the per-candidate random activation seed. */
    static long mixSeed(long counter, long timeNanos, int identityHash) {
        long value = counter * 0x9E3779B97F4A7C15L
                ^ timeNanos ^ ((long) identityHash * 0xBF58476D1CE4E5B9L);
        value = (value ^ (value >>> 30)) * 0xBF58476D1CE4E5B9L;
        value = (value ^ (value >>> 27)) * 0x94D049BB133111EBL;
        return value ^ (value >>> 31);
    }

    private static long now() {
        return System.currentTimeMillis();
    }

    private static final int[] BLANK = {0};
}
