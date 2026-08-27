package com.hilight.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

public final class LightsBackendClearTest {

    private static final int[] LEDS = {1, 2, 3, 4, 5, 6, 7, 8};

    @Test
    public void automaticCycleRunsEverySafePassWithoutClaimingPhysicalSuccess() {
        Rig rig = new Rig(LEDS);

        assertEquals(
                LightsBackend.ClearResult.BINDER_ACCEPTED_UNVERIFIED,
                rig.lights.forceBlack()
        );
        assertTrue(rig.lights.isBlackClearPending());
        assertEquals(1, rig.lights.clearAttemptsUsed());
        assertEquals(2, rig.lights.cleanupBorrowsRemaining());

        rig.advanceRetry();
        assertEquals(
                LightsBackend.ClearResult.BINDER_ACCEPTED_UNVERIFIED,
                rig.lights.forceBlack()
        );
        rig.advanceRetry();
        assertEquals(
                LightsBackend.ClearResult.COMPLETED_UNVERIFIED,
                rig.lights.forceBlack()
        );

        assertFalse(rig.lights.isBlackClearPending());
        assertTrue(rig.lights.isBlackClearTerminal());
        assertEquals(LightsBackend.ClearResult.BINDER_ACCEPTED_UNVERIFIED,
                rig.lights.lastClearAttemptResult());
        assertEquals(LightsBackend.ClearStage.CLOSED, rig.lights.lastClearStage());
        assertEquals(3, rig.io.priorities.size());
        assertEquals(3, rig.io.closes);
        assertEquals(6, rig.io.firstColors.size());
        assertEquals(Arrays.asList(33L, 33L, 33L, 33L, 33L, 33L), rig.sleeper.sleeps);
    }

    @Test
    public void alphaReadbackDistinguishesEffectiveFrameworkStateFromPhotons() {
        Rig rig = new Rig(LEDS);
        rig.io.readbackFollowsWrites = true;

        assertEquals(
                LightsBackend.ClearResult.FRAMEWORK_EFFECTIVE_UNVERIFIED,
                rig.lights.forceBlack()
        );

        assertEquals(LightsBackend.ClearResult.FRAMEWORK_EFFECTIVE_UNVERIFIED,
                rig.lights.lastClearAttemptResult());
        assertTrue(rig.io.reads > 0);
        assertTrue(rig.lights.isBlackClearPending());
    }

    @Test
    public void visibleReadbackIsShadowedAndAllShadowedPassesEndExhausted() {
        Rig rig = new Rig(LEDS);
        rig.io.fixedReadback = 0xFFFF0000;

        assertEquals(LightsBackend.ClearResult.SHADOWED, rig.lights.forceBlack());
        rig.advanceRetry();
        assertEquals(LightsBackend.ClearResult.SHADOWED, rig.lights.forceBlack());
        rig.advanceRetry();
        assertEquals(LightsBackend.ClearResult.EXHAUSTED, rig.lights.forceBlack());

        assertEquals(LightsBackend.ClearResult.SHADOWED,
                rig.lights.lastClearAttemptResult());
        assertEquals(LightsBackend.ClearResult.EXHAUSTED, rig.lights.lastClearResult());
        assertFalse(rig.lights.isBlackClearPending());
    }

    @Test
    public void finalCanonicalReadbackCanDetectLateShadowing() {
        Rig rig = new Rig(LEDS);
        rig.io.readbackFollowsWrites = true;
        rig.io.shadowCanonicalOnly = true;

        assertEquals(LightsBackend.ClearResult.SHADOWED, rig.lights.forceBlack());

        assertEquals(LightsBackend.ClearResult.SHADOWED,
                rig.lights.lastClearAttemptResult());
    }

    @Test
    public void readbackAggregationPrioritizesMismatchOverEarlierUnavailableLed() {
        Rig rig = new Rig(LEDS);
        rig.io.readbackFollowsWrites = true;
        rig.io.unavailableReadbackId = LEDS[0];
        rig.io.shadowedReadbackId = LEDS[1];

        assertEquals(LightsBackend.ClearResult.SHADOWED, rig.lights.forceBlack());
    }

