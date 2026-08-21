package com.hilight.core;

/**
 * Pure, stateful safety limiter for the LED renderer.
 *
 * Keeping this separate from Android and binder work makes the timing limits directly testable.
 */
final class SafetyGuard {

    static final long FRAME_MS = 66;
    static final long DUTY_WINDOW_MS = 10 * 60_000;
    static final double MAX_DUTY = 0.5;
    static final long TAPER_AFTER_MS = 10_000;
    static final long TAPER_RAMP_MS = 10_000;
    static final double TAPER_FLOOR = 0.55;

    private final long frameMs;
    private final long dutyWindowMs;
    private final double maxDuty;
    private final long taperAfterMs;
    private final long taperRampMs;
    private final double taperFloor;

    private long windowStart = Long.MIN_VALUE;
    private long litMsInWindow;
    private long continuousLitMs;
    private boolean resting;

    /** The safety decision for one frame/effect interval. */
    static final class Decision {
        final double scale;
        final boolean resting;
        private final double quietScale;
        private final double taper;

        private Decision(double scale, boolean resting, double quietScale, double taper) {
            this.scale = scale;
            this.resting = resting;
            this.quietScale = quietScale;
            this.taper = taper;
        }
    }

    SafetyGuard() {
        this(FRAME_MS, DUTY_WINDOW_MS, MAX_DUTY, TAPER_AFTER_MS, TAPER_RAMP_MS, TAPER_FLOOR);
    }

    SafetyGuard(
            long frameMs,
            long dutyWindowMs,
            double maxDuty,
            long taperAfterMs,
            long taperRampMs,
            double taperFloor
    ) {
        if (frameMs <= 0 || dutyWindowMs <= 0 || maxDuty <= 0 || maxDuty > 1
                || taperAfterMs < 0 || taperRampMs <= 0 || taperFloor < 0 || taperFloor > 1) {
            throw new IllegalArgumentException("Invalid safety limits");
        }
        this.frameMs = frameMs;
        this.dutyWindowMs = dutyWindowMs;
        this.maxDuty = maxDuty;
        this.taperAfterMs = taperAfterMs;
        this.taperRampMs = taperRampMs;
        this.taperFloor = taperFloor;
    }

    int[] apply(int[] frame, long now, double dim) {
        return apply(frame, observe(isLit(frame), now, dim));
    }

    /** Applies an already-observed decision without charging duty or taper a second time. */
    int[] apply(int[] frame, Decision decision) {
        if (!isLit(frame)) return frame;
        if (decision.resting) return new int[]{0};
        if (decision.scale >= 0.999) return frame;

        int[] out = frame;
        // Keep the historical two-stage rounding: quiet-hours dim first, then taper.
        if (decision.quietScale < 0.999) {
            out = new int[frame.length];
            for (int i = 0; i < frame.length; i++) out[i] = Renderer.scale(frame[i], decision.quietScale);
        }
        if (decision.taper < 1.0) {
            int[] tapered = new int[frame.length];
            for (int i = 0; i < frame.length; i++) tapered[i] = Renderer.scale(out[i], decision.taper);
            out = tapered;
        }
        return out;
    }

    Decision observe(boolean lit, long now, double dim) {
        if (windowStart == Long.MIN_VALUE || now - windowStart >= dutyWindowMs) {
            windowStart = now;
            litMsInWindow = 0;
            resting = false;
        }

        if (!lit) {
            continuousLitMs = 0;
            return new Decision(1.0, resting, 1.0, 1.0);
        }

        double quietScale = applyDim(dim);
        if (resting) return new Decision(0.0, true, quietScale, 1.0);

        litMsInWindow += frameMs;
        continuousLitMs += frameMs;
        if (litMsInWindow > dutyWindowMs * maxDuty) {
            resting = true;
            return new Decision(0.0, true, quietScale, 1.0);
        }

        double taper = 1.0;
        if (continuousLitMs > taperAfterMs) {
            double over = Math.min(1.0, (continuousLitMs - taperAfterMs) / (double) taperRampMs);
            taper = 1.0 - (1.0 - taperFloor) * over;
        }
        return new Decision(clamp01(quietScale * taper), false, quietScale, taper);
    }

    boolean isResting() { return resting; }

    int dutyPercent() {
        return (int) (100.0 * litMsInWindow / (dutyWindowMs * maxDuty));
    }

    private static double clampDim(double dim) {
        // NaN was treated as no dim by apply's old threshold check; retain that behavior.
        return Double.isNaN(dim) ? 1.0 : Renderer.clamp01(dim);
    }

    private static double applyDim(double dim) {
        // Preserve apply's no-allocation threshold: values at or above .999 were not dimmed.
        return dim < 0.999 ? clampDim(dim) : 1.0;
    }

    private static double clamp01(double value) {
        return value < 0 ? 0 : value > 1 ? 1 : value;
    }

    private static boolean isLit(int[] frame) {
        for (int c : frame) {
            if (((c >> 16 & 0xFF) + (c >> 8 & 0xFF) + (c & 0xFF)) > 12) return true;
        }
        return false;
    }
}
