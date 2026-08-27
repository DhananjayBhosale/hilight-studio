package com.hilight.core;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class SafetyGuardTest {

    private static final int[] RED = {0xFFFF0000};

    @Test
    public void dutyLimitRestsThenResumesWhenTheWindowRollsOver() {
        SafetyGuard guard = new SafetyGuard(100, 0.5, 1_000, 20, 0.5);

        for (int now = 0; now <= 40; now += 10) assertArrayEquals(RED, guard.apply(RED, now, 1.0));
        assertArrayEquals(new int[]{0}, guard.apply(RED, 50, 1.0));
        assertTrue(guard.isResting());

        assertArrayEquals(RED, guard.apply(RED, 100, 1.0));
        assertFalse(guard.isResting());
    }

    @Test
    public void sustainedLightTapersAfterTheConfiguredDelay() {
        SafetyGuard guard = new SafetyGuard(1_000, 0.5, 20, 20, 0.5);

        assertArrayEquals(RED, guard.apply(RED, 0, 1.0));
        assertArrayEquals(RED, guard.apply(RED, 10, 1.0));
        assertArrayEquals(RED, guard.apply(RED, 20, 1.0));
        assertArrayEquals(new int[]{Renderer.scale(RED[0], 0.75)}, guard.apply(RED, 30, 1.0));
    }

    @Test
    public void stalledRendererAccountsActualElapsedLatchedTime() {
        SafetyGuard guard = new SafetyGuard(100, 0.5, 1_000, 20, 0.5);

        assertArrayEquals(RED, guard.apply(RED, 0, 1.0));
        assertArrayEquals(new int[]{0}, guard.apply(RED, 60, 1.0));
        assertTrue(guard.isResting());
    }

    @Test
    public void stallAcrossAWholeWindowCannotResetAnObservedDutyOverrun() {
        SafetyGuard guard = new SafetyGuard(100, 0.5, 1_000, 20, 0.5);

        assertArrayEquals(RED, guard.apply(RED, 0, 1.0));
        assertArrayEquals(new int[]{0}, guard.apply(RED, 100, 1.0));
        assertTrue(guard.isResting());
        assertArrayEquals(new int[]{0}, guard.apply(RED, 150, 1.0));

        assertArrayEquals(RED, guard.apply(RED, 200, 1.0));
        assertFalse(guard.isResting());
    }

    @Test
    public void darkFrameResetsTheContinuousLightTimer() {
        SafetyGuard guard = new SafetyGuard(1_000, 0.5, 20, 20, 0.5);

        guard.apply(RED, 0, 1.0);
        guard.apply(RED, 10, 1.0);
        assertArrayEquals(new int[]{0}, guard.apply(new int[]{0}, 20, 1.0));
        assertArrayEquals(RED, guard.apply(RED, 30, 1.0));
    }

    @Test
    public void quietHoursDimAppliesBeforeSafetyTaper() {
        SafetyGuard guard = new SafetyGuard(1_000, 0.5, 100, 20, 0.5);

        assertArrayEquals(new int[]{Renderer.scale(RED[0], 0.5)}, guard.apply(RED, 0, 0.5));
    }
}