    @Test
    public void unreadableReadbackRemainsBinderAcceptedUnverified() {
        Rig rig = new Rig(LEDS);
        rig.io.throwOnRead = true;

        assertEquals(
                LightsBackend.ClearResult.BINDER_ACCEPTED_UNVERIFIED,
                rig.lights.forceBlack()
        );
        assertEquals(LightsBackend.ClearResult.BINDER_ACCEPTED_UNVERIFIED,
                rig.lights.lastClearAttemptResult());
    }

    @Test
    public void failedOpensConsumeBoundedBudgetAndEndExplicitlyExhausted() {
        Rig rig = new Rig(LEDS);
        rig.io.acceptOpen = false;

        assertEquals(LightsBackend.ClearResult.IO_FAILED, rig.lights.forceBlack());
        rig.advanceRetry();
        assertEquals(LightsBackend.ClearResult.IO_FAILED, rig.lights.forceBlack());
        rig.advanceRetry();
        assertEquals(LightsBackend.ClearResult.EXHAUSTED, rig.lights.forceBlack());

        assertEquals(3, rig.io.priorities.size());
        assertEquals(0, rig.io.closes);
        assertFalse(rig.lights.isBlackClearPending());
        assertEquals(LightsBackend.ClearResult.IO_FAILED,
                rig.lights.lastClearAttemptResult());
    }

    @Test
    public void duplicateIdleDocumentsCannotRearmAnExhaustedCycle() {
        Rig rig = new Rig(LEDS);
        rig.io.acceptOpen = false;
        exhaust(rig);
        int opensAtLimit = rig.io.priorities.size();

        for (int i = 0; i < 100; i++) rig.lights.requestBlackClear();

        assertEquals(LightsBackend.ClearResult.NOT_REQUESTED, rig.lights.forceBlack());
        assertEquals(opensAtLimit, rig.io.priorities.size());
        assertEquals(LightsBackend.ClearResult.EXHAUSTED, rig.lights.lastClearResult());
    }

    @Test
    public void firstVisibleAttemptArmsOneFreshAutomaticCycle() {
        Rig rig = new Rig(LEDS);
        finishSuccessfulCycle(rig);
        long oldCycle = rig.lights.clearCycleId();

        assertTrue(rig.lights.openSession(7));
        assertTrue(rig.lights.push(new int[]{0xFFFF4081}));
        assertEquals(oldCycle + 1, rig.lights.clearCycleId());
        assertEquals(LightsBackend.MAX_CLEANUP_BORROWS,
                rig.lights.cleanupBorrowsRemaining());
        assertTrue(rig.lights.isBlackClearPending());

        assertTrue(rig.lights.push(new int[]{0xFF336699}));
        assertEquals(oldCycle + 1, rig.lights.clearCycleId());
        assertEquals(0, rig.lights.clearAttemptsUsed());
    }

    @Test
    public void failedVisibleWriteStillArmsOnceAndRetryCannotRearm() {
        Rig rig = new Rig(LEDS);
        finishSuccessfulCycle(rig);
        long oldCycle = rig.lights.clearCycleId();

        assertTrue(rig.lights.openSession(7));
        rig.io.failWriteNumber(1);
        assertFalse(rig.lights.push(new int[]{0xFFFF4081}));
        assertEquals(oldCycle + 1, rig.lights.clearCycleId());
        assertTrue(rig.lights.isBlackClearPending());
        assertFalse(rig.lights.isSessionOpen());

        assertTrue(rig.lights.openSession(7));
        assertTrue(rig.lights.push(new int[]{0xFF336699}));
        assertEquals(oldCycle + 1, rig.lights.clearCycleId());
        assertEquals(0, rig.lights.clearAttemptsUsed());
        assertEquals(LightsBackend.MAX_CLEANUP_BORROWS,
                rig.lights.cleanupBorrowsRemaining());
    }

