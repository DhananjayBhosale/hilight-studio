package com.hilight.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class EngineEffectPolicyTest {

    @Test
    public void iterationCountCeilsToCoverTheFiniteDeadline() {
        assertEquals(17, Engine.effectIterations(10_317, 628));
        assertEquals(4, Engine.effectIterations(4_000, 1_200));
        assertEquals(1, Engine.effectIterations(628, 628));
        assertEquals(1, Engine.effectIterations(627, 628));
        assertTrue(Engine.coverageEnd(0, Engine.effectIterations(10_317, 628), 628) >= 10_317);
        assertTrue(Engine.coverageEnd(0, 16, 628) < 10_317);
    }

    @Test
    public void ambientTimeoutIsBoundedAtStateIngress() {
        assertEquals(1_000, Engine.clampAmbientTimeout(999));
        assertEquals(Engine.DEFAULT_AMBIENT_TIMEOUT_MS,
                Engine.clampAmbientTimeout(Engine.DEFAULT_AMBIENT_TIMEOUT_MS));
        assertEquals(123_456, Engine.clampAmbientTimeout(123_456));
        assertEquals(Engine.MAX_AMBIENT_TIMEOUT_MS,
                Engine.clampAmbientTimeout(Engine.MAX_AMBIENT_TIMEOUT_MS + 1));
        assertEquals(Engine.MAX_AMBIENT_TIMEOUT_MS,
                Engine.clampAmbientTimeout(Long.MAX_VALUE));
    }

    @Test
    public void maximumAmbientTimeoutCoversDeadlineWithoutIterationSaturation() {
        int iterations = Engine.effectIterations(Engine.MAX_AMBIENT_TIMEOUT_MS, Engine.FRAME_MS);
        assertEquals(4_546, iterations);
        assertTrue(iterations < Integer.MAX_VALUE);
        assertTrue(Engine.coverageEnd(0, iterations, Engine.FRAME_MS)
                >= Engine.MAX_AMBIENT_TIMEOUT_MS);
    }

    @Test
    public void iterationCountRejectsInvalidInputsAndSaturates() {
        assertEquals(0, Engine.effectIterations(0, 628));
        assertEquals(0, Engine.effectIterations(-1, 628));
        assertEquals(0, Engine.effectIterations(628, 0));
        assertEquals(0, Engine.effectIterations(628, -1));
        assertEquals(Integer.MAX_VALUE, Engine.effectIterations(Long.MAX_VALUE, 1));
    }

    @Test
    public void cachedSubmissionUsesTheExactGateDeadline() {
        long now = 1_000;
        long required = Engine.requiredCoverageEnd(now, 628);
        assertTrue(Engine.isHardwareSubmissionLive(
                true, true, true, true, true, required, required));
        assertTrue(Engine.isHardwareSubmissionLive(
                true, true, true, true, true, required + 1, required));
        assertFalse(Engine.isHardwareSubmissionLive(
                true, true, true, true, true, required - 1, required));
        assertFalse(Engine.isHardwareSubmissionLive(
                true, true, true, true, false, required, required));
        assertFalse(Engine.isHardwareSubmissionLive(
                false, true, true, true, true, required, required));
        assertEquals(Long.MAX_VALUE,
                Engine.requiredCoverageEnd(Long.MAX_VALUE - 1, Long.MAX_VALUE));
    }

    @Test
    public void terminationTimingMatchesValidatedBlackDrain() {
        assertEquals(132, Engine.BLACK_EFFECT_WAIT_MS);
        assertEquals(66, Engine.BLACK_STATIC_DRAIN_MS);
        assertEquals(2 * Engine.FRAME_MS, Engine.BLACK_EFFECT_WAIT_MS);
        assertEquals(Engine.FRAME_MS, Engine.BLACK_STATIC_DRAIN_MS);
    }

    @Test
    public void taperBucketReplacementRequiresACompleteReplacementCycle() {
        long now = 1_000;
        long period = 628;
        for (long remaining : new long[]{0, 305, 627}) {
            long required = Engine.requiredCoverageEnd(now, remaining);
            assertTrue(Engine.shouldReuseHardwareSubmission(
                    true, false, true, true, true, required, required, remaining, period));
        }
        for (long remaining : new long[]{628, 629}) {
            long required = Engine.requiredCoverageEnd(now, remaining);
            assertFalse(Engine.shouldReuseHardwareSubmission(
                    true, false, true, true, true, required, required, remaining, period));
        }

        long required1199 = Engine.requiredCoverageEnd(now, 1_199);
        assertTrue(Engine.shouldReuseHardwareSubmission(
                true, false, true, true, true, required1199, required1199, 1_199, 1_200));
        long required1200 = Engine.requiredCoverageEnd(now, 1_200);
        assertFalse(Engine.shouldReuseHardwareSubmission(
                true, false, true, true, true, required1200, required1200, 1_200, 1_200));
    }

    @Test
    public void matchingBucketReusesNormallyWhenCoverageIsSufficient() {
        assertTrue(Engine.shouldReuseHardwareSubmission(
                true, true, true, true, true, Long.MAX_VALUE, Long.MAX_VALUE,
                Long.MAX_VALUE, 0));
        assertTrue(Engine.shouldReuseHardwareSubmission(
                true, true, true, true, true, 1_000, 1_000, -1, -1));
    }

    @Test
    public void bucketReuseRequiresEveryOtherCacheIdentityAndCoverage() {
        long now = 1_000;
        long required = Engine.requiredCoverageEnd(now, 305);
        assertFalse(Engine.shouldReuseHardwareSubmission(
                false, false, true, true, true, required, required, 305, 628));
        assertFalse(Engine.shouldReuseHardwareSubmission(
                true, false, false, true, true, required, required, 305, 628));
        assertFalse(Engine.shouldReuseHardwareSubmission(
                true, false, true, false, true, required, required, 305, 628));
        assertFalse(Engine.shouldReuseHardwareSubmission(
                true, false, true, true, false, required, required, 305, 628));
        assertFalse(Engine.shouldReuseHardwareSubmission(
                true, false, true, true, true, required - 1, required, 305, 628));
    }

    @Test
    public void nonpositivePeriodDoesNotGuardBucketMismatch() {
        long now = 1_000;
        long required = Engine.requiredCoverageEnd(now, 0);
        assertFalse(Engine.shouldReuseHardwareSubmission(
                true, false, true, true, true, required, required, 0, 0));
        assertFalse(Engine.shouldReuseHardwareSubmission(
                true, false, true, true, true, required, required, 0, -1));
    }

    @Test
    public void coverageArithmeticSaturatesInsteadOfWrapping() {
        assertEquals(Long.MAX_VALUE,
                Engine.coverageEnd(Long.MAX_VALUE - 1, Integer.MAX_VALUE, Long.MAX_VALUE));
    }

    @Test
    public void candidateSeedMixIsDeterministicButChangesPerCandidateCounter() {
        long first = Engine.mixSeed(1L, 2L, 3);
        assertEquals(first, Engine.mixSeed(1L, 2L, 3));
        assertNotEquals(first, Engine.mixSeed(2L, 2L, 3));
        assertNotEquals(first, Engine.mixSeed(1L, 3L, 3));
        assertNotEquals(first, Engine.mixSeed(1L, 2L, 4));
    }

    @Test
    public void safetyScaleBucketsOnlyDownwardInFivePercentSteps() {
        assertEquals(1.0, Engine.scaleBucket(1.0), 0.0);
        assertEquals(0.95, Engine.scaleBucket(0.99), 0.0);
        assertEquals(0.95, Engine.scaleBucket(0.95), 0.0);
        assertEquals(0.90, Engine.scaleBucket(0.949), 0.0);
        assertEquals(0.05, Engine.scaleBucket(0.051), 0.0);
        assertEquals(0.05, Engine.scaleBucket(0.05), 0.0);
        assertEquals(0.0, Engine.scaleBucket(0.02), 0.0);
    }

    @Test
    public void interruptedDrainWaitsAndRestoresInterruptStatus() {
        Thread.interrupted();
        long started = System.nanoTime();
        try {
            Thread.currentThread().interrupt();
            assertTrue(Engine.sleepPreservingInterrupt(20));
            long elapsed = System.nanoTime() - started;
            assertTrue("drain returned before its bounded wait", elapsed >= 10_000_000L);
            assertTrue("drain exceeded its bounded wait", elapsed < 1_000_000_000L);
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }
}
