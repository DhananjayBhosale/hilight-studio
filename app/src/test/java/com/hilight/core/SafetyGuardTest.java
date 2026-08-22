package com.hilight.core;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class SafetyGuardTest {

    private static final int[] RED = {0xFFFF0000};

    @Test
    public void defaultFrameCadenceMatchesTheRenderLoop() {
        assertEquals(66, SafetyGuard.FRAME_MS);
        assertEquals(SafetyGuard.FRAME_MS, Engine.FRAME_MS);
    }

    @Test
    public void observeCombinesDimWithPreTaperRampAndFloor() {
        SafetyGuard guard = new SafetyGuard(10, 1_000, 0.5, 20, 20, 0.5);

        assertEquals(0.8, guard.observe(true, 0, 0.8).scale, 0.000001);
        assertEquals(0.8, guard.observe(true, 10, 0.8).scale, 0.000001);
        assertEquals(0.6, guard.observe(true, 20, 0.8).scale, 0.000001);
        assertEquals(0.4, guard.observe(true, 30, 0.8).scale, 0.000001);
    }

    @Test
    public void observePreservesTheExistingNoDimThreshold() {
        SafetyGuard guard = new SafetyGuard(10, 1_000, 0.5, 1_000, 20, 0.5);

        // Software apply deliberately does not dim at .999 or above; hardware buckets must match.
        assertEquals(1.0, guard.observe(true, 0, 0.999).scale, 0.000001);
    }

    @Test
    public void observeChargesLitIntervalsConservativelyAndThenRests() {
        SafetyGuard guard = new SafetyGuard(10, 100, 0.5, 1_000, 20, 0.5);

        for (int now = 0; now <= 40; now += 10) {
            assertFalse(guard.observe(true, now, 1.0).resting);
        }
        SafetyGuard.Decision decision = guard.observe(true, 50, 1.0);
        assertTrue(decision.resting);
        assertEquals(0.0, decision.scale, 0.000001);
    }

    @Test
    public void observeUnlitResetsTaperWithoutChargingDuty() {
        SafetyGuard guard = new SafetyGuard(10, 100, 0.5, 20, 20, 0.5);

        guard.observe(true, 0, 1.0);
        guard.observe(true, 10, 1.0);
        assertEquals(1.0, guard.observe(false, 20, 0.8).scale, 0.000001);
        guard.observe(true, 30, 1.0);
        guard.observe(true, 40, 1.0);
        guard.observe(true, 50, 1.0);
        assertFalse(guard.isResting());
        assertTrue(guard.observe(true, 60, 1.0).resting);
    }

    @Test
    public void applyingAnObservedDecisionDoesNotChargeTwice() {
        SafetyGuard observedGuard = new SafetyGuard(10, 1_000, 0.5, 20, 20, 0.5);
        SafetyGuard directGuard = new SafetyGuard(10, 1_000, 0.5, 20, 20, 0.5);

        SafetyGuard.Decision decision = observedGuard.observe(true, 0, 0.8);
        assertArrayEquals(directGuard.apply(RED, 0, 0.8), observedGuard.apply(RED, decision));
        assertEquals(2, observedGuard.dutyPercent());
        assertEquals(2, directGuard.dutyPercent());
    }

    @Test
    public void dutyLimitRestsThenResumesWhenTheWindowRollsOver() {
        SafetyGuard guard = new SafetyGuard(10, 100, 0.5, 1_000, 20, 0.5);

        for (int now = 0; now <= 40; now += 10) assertArrayEquals(RED, guard.apply(RED, now, 1.0));
        assertArrayEquals(new int[]{0}, guard.apply(RED, 50, 1.0));
        assertTrue(guard.isResting());

        assertArrayEquals(RED, guard.apply(RED, 100, 1.0));
        assertFalse(guard.isResting());
    }

    @Test
    public void sustainedLightTapersAfterTheConfiguredDelay() {
        SafetyGuard guard = new SafetyGuard(10, 1_000, 0.5, 20, 20, 0.5);

        assertArrayEquals(RED, guard.apply(RED, 0, 1.0));
        assertArrayEquals(RED, guard.apply(RED, 10, 1.0));
        assertArrayEquals(new int[]{Renderer.scale(RED[0], 0.75)}, guard.apply(RED, 20, 1.0));
    }

    @Test
    public void darkFrameResetsTheContinuousLightTimer() {
        SafetyGuard guard = new SafetyGuard(10, 1_000, 0.5, 20, 20, 0.5);

        guard.apply(RED, 0, 1.0);
        guard.apply(RED, 10, 1.0);
        assertArrayEquals(new int[]{0}, guard.apply(new int[]{0}, 20, 1.0));
        assertArrayEquals(RED, guard.apply(RED, 30, 1.0));
    }

    @Test
    public void quietHoursDimAppliesBeforeSafetyTaper() {
        SafetyGuard guard = new SafetyGuard(10, 1_000, 0.5, 100, 20, 0.5);

        assertArrayEquals(new int[]{Renderer.scale(RED[0], 0.5)}, guard.apply(RED, 0, 0.5));
    }
}
