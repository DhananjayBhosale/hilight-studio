package com.hilight.core;

/**
 * Decides which layer drives the next frame, and tracks whether the array has already been blanked
 * for the current dark period.
 *
 * Pure and Android-free for the same reason as {@link SafetyGuard}: the rules that stop the array
 * being left lit are the part worth testing directly, and they are easy to get subtly wrong.
 *
 * The blank latch exists only to avoid pushing a blank frame every 33 ms once the array is already
 * dark — {@link LightsBackend#push} is a binder call with no dedup of its own. The latch therefore
 * has to describe the hardware, not the reason it went dark, which is why {@link #clearAlert()}
 * resets it: an alert leaves the LEDs lit, so one more blank frame is owed after it ends.
 */
final class OutputGate {

    enum Layer {
        /** the alert layer is holding the array */
        ALERT,
        /** the always-on look is in its window */
        AMBIENT,
        /** nothing may be lit and the array is not dark yet — push one blank frame */
        BLANK,
        /** nothing may be lit and the array is already dark — push nothing */
        IDLE,
    }

    private long alertStart;
    private long alertEnd;
    private boolean alertHeld;

    private long ambientDeadline;
    /** true once a blank frame has been pushed for the current dark period */
    private boolean blanked;

    /** Takes the array for {@code durationMs}, starting at this monotonic elapsed time. */
    void startAlert(long elapsedRealtime, long durationMs) {
        alertHeld = true;
        alertStart = elapsedRealtime;
        alertEnd = deadlineAfter(elapsedRealtime, durationMs);
    }

    /**
     * Drops the alert layer.
     *
     * The alert had the LEDs lit, so the blank latch no longer describes the hardware. Clearing it
     * guarantees one more blank frame if the ambient window has already expired — without this, an
     * alert arriving after the auto-off deadline leaves its last frame on the array indefinitely,
     * and the duty guard never sees it because no further frames are pushed.
     */
    void clearAlert() {
        if (!alertHeld) return;
        alertHeld = false;
        blanked = false;
    }

    /** Starts a fresh auto-off window. Only a deliberate user action should call this. */
    void armAmbient(long elapsedRealtime, long timeoutMs) {
        ambientDeadline = deadlineAfter(elapsedRealtime, timeoutMs);
        blanked = false;
    }

    /**
     * Records output owned outside the alert/ambient layers, currently a privacy activity rule.
     *
     * Privacy output may begin after this gate has already latched the array as blank. Clearing the
     * latch makes the first inactive frame return BLANK instead of IDLE, so the external output is
     * sent through the bounded framework cleanup path and its session released when the microphone
     * or camera stops. Physical darkness still requires observation on affected hardware.
     */
    void noteExternalOutput() {
        blanked = false;
    }

    boolean isAlertHeld() {
        return alertHeld;
    }

    long alertElapsed(long elapsedRealtime) {
        return Math.max(0, elapsedRealtime - alertStart);
    }

    long ambientRemainingMs(long elapsedRealtime) {
        return Math.max(0, ambientDeadline - elapsedRealtime);
    }

    /** True while the auto-off window has expired and the array is being held dark. */
    boolean isAmbientHeld() {
        return blanked;
    }

    /** The layer for this frame. Expires the alert and takes the blank latch as a side effect. */
    Layer next(long elapsedRealtime) {
        if (alertHeld) {
            if (elapsedRealtime < alertEnd) return Layer.ALERT;
            clearAlert();
        }
        if (elapsedRealtime >= ambientDeadline) {
            if (blanked) return Layer.IDLE;
            blanked = true;
            return Layer.BLANK;
        }
        return Layer.AMBIENT;
    }

    private static long deadlineAfter(long elapsedRealtime, long durationMs) {
        if (durationMs <= 0) return elapsedRealtime;
        if (elapsedRealtime > Long.MAX_VALUE - durationMs) return Long.MAX_VALUE;
        return elapsedRealtime + durationMs;
    }
}
