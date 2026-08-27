package com.hilight.core;

/** Production-safe, framework-distinct stimuli whose RGB channels remain physically black. */
final class ForcedBlackClear {

    static final String STRATEGY_ID = "alpha_black_then_zero";
    static final int STRATEGY_VERSION = 1;

    /**
     * Android documents that a LightState's alpha channel is ignored. Changing only alpha therefore
     * defeats LightsService's full-integer state dedup without asking the LEDs to emit any colour.
     */
    static final int RETRY_COLOR = 0x01000000;
    static final int CANONICAL_COLOR = 0x00000000;

    enum Stimulus {
        /** Production default. It never asks any RGB channel to emit light. */
        ALPHA_BLACK_THEN_ZERO(
                STRATEGY_ID,
                STRATEGY_VERSION,
                new int[]{RETRY_COLOR, CANONICAL_COLOR}
        ),
        /** Diagnostic-only variant: session close supplies the canonical-zero transition. */
        ALPHA_BLACK_THEN_CLOSE(
                "alpha_black_then_close",
                1,
                new int[]{RETRY_COLOR}
        );

        final String id;
        final int version;
        private final int[] colors;

        Stimulus(String id, int version, int[] colors) {
            this.id = id;
            this.version = version;
            this.colors = colors;
        }

        int[] colors() { return colors.clone(); }
    }

    private ForcedBlackClear() {}

    @FunctionalInterface
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    @FunctionalInterface
    interface Writer {
        /** Returns true only when the framework accepted the write. */
        boolean write(int color);
    }

    @FunctionalInterface
    interface SettledObserver {
        /** Called after an accepted frame remained exposed for the settle period. */
        void afterSettle(int color);
    }

    /** Runs the production-safe stimulus. */
    static boolean apply(long minUpdatePeriodMs, Writer write, Sleeper sleeper) {
        return apply(
                Stimulus.ALPHA_BLACK_THEN_ZERO,
                minUpdatePeriodMs,
                write,
                sleeper,
                ignored -> {}
        );
    }

    /**
     * Returns true only when every frame was accepted and remained exposed for one advertised
     * update period. The final wait is deliberate: closing immediately after canonical black can
     * let a vendor driver coalesce the last write with session teardown.
     */
    static boolean apply(
            Stimulus stimulus,
            long minUpdatePeriodMs,
            Writer write,
            Sleeper sleeper
    ) {
        return apply(stimulus, minUpdatePeriodMs, write, sleeper, ignored -> {});
    }

    static boolean apply(
            Stimulus stimulus,
            long minUpdatePeriodMs,
            Writer write,
            Sleeper sleeper,
            SettledObserver observer
    ) {
        long settleMs = Math.max(1, minUpdatePeriodMs);
        for (int color : stimulus.colors) {
            if (!write.write(color)) return false;
            try {
                sleeper.sleep(settleMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            observer.afterSettle(color);
        }
        return true;
    }
}