    @Test
    public void transientDarkClosePreservesCycleAndBlanksEachNewNormalSessionOnce() {
        Rig rig = new Rig(LEDS);
        finishSuccessfulCycle(rig);
        assertTrue(rig.lights.openSession(7));
        assertTrue(rig.lights.push(new int[]{0xFFFF4081}));
        long cycle = rig.lights.clearCycleId();
        rig.resetObservations();

        assertTrue(rig.lights.closeTransientDarkSession());

        assertFalse(rig.lights.isSessionOpen());
        assertEquals(cycle, rig.lights.clearCycleId());
        assertEquals(0, rig.lights.clearAttemptsUsed());
        assertEquals(LightsBackend.MAX_CLEANUP_BORROWS,
                rig.lights.cleanupBorrowsRemaining());
        assertTrue(rig.lights.isBlackClearPending());
        assertTrue(rig.io.priorities.isEmpty());
        assertEquals(
                Arrays.asList(ForcedBlackClear.RETRY_COLOR, ForcedBlackClear.CANONICAL_COLOR),
                rig.io.firstColors
        );
        assertEquals(1, rig.io.closes);

        rig.resetObservations();
        assertTrue(rig.lights.openSession(7));
        assertTrue(rig.lights.push(new int[]{0xFF336699}));
        assertEquals(cycle, rig.lights.clearCycleId());
        rig.resetObservations();
        assertTrue(rig.lights.closeTransientDarkSession());
        assertEquals(
                Arrays.asList(ForcedBlackClear.RETRY_COLOR, ForcedBlackClear.CANONICAL_COLOR),
                rig.io.firstColors
        );
        assertEquals(1, rig.io.closes);
    }

    @Test
    public void stopHasOneReservedAttemptAfterAutomaticPassesAreSpent() {
        Rig rig = new Rig(LEDS);
        finishSuccessfulCycle(rig);
        int opensAfterAutomatic = rig.io.priorities.size();

        rig.lights.requestBlackClearNow();
        assertEquals(
                LightsBackend.ClearResult.COMPLETED_UNVERIFIED,
                rig.lights.forceBlack()
        );

        assertEquals(opensAfterAutomatic + 1, rig.io.priorities.size());
        assertFalse(rig.lights.stopClearAttemptAvailable());
        assertEquals(4, rig.lights.clearAttemptsUsed());

        rig.lights.requestBlackClearNow();
        assertEquals(LightsBackend.ClearResult.NOT_REQUESTED, rig.lights.forceBlack());
        assertEquals(opensAfterAutomatic + 1, rig.io.priorities.size());
    }

    @Test
    public void preReleaseStimulusRunsOnceEvenWhenCloseKeepsFailing() {
        Rig rig = new Rig(LEDS);
        assertTrue(rig.lights.openSession(7));
        assertTrue(rig.lights.push(new int[]{0xFFFF4081}));
        rig.resetObservations();
        rig.io.acceptClose = false;

        assertEquals(
                LightsBackend.ClearResult.BINDER_ACCEPTED_UNVERIFIED,
                rig.lights.forceBlack()
        );
        assertFalse(rig.lights.closeSession());
        for (int i = 0; i < 10; i++) {
            rig.lights.forceBlack();
            rig.lights.closeSession();
        }

        assertEquals(
                Arrays.asList(ForcedBlackClear.RETRY_COLOR, ForcedBlackClear.CANONICAL_COLOR),
                rig.io.firstColors
        );
        assertEquals(1, rig.io.closes);
        assertTrue(rig.lights.isSessionOpen());
    }

