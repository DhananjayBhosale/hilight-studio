package com.hilight.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

public final class ForcedBlackClearTest {

    @Test
    public void retryAndCanonicalFramesAreBothRgbBlack() {
        assertEquals(0, ForcedBlackClear.RETRY_COLOR & 0x00FFFFFF);
        assertEquals(0, ForcedBlackClear.CANONICAL_COLOR & 0x00FFFFFF);
        assertFalse(FrameVisibility.isVisible(new int[]{ForcedBlackClear.RETRY_COLOR}));
        assertFalse(FrameVisibility.isVisible(new int[]{ForcedBlackClear.CANONICAL_COLOR}));
    }

    @Test
    public void retryChangesFrameworkStateBeforeReturningToCanonicalBlack() {
        assertNotEquals(ForcedBlackClear.RETRY_COLOR, ForcedBlackClear.CANONICAL_COLOR);
        assertEquals(0, ForcedBlackClear.CANONICAL_COLOR);
    }

    @Test
    public void writesRetryThenCanonicalBlackOneUpdatePeriodApart() {
        List<Integer> writes = new ArrayList<>();
        List<Long> sleeps = new ArrayList<>();

        boolean cleared = ForcedBlackClear.apply(33, writes::add, sleeps::add);

        assertTrue(cleared);
        assertEquals(
                Arrays.asList(ForcedBlackClear.RETRY_COLOR, ForcedBlackClear.CANONICAL_COLOR),
                writes
        );
        assertEquals(Arrays.asList(33L, 33L), sleeps);
    }

    @Test
    public void zeroAdvertisedPeriodStillSeparatesWrites() {
        List<Long> sleeps = new ArrayList<>();

        ForcedBlackClear.apply(0, ignored -> true, sleeps::add);

        assertEquals(Arrays.asList(1L, 1L), sleeps);
    }

    @Test
    public void failedFirstWriteStopsTheSequence() {
        List<Integer> writes = new ArrayList<>();

        boolean cleared = ForcedBlackClear.apply(
                33,
                color -> {
                    writes.add(color);
                    return false;
                },
                ignored -> {}
        );

        assertFalse(cleared);
        assertEquals(Arrays.asList(ForcedBlackClear.RETRY_COLOR), writes);
    }

    @Test
    public void interruptedWaitDoesNotClaimACompletedClear() {
        List<Integer> writes = new ArrayList<>();

        boolean cleared = ForcedBlackClear.apply(
                33,
                writes::add,
                ignored -> { throw new InterruptedException("test"); }
        );

        assertFalse(cleared);
        assertEquals(Arrays.asList(ForcedBlackClear.RETRY_COLOR), writes);
        assertTrue(Thread.interrupted()); // clear the test thread's interrupt flag
    }

    @Test
    public void productionStrategyIsNamedVersionedAndContainsNoVisibleRgb() {
        assertEquals("alpha_black_then_zero", ForcedBlackClear.STRATEGY_ID);
        assertTrue(ForcedBlackClear.STRATEGY_VERSION > 0);
        for (int color : ForcedBlackClear.Stimulus.ALPHA_BLACK_THEN_ZERO.colors()) {
            assertEquals(0, color & 0x00FFFFFF);
        }
    }

    @Test
    public void diagnosticCloseTransitionStopsAfterAlphaBlack() {
        List<Integer> writes = new ArrayList<>();
        List<Long> sleeps = new ArrayList<>();

        assertTrue(ForcedBlackClear.apply(
                ForcedBlackClear.Stimulus.ALPHA_BLACK_THEN_CLOSE,
                33,
                writes::add,
                sleeps::add
        ));

        assertEquals(Arrays.asList(ForcedBlackClear.RETRY_COLOR), writes);
        assertEquals(Arrays.asList(33L), sleeps);
    }

    @Test
    public void observersRunOnlyAfterEachAcceptedFrameSettles() {
        List<String> events = new ArrayList<>();

        assertTrue(ForcedBlackClear.apply(
                ForcedBlackClear.Stimulus.ALPHA_BLACK_THEN_ZERO,
                33,
                color -> {
                    events.add("write:" + color);
                    return true;
                },
                millis -> events.add("sleep:" + millis),
                color -> events.add("read:" + color)
        ));

        assertEquals(Arrays.asList(
                "write:" + ForcedBlackClear.RETRY_COLOR,
                "sleep:33",
                "read:" + ForcedBlackClear.RETRY_COLOR,
                "write:" + ForcedBlackClear.CANONICAL_COLOR,
                "sleep:33",
                "read:" + ForcedBlackClear.CANONICAL_COLOR
        ), events);
    }
}
