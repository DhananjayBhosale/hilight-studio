package com.hilight.core;

final class SafetyGuard {
    static final long FRAME_MS = 33;
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
        if (windowStart == Long.MIN_VALUE || now - windowStart >= dutyWindowMs) {
            windowStart = now;
            litMsInWindow = 0;
            resting = false;
        }

        if (!isLit(frame)) {
            continuousLitMs = 0;
            return frame;
        }

        if (dim < 0.999) {
            int[] dimmed = new int[frame.length];
            for (int i = 0; i < frame.length; i++) dimmed[i] = Renderer.scale(frame[i], dim);
            frame = dimmed;
        }

        if (resting) return new int[]{0};

        litMsInWindow += frameMs;
        continuousLitMs += frameMs;

        if (litMsInWindow > dutyWindowMs * maxDuty) {
            resting = true;
            return new int[]{0};
        }

        if (continuousLitMs <= taperAfterMs) return frame;

        double over = Math.min(1.0, (continuousLitMs - taperAfterMs) / (double) taperRampMs);
        double scale = 1.0 - (1.0 - taperFloor) * over;
        int[] out = new int[frame.length];
        for (int i = 0; i < frame.length; i++) out[i] = Renderer.scale(frame[i], scale);
        return out;
    }

    boolean isResting() {
        return resting;
    }

    int dutyPercent() {
        return (int) (100.0 * litMsInWindow / (dutyWindowMs * maxDuty));
    }

    private static boolean isLit(int[] frame) {
        for (int c : frame) {
            if (((c >> 16 & 0xFF) + (c >> 8 & 0xFF) + (c & 0xFF)) > 12) return true;
        }
        return false;
    }
}