    @Test
    public void permanentCleanupCloseFailureNeverRepeatsWritesAndIsBounded() {
        Rig rig = new Rig(LEDS);
        rig.io.acceptClose = false;

        assertEquals(LightsBackend.ClearResult.IO_FAILED, rig.lights.forceBlack());
        for (int i = 1; i < LightsBackend.MAX_CLOSE_FAILURES; i++) {
            rig.advanceRetry();
            rig.lights.forceBlack();
        }
        int writesAtExhaustion = rig.io.firstColors.size();
        int closesBeforeFinalOverride = rig.io.closes;
        for (int i = 0; i < 20; i++) {
            rig.advanceRetry();
            assertEquals(LightsBackend.ClearResult.EXHAUSTED, rig.lights.forceBlack());
        }

        assertEquals(2, writesAtExhaustion);
        assertEquals(LightsBackend.MAX_CLOSE_FAILURES, closesBeforeFinalOverride);
        assertEquals(writesAtExhaustion, rig.io.firstColors.size());
        assertEquals(LightsBackend.MAX_CLOSE_FAILURES + 1, rig.io.closes);
        assertEquals(LightsBackend.ClearStage.CLOSE_EXHAUSTED, rig.lights.lastClearStage());
        assertTrue(rig.lights.isSessionOpen());
        assertFalse(rig.lights.isBlackClearPending());
        assertTrue(rig.lights.isBlackClearTerminal());
        assertTrue(rig.lights.isUnreleasedFatal());
    }

    @Test
    public void recoveredCloseRestoresTheCompletedStimulusEvidence() {
        Rig rig = new Rig(LEDS);
        rig.io.acceptClose = false;

        assertEquals(LightsBackend.ClearResult.IO_FAILED, rig.lights.forceBlack());
        assertEquals(LightsBackend.ClearResult.IO_FAILED,
                rig.lights.lastClearAttemptResult());

        rig.io.acceptClose = true;
        rig.advanceRetry();
        assertEquals(
                LightsBackend.ClearResult.BINDER_ACCEPTED_UNVERIFIED,
                rig.lights.forceBlack()
        );
        assertEquals(LightsBackend.ClearResult.BINDER_ACCEPTED_UNVERIFIED,
                rig.lights.lastClearAttemptResult());
        assertEquals(LightsBackend.ClearStage.CLOSED, rig.lights.lastClearStage());
    }

    @Test
    public void finalCloseOverrideRunsAutomaticallyAfterOrdinaryCloseBudget() {
        Rig rig = new Rig(LEDS);
        rig.io.acceptClose = false;
        rig.lights.forceBlack();
        for (int i = 1; i < LightsBackend.MAX_CLOSE_FAILURES; i++) {
            rig.advanceRetry();
            rig.lights.forceBlack();
        }
        assertEquals(LightsBackend.MAX_CLOSE_FAILURES, rig.io.closes);

        rig.io.acceptClose = true;
        rig.advanceRetry();
        rig.lights.forceBlack();

        assertEquals(LightsBackend.MAX_CLOSE_FAILURES + 1, rig.io.closes);
        assertFalse(rig.lights.isSessionOpen());
    }

    @Test
    public void repeatedStopRequestsCannotLoopTheFinalCloseOverride() {
        Rig rig = new Rig(LEDS);
        rig.io.acceptClose = false;
        rig.lights.forceBlack();
        for (int i = 1; i < LightsBackend.MAX_CLOSE_FAILURES; i++) {
            rig.advanceRetry();
            rig.lights.forceBlack();
        }

        rig.lights.requestBlackClearNow();
        rig.lights.forceBlack();
        for (int i = 0; i < 20; i++) {
            rig.lights.requestBlackClearNow();
            rig.advanceRetry();
            rig.lights.forceBlack();
        }

        assertEquals(LightsBackend.MAX_CLOSE_FAILURES + 1, rig.io.closes);
        assertEquals(2, rig.io.firstColors.size());
        assertTrue(rig.lights.isSessionOpen());
        assertTrue(rig.lights.isUnreleasedFatal());
    }

