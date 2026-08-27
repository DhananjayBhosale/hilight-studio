package com.hilight.core;

/**
 * Pure, stateful safety limiter for the LED renderer.
 *
 * Keeping this separate from Android and binder work makes the timing limits directly testable.
 */
final class SafetyGuard {

    static final long FRAME_MS = 33;
    static final long DUTY_WINDOW_MS = 10 * 60_000;
    static final double MAX_DUTY = 0.5;
    static final long TAPER_AFTER_MS = 10_000;
    static final long TAPER_RAMP_MS = 10_000;
    static final double TAPER_FLOOR = 0.55;

    private final long dutyWindowMs;
    private final double maxDuty;
    private final long taperAfterMs;
    private final long taperRampMs;
    private final double taperFloor;

    private long windowStart = Long.MIN_VALUE;
    private long lastAppliedAt = Long.MIN_VALUE;
    private long litMsInWindow;
    private long continuousLitMs;
    private boolean lastOutputVisible;
    private boolean resting;

    SafetyGuard() {
        this(DUTY_WINDOW_MS, MAX_DUTY, TAPER_AFTER_MS, TAPER_RAMP_MS, TAPER_FLOOR);
    }

    SafetyGuard(
            long dutyWindowMs,
            double maxDuty,
            long taperAfterMs,
            long taperRampMs,
            double taperFloor
    ) {
        if (dutyWindowMs <= 0 || maxDuty <= 0 || maxDuty > 1
                || taperAfterMs < 0 || taperRampMs <= 0 || taperFloor < 0 || taperFloor > 1) {
            throw new IllegalArgumentException("Invalid safety limits");
        }
        this.dutyWindowMs = dutyWindowMs;
        this.maxDuty = maxDuty;
        this.taperAfterMs = taperAfterMs;
        this.taperRampMs = taperRampMs;
        this.taperFloor = taperFloor;
    }

    /** Applies limits using monotonic elapsed realtime supplied by the renderer. */
    int[] apply(int[] frame, long elapsedRealtime, double dim) {
        if (lastAppliedAt != Long.MIN_VALUE && elapsedRealtime < lastAppliedAt) {
            // elapsedRealtime() cannot move backwards in production. Clamping keeps a malformed
            // host input from subtracting already-accounted light time or extending a limit.
            elapsedRealtime = lastAppliedAt;
        }
        accountElapsedTime(elapsedRealtime);

        if (!FrameVisibility.isVisible(frame)) {
            noteOutput(false);
            return frame;
        }

        if (dim < 0.999) {
            int[] dimmed = new int[frame.length];
            for (int i = 0; i < frame.length; i++) dimmed[i] = Renderer.scale(frame[i], dim);
            frame = dimmed;
        }

        if (resting || litMsInWindow >= dutyWindowMs * maxDuty) {
            resting = true;
            noteOutput(false);
            return new int[]{0};
        }

        if (continuousLitMs <= taperAfterMs) {
            noteOutput(FrameVisibility.isVisible(frame));
            return frame;
        }

        double over = Math.min(1.0, (continuousLitMs - taperAfterMs) / (double) taperRampMs);
        double scale = 1.0 - (1.0 - taperFloor) * over;
        int[] out = new int[frame.length];
        for (int i = 0; i < frame.length; i++) out[i] = Renderer.scale(frame[i], scale);
        noteOutput(FrameVisibility.isVisible(out));
        return out;
    }

    /** Accounts how long the previous output remained latched between renderer calls. */
    private void accountElapsedTime(long elapsedRealtime) {
        if (windowStart == Long.MIN_VALUE) {
            windowStart = elapsedRealtime;
        }
        if (lastAppliedAt == Long.MIN_VALUE) {
            lastAppliedAt = elapsedRealtime;
            return;
        }

        long delta = elapsedRealtime - lastAppliedAt;
        if (lastOutputVisible) {
            continuousLitMs = saturatingAdd(continuousLitMs, delta);
        }

        long sinceWindowStart = elapsedRealtime - windowStart;
        if (sinceWindowStart >= dutyWindowMs) {
            long completedWindows = sinceWindowStart / dutyWindowMs;
            long newWindowStart = windowStart + completedWindows * dutyWindowMs;
            boolean observedPriorWindowOverrun = false;
            if (lastOutputVisible) {
                long firstWindowEnd = windowStart + dutyWindowMs;
                long priorWindowTail = Math.max(
                        0,
                        Math.min(elapsedRealtime, firstWindowEnd) - lastAppliedAt
                );
                observedPriorWindowOverrun = completedWindows > 1
                        || saturatingAdd(litMsInWindow, priorWindowTail)
                        > dutyWindowMs * maxDuty;
            }
            litMsInWindow = lastOutputVisible
                    ? elapsedRealtime - Math.max(lastAppliedAt, newWindowStart)
                    : 0;
            windowStart = newWindowStart;
            // If a stalled renderer let a visible frame overrun an earlier window, fail dark for
            // this window rather than treating the already-violating gap as a fresh budget.
            resting = observedPriorWindowOverrun;
        } else if (lastOutputVisible) {
            litMsInWindow = saturatingAdd(litMsInWindow, delta);
        }
        lastAppliedAt = elapsedRealtime;
    }

    private void noteOutput(boolean visible) {
        lastOutputVisible = visible;
        if (!visible) {
            continuousLitMs = 0;
        }
    }

    private static long saturatingAdd(long value, long delta) {
        return value > Long.MAX_VALUE - delta ? Long.MAX_VALUE : value + delta;
    }

    boolean isResting() {
        return resting;
    }

    int dutyPercent() {
        return (int) (100.0 * litMsInWindow / (dutyWindowMs * maxDuty));
    }
}