    @Test
    public void reservedBorrowGetsItsFinalCloseOverrideAfterConsumingTheReserve() {
        Rig rig = new Rig(LEDS);
        finishSuccessfulCycle(rig);
        rig.resetObservations();
        rig.io.acceptClose = false;
        rig.lights.requestBlackClearNow();
        rig.lights.forceBlack();
        assertFalse(rig.lights.stopClearAttemptAvailable());
        for (int i = 1; i < LightsBackend.MAX_CLOSE_FAILURES; i++) {
            rig.advanceRetry();
            rig.lights.forceBlack();
        }

        rig.io.acceptClose = true;
        rig.advanceRetry();
        rig.lights.forceBlack();

        assertEquals(LightsBackend.MAX_CLOSE_FAILURES + 1, rig.io.closes);
        assertFalse(rig.lights.isSessionOpen());
        assertEquals(LightsBackend.ClearResult.COMPLETED_UNVERIFIED,
                rig.lights.lastClearResult());
    }

    @Test
    public void failedWriteClosesBorrowedSessionAndRemainsBounded() {
        Rig rig = new Rig(LEDS);
        rig.io.failWriteNumber(1);

        assertEquals(LightsBackend.ClearResult.IO_FAILED, rig.lights.forceBlack());

        assertEquals(1, rig.io.closes);
        assertFalse(rig.lights.isSessionOpen());
        assertTrue(rig.lights.isBlackClearPending());
        assertEquals(Arrays.asList(ForcedBlackClear.RETRY_COLOR), rig.io.firstColors);
    }

    @Test
    public void failedSecondWriteDoesNotClaimFrameworkEvidence() {
        Rig rig = new Rig(LEDS);
        rig.io.readbackFollowsWrites = true;
        rig.io.failWriteNumber(2);

        assertEquals(LightsBackend.ClearResult.IO_FAILED, rig.lights.forceBlack());

        assertEquals(
                Arrays.asList(ForcedBlackClear.RETRY_COLOR, ForcedBlackClear.CANONICAL_COLOR),
                rig.io.firstColors
        );
        assertEquals(LightsBackend.ClearResult.IO_FAILED,
                rig.lights.lastClearAttemptResult());
        assertEquals(1, rig.io.closes);
    }

    @Test
    public void interruptedStimulusClosesSessionAndRecordsIoFailure() {
        Rig rig = new Rig(LEDS);
        rig.sleeper.interruptNext = true;

        assertEquals(LightsBackend.ClearResult.IO_FAILED, rig.lights.forceBlack());

        assertEquals(1, rig.io.closes);
        assertFalse(rig.lights.isSessionOpen());
        assertTrue(Thread.interrupted());
    }

    @Test
    public void noLightsEndsAsTerminalNoOpWithoutSpendingAttempts() {
        Rig rig = new Rig(new int[0]);

        assertEquals(LightsBackend.ClearResult.NOT_REQUESTED, rig.lights.forceBlack());

        assertFalse(rig.lights.isBlackClearPending());
        assertTrue(rig.lights.isBlackClearTerminal());
        assertEquals(0, rig.lights.clearAttemptsUsed());
        assertEquals(LightsBackend.ClearStage.NO_LIGHTS, rig.lights.lastClearStage());
    }

    @Test
    public void ordinaryRenderCloseDoesNotOverwriteClearCycleDiagnostics() {
        Rig rig = new Rig(LEDS);
        finishSuccessfulCycle(rig);
        assertTrue(rig.lights.openSession(7));
        assertTrue(rig.lights.push(new int[]{0xFFFF4081}));
        long cycle = rig.lights.clearCycleId();

        assertTrue(rig.lights.closeSession());

        assertEquals(cycle, rig.lights.clearCycleId());
        assertEquals(LightsBackend.ClearResult.NOT_REQUESTED, rig.lights.lastClearResult());
        assertEquals(LightsBackend.ClearStage.REQUESTED, rig.lights.lastClearStage());
        assertTrue(rig.lights.isBlackClearPending());
    }

    @Test
    public void explicitCycleRearmRequiresClosedTerminalBackend() {
        Rig rig = new Rig(LEDS);

        assertFalse(rig.lights.requestBlackClearCycle());
        finishSuccessfulCycle(rig);
        long previousCycle = rig.lights.clearCycleId();

        assertTrue(rig.lights.requestBlackClearCycle());
        assertEquals(previousCycle + 1, rig.lights.clearCycleId());
        assertTrue(rig.lights.isBlackClearPending());
        assertEquals(LightsBackend.MAX_CLEANUP_BORROWS,
                rig.lights.cleanupBorrowsRemaining());
        assertFalse(rig.lights.requestBlackClearCycle());
    }

    @Test
    public void manualCycleAfterAutomaticExhaustionRunsExactlyThreeBlackOnlyPasses() {
        Rig rig = new Rig(LEDS);
        rig.io.acceptOpen = false;
        exhaust(rig);
        long automaticCycle = rig.lights.clearCycleId();
        rig.io.acceptOpen = true;
        rig.resetObservations();

        assertTrue(rig.lights.requestBlackClearCycle());
        assertEquals(LightsBackend.ClearCycleSource.MANUAL, rig.lights.clearCycleSource());
        for (int i = 0; i < LightsBackend.MAX_CLEANUP_BORROWS; i++) {
            if (i > 0) rig.advanceRetry();
            rig.lights.forceBlack();
        }

        assertEquals(automaticCycle + 1, rig.lights.clearCycleId());
        assertEquals(LightsBackend.MAX_CLEANUP_BORROWS, rig.io.priorities.size());
        assertEquals(
                Arrays.asList(
                        ForcedBlackClear.RETRY_COLOR, ForcedBlackClear.CANONICAL_COLOR,
                        ForcedBlackClear.RETRY_COLOR, ForcedBlackClear.CANONICAL_COLOR,
                        ForcedBlackClear.RETRY_COLOR, ForcedBlackClear.CANONICAL_COLOR
                ),
                rig.io.firstColors
        );
        for (int color : rig.io.firstColors) assertEquals(0, color & 0x00FFFFFF);
        assertEquals(LightsBackend.ClearResult.COMPLETED_UNVERIFIED,
                rig.lights.lastClearResult());
        assertTrue(rig.lights.isBlackClearTerminal());
        assertFalse(rig.lights.isSessionOpen());
    }

    @Test
    public void manualRequestCannotResetPendingCycleBudget() {
        Rig rig = new Rig(LEDS);
        rig.lights.forceBlack();
        int remaining = rig.lights.cleanupBorrowsRemaining();
        long cycle = rig.lights.clearCycleId();

        assertFalse(rig.lights.requestBlackClearCycle());

        assertEquals(cycle, rig.lights.clearCycleId());
        assertEquals(remaining, rig.lights.cleanupBorrowsRemaining());
        assertEquals(LightsBackend.ClearCycleSource.AUTOMATIC, rig.lights.clearCycleSource());
    }

    @Test
    public void clearDiagnosticsExposeCycleStageTimestampAndSafeStrategy() {
        Rig rig = new Rig(LEDS);
        rig.clock.now = 1234;
        rig.lights.forceBlack();

        assertEquals(1, rig.lights.clearCycleId());
        assertEquals(1234, rig.lights.lastClearTimestampMs());
        assertEquals("alpha_black_then_zero", rig.lights.clearStrategyId());
        assertTrue(rig.lights.clearStrategyVersion() > 0);
        assertEquals(33, rig.lights.minUpdatePeriodMs());
    }

    @Test
    public void vendorSettlePeriodIsClampedWithoutChangingReportedMetadata() {
        Rig huge = new Rig(LEDS, Long.MAX_VALUE);
        huge.lights.forceBlack();
        assertEquals(
                Arrays.asList(
                        LightsBackend.MAX_CLEAR_SETTLE_MS,
                        LightsBackend.MAX_CLEAR_SETTLE_MS
                ),
                huge.sleeper.sleeps
        );
        assertEquals(Long.MAX_VALUE, huge.lights.minUpdatePeriodMs());

        Rig negative = new Rig(LEDS, -10);
        negative.lights.forceBlack();
        assertEquals(Arrays.asList(1L, 1L), negative.sleeper.sleeps);
        assertEquals(-10, negative.lights.minUpdatePeriodMs());
    }

    @Test
    public void cleanupPriorityIsBelowTheUserRangeWithoutExtremeSentinel() {
        assertTrue(LightsBackend.CLEANUP_PRIORITY < -10);
        assertTrue(LightsBackend.CLEANUP_PRIORITY > Integer.MIN_VALUE);
    }

    private static void finishSuccessfulCycle(Rig rig) {
        for (int i = 0; i < LightsBackend.MAX_CLEANUP_BORROWS; i++) {
            if (i > 0) rig.advanceRetry();
            rig.lights.forceBlack();
        }
        assertEquals(LightsBackend.ClearResult.COMPLETED_UNVERIFIED,
                rig.lights.lastClearResult());
    }

    private static void exhaust(Rig rig) {
        for (int i = 0; i < LightsBackend.MAX_CLEANUP_BORROWS; i++) {
            if (i > 0) rig.advanceRetry();
            rig.lights.forceBlack();
        }
        assertEquals(LightsBackend.ClearResult.EXHAUSTED, rig.lights.lastClearResult());
    }

    private static final class Rig {
        final FakeIo io = new FakeIo();
        final FakeClock clock = new FakeClock();
        final FakeSleeper sleeper = new FakeSleeper();
        final LightsBackend lights;

        Rig(int[] ids) {
            this(ids, 33);
        }

        Rig(int[] ids, long minUpdatePeriodMs) {
            lights = new LightsBackend(io, ids, minUpdatePeriodMs, clock, sleeper);
        }

        void advanceRetry() { clock.advance(LightsBackend.CLEANUP_RETRY_MS); }

        void resetObservations() {
            io.resetObservations();
            sleeper.sleeps.clear();
        }
    }

    private static final class FakeClock implements LightsBackend.Clock {
        long now;

        @Override
        public long nowMs() { return now; }

        void advance(long millis) { now += millis; }
    }

    private static final class FakeSleeper implements ForcedBlackClear.Sleeper {
        boolean interruptNext;
        final List<Long> sleeps = new ArrayList<>();

        @Override
        public void sleep(long millis) throws InterruptedException {
            sleeps.add(millis);
            if (interruptNext) {
                interruptNext = false;
                throw new InterruptedException("test");
            }
        }
    }

    private static final class FakeIo implements LightsBackend.SessionIo {
        int closes;
        int reads;
        int writeCalls;
        int failWriteAt = -1;
        int currentColor;
        boolean acceptOpen = true;
        boolean acceptClose = true;
        boolean readbackFollowsWrites;
        boolean throwOnRead;
        boolean shadowCanonicalOnly;
        int unavailableReadbackId = -1;
        int shadowedReadbackId = -1;
        Integer fixedReadback;
        final List<Integer> priorities = new ArrayList<>();
        final List<Integer> firstColors = new ArrayList<>();

        @Override
        public boolean open(int priority) {
            priorities.add(priority);
            return acceptOpen;
        }

        @Override
        public boolean close() {
            closes++;
            return acceptClose;
        }

        @Override
        public boolean set(int[] ids, int[] colors) {
            writeCalls++;
            currentColor = colors[0];
            firstColors.add(colors[0]);
            return writeCalls != failWriteAt;
        }

        @Override
        public Integer get(int id) throws Exception {
            reads++;
            if (throwOnRead) throw new Exception("readback unavailable");
            if (id == unavailableReadbackId) return null;
            if (id == shadowedReadbackId) return 0xFFFF0000;
            if (shadowCanonicalOnly && currentColor == ForcedBlackClear.CANONICAL_COLOR) {
                return 0xFFFF0000;
            }
            if (fixedReadback != null) return fixedReadback;
            return readbackFollowsWrites ? currentColor : null;
        }

        void failWriteNumber(int relativeNumber) {
            failWriteAt = writeCalls + relativeNumber;
        }

        void resetObservations() {
            closes = 0;
            reads = 0;
            priorities.clear();
            firstColors.clear();
        }
    }
}
