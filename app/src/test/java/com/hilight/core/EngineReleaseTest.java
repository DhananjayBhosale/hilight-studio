package com.hilight.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.json.JSONObject;
import org.junit.Test;

public final class EngineReleaseTest {

    private static final int[] LEDS = {1, 2, 3, 4, 5, 6, 7, 8};

    @Test
    public void rawStateCannotImpersonateTheCleanupPriority() {
        assertEquals(Engine.USER_PRIORITY_MIN, Engine.clampUserPriority(Integer.MIN_VALUE));
        assertEquals(4, Engine.clampUserPriority(4));
        assertEquals(Engine.USER_PRIORITY_MAX, Engine.clampUserPriority(Integer.MAX_VALUE));
    }

    @Test
    public void rawStateCannotBypassTheAmbientTimeoutBounds() throws Exception {
        Rig rig = new Rig();
        rig.finishStartupCycle();
        Engine engine = rig.newEngine();

        engine.setState("{\"enabled\":false,\"ambientTimeoutMs\":9223372036854775807}");
        assertEquals(
                Engine.MAX_AMBIENT_TIMEOUT_MS,
                new JSONObject(engine.status()).getLong("timeoutMs")
        );

        engine.setState("{\"enabled\":false,\"ambientTimeoutMs\":-1}");
        assertEquals(1_000, new JSONObject(engine.status()).getLong("timeoutMs"));
    }

    @Test
    public void startupCleanupReachesTerminalBeforeRendererReadiness() {
        Rig rig = new Rig();
        rig.clock.autoAdvanceMs = LightsBackend.CLEANUP_RETRY_MS;
        Engine engine = rig.newEngine();

        assertTrue(engine.prepareForReady());

        assertTrue(rig.lights.isBlackClearTerminal());
        assertFalse(rig.lights.isSessionOpen());
        assertEquals(Arrays.asList(
                LightsBackend.CLEANUP_PRIORITY,
                LightsBackend.CLEANUP_PRIORITY,
                LightsBackend.CLEANUP_PRIORITY
        ), rig.io.priorities);
        assertEquals(
                Arrays.asList(
                        ForcedBlackClear.RETRY_COLOR, ForcedBlackClear.CANONICAL_COLOR,
                        ForcedBlackClear.RETRY_COLOR, ForcedBlackClear.CANONICAL_COLOR,
                        ForcedBlackClear.RETRY_COLOR, ForcedBlackClear.CANONICAL_COLOR
                ),
                rig.io.firstColors
        );
    }

    @Test
    public void startupCloseExhaustionStopsBestEffortAndRethrowsWithoutClaimingRelease()
            throws Exception {
        Rig rig = new Rig();
        rig.clock.autoAdvanceMs = LightsBackend.CLEANUP_RETRY_MS;
        rig.io.acceptClose = false;
        Engine engine = rig.newEngine();

        boolean failed = false;
        try {
            engine.start();
        } catch (IllegalStateException expected) {
            failed = true;
            assertTrue(expected.getMessage().contains("startup LED cleanup"));
        }

        assertTrue(failed);
        assertEquals(LightsBackend.MAX_CLOSE_FAILURES + 1, rig.io.closes);
        assertTrue(rig.lights.isSessionOpen());
        assertTrue(rig.lights.isBlackClearTerminal());
        assertFalse(rig.lights.isBlackClearPending());
        assertTrue(rig.lights.isUnreleasedFatal());
        JSONObject status = new JSONObject(engine.status());
        assertTrue(status.getBoolean("blackClearUnreleasedFatal"));
        assertEquals("exhausted", status.getString("blackClearResult"));
        assertEquals("close_exhausted", status.getString("blackClearStage"));
        assertEquals(0, status.getLong("releasedStateRevision"));
    }

    @Test
    public void startupExceptionBestEffortClosesTheBackendBeforeRethrow() {
        Rig rig = new Rig();
        rig.clock.autoAdvanceMs = LightsBackend.CLEANUP_RETRY_MS;
        rig.sleeper.throwRuntimeOnce = true;
        Engine engine = rig.newEngine();

        RuntimeException failure = null;
        try {
            engine.start();
        } catch (RuntimeException expected) {
            failure = expected;
        } catch (Exception unexpected) {
            throw new AssertionError(unexpected);
        }

        assertTrue(failure != null);
        assertEquals("startup stimulus failed", failure.getMessage());
        assertTrue(rig.io.closes > 0);
        assertFalse(rig.lights.isSessionOpen());
        assertTrue(rig.lights.isBlackClearTerminal());
    }

    @Test
    public void concurrentStopCannotBeOvertakenByStartupPublication() throws Exception {
        Rig rig = new Rig();
        rig.clock.autoAdvanceMs = LightsBackend.CLEANUP_RETRY_MS;
        rig.sleeper.blockOnce = true;
        Engine engine = rig.newEngine();
        AtomicReference<Throwable> startupFailure = new AtomicReference<>();

        Thread starter = new Thread(() -> {
            try {
                engine.start();
            } catch (Throwable failure) {
                startupFailure.set(failure);
            }
        }, "engine-start-test");
        starter.start();
        assertTrue(rig.sleeper.entered.await(2, TimeUnit.SECONDS));

        Thread stopper = new Thread(engine::stop, "engine-stop-test");
        stopper.start();
        stopper.join(50);
        assertTrue(stopper.isAlive());

        rig.sleeper.release.countDown();
        starter.join(5_000);
        stopper.join(5_000);

        assertFalse(starter.isAlive());
        assertFalse(stopper.isAlive());
        assertTrue(startupFailure.get() == null);
        assertFalse(rig.lights.isSessionOpen());
        assertTrue(rig.lights.isBlackClearTerminal());

        engine.setState("{\"enabled\":true,\"arm\":true,"
                + "\"ambient\":{\"mode\":\"solid\",\"color\":4294967295}}");
        Thread.sleep(100);
        assertFalse(rig.lights.isSessionOpen());
    }

    @Test
    public void receivedIdleRevisionIsNotReleasedUntilTheRenderThreadSettlesIt() throws Exception {
        Rig rig = new Rig();
        rig.finishStartupCycle();
        rig.resetObservations();
        Engine engine = rig.newEngine();

        engine.setState("{\"enabled\":false,\"stateRevision\":41}");

        JSONObject received = new JSONObject(engine.status());
        assertEquals(41, received.getLong("receivedStateRevision"));
        assertEquals(41, received.getLong("appliedStateRevision"));
        assertEquals(0, received.getLong("settledStateRevision"));
        assertEquals(0, received.getLong("releasedStateRevision"));

        rig.tick(engine, 10_000, 10_000);

        JSONObject released = new JSONObject(engine.status());
        assertEquals(41, released.getLong("settledStateRevision"));
        assertEquals(41, released.getLong("releasedStateRevision"));
        assertFalse(released.getBoolean("session"));
        assertFalse(released.getBoolean("blackClearPending"));
    }

    @Test
    public void duplicateDisabledDocumentsSettleWithoutStartingNewCleanupCycles() throws Exception {
        Rig rig = new Rig();
        rig.finishStartupCycle();
        Engine engine = rig.newEngine();
        engine.setState("{\"enabled\":false,\"stateRevision\":51}");
        rig.tick(engine, 10_000, 10_000);
        rig.resetObservations();

        engine.setState("{\"enabled\":false,\"stateRevision\":52}");
        rig.clock.now += LightsBackend.CLEANUP_RETRY_MS;
        rig.tick(engine, 10_033, 10_033);

        assertTrue(rig.io.priorities.isEmpty());
        assertTrue(rig.io.firstColors.isEmpty());
        assertEquals(52, new JSONObject(engine.status()).getLong("releasedStateRevision"));
    }

    @Test
    public void uniqueDisabledManualRequestRunsThreePassesAndRejectsReplayOrOlderIds()
            throws Exception {
        Rig rig = new Rig();
        rig.finishStartupCycle();
        Engine engine = rig.newEngine();

        engine.setState("{\"enabled\":false,\"manualBlackClearRequestId\":100}");
        JSONObject accepted = new JSONObject(engine.status());
        assertEquals(100, accepted.getLong("lastSeenManualBlackClearRequestId"));
        assertEquals(100, accepted.getLong("lastAcceptedManualBlackClearRequestId"));
        assertEquals("manual", accepted.getString("blackClearCycleSource"));
        assertTrue(accepted.getBoolean("blackClearPending"));

        rig.driveToTerminal(engine, 10_000);
        assertEquals(3, rig.io.priorities.size());
        assertEquals(Arrays.asList(
                ForcedBlackClear.RETRY_COLOR, ForcedBlackClear.CANONICAL_COLOR,
                ForcedBlackClear.RETRY_COLOR, ForcedBlackClear.CANONICAL_COLOR,
                ForcedBlackClear.RETRY_COLOR, ForcedBlackClear.CANONICAL_COLOR
        ), rig.io.firstColors);
        rig.resetObservations();

        engine.setState("{\"enabled\":false,\"manualBlackClearRequestId\":100}");
        rig.tick(engine, 11_000, 11_000);
        engine.setState("{\"enabled\":false,\"manualBlackClearRequestId\":99}");
        rig.tick(engine, 11_033, 11_033);

        JSONObject replayed = new JSONObject(engine.status());
        assertEquals(100, replayed.getLong("lastSeenManualBlackClearRequestId"));
        assertEquals(100, replayed.getLong("lastAcceptedManualBlackClearRequestId"));
        assertTrue(rig.io.priorities.isEmpty());
    }

    @Test
    public void unsafeOrPendingManualRequestsAreSeenButNeverAccepted() throws Exception {
        Rig rig = new Rig();
        rig.finishStartupCycle();
        Engine engine = rig.newEngine();

        engine.setState("{\"enabled\":true,\"manualBlackClearRequestId\":1}");
        assertManualIds(engine, 1, 0);
        engine.setState("{\"enabled\":false,\"alert\":{\"id\":9},"
                + "\"manualBlackClearRequestId\":2}");
        assertManualIds(engine, 2, 0);
        engine.setState("{\"enabled\":false,\"privacyOutputEnabled\":true,"
                + "\"manualBlackClearRequestId\":3}");
        assertManualIds(engine, 3, 0);

        assertTrue(rig.lights.openSession(7));
        engine.setState("{\"enabled\":false,\"manualBlackClearRequestId\":4}");
        assertManualIds(engine, 4, 0);
        assertTrue(rig.lights.closeSession());

        engine.setState("{\"enabled\":false,\"manualBlackClearRequestId\":5}");
        assertManualIds(engine, 5, 5);
        engine.setState("{\"enabled\":false,\"manualBlackClearRequestId\":6}");
        assertManualIds(engine, 6, 5);
        rig.driveToTerminal(engine, 20_000);

        // Request 6 was consumed as unsafe while 5 was pending; replaying it cannot arm a cycle.
        rig.resetObservations();
        engine.setState("{\"enabled\":false,\"manualBlackClearRequestId\":6}");
        rig.tick(engine, 21_000, 21_000);
        assertTrue(rig.io.priorities.isEmpty());
        assertManualIds(engine, 6, 5);

        engine.setState("{\"enabled\":false,\"manualBlackClearRequestId\":7}");
        assertManualIds(engine, 7, 7);
        rig.driveToTerminal(engine, 22_000);
        assertEquals(LightsBackend.MAX_CLEANUP_BORROWS, rig.io.priorities.size());
    }

    private static void assertManualIds(Engine engine, long seen, long accepted)
            throws Exception {
        JSONObject status = new JSONObject(engine.status());
        assertEquals(seen, status.getLong("lastSeenManualBlackClearRequestId"));
        assertEquals(accepted, status.getLong("lastAcceptedManualBlackClearRequestId"));
    }

    @Test
    public void failedSessionCloseCannotAcknowledgeRelease() throws Exception {
        Rig rig = new Rig();
        rig.finishStartupCycle();
        assertTrue(rig.lights.openSession(7));
        assertTrue(rig.lights.push(new int[]{0xFFFF4081}));
        rig.io.acceptClose = false;
        Engine engine = rig.newEngine();
        engine.setState("{\"enabled\":false,\"stateRevision\":61}");

        rig.tick(engine, 10_000, 10_000);

        JSONObject blocked = new JSONObject(engine.status());
        assertTrue(blocked.getBoolean("session"));
        assertEquals(0, blocked.getLong("releasedStateRevision"));

        rig.io.acceptClose = true;
        rig.driveToTerminal(engine, 10_033);

        JSONObject released = new JSONObject(engine.status());
        assertFalse(released.getBoolean("session"));
        assertEquals(61, released.getLong("releasedStateRevision"));
    }

    @Test
    public void stopCleanupIsIdempotent() {
        Rig rig = new Rig();
        rig.finishStartupCycle();
        rig.clock.now = LightsBackend.CLEANUP_RETRY_MS;
        rig.clock.autoAdvanceMs = LightsBackend.CLEANUP_RETRY_MS;
        assertTrue(rig.lights.openSession(7));
        assertTrue(rig.lights.push(new int[]{0xFFFF4081}));
        rig.resetObservations();
        Engine engine = rig.newEngine();

        engine.stop();
        int writesAfterFirstStop = rig.io.firstColors.size();
        int closesAfterFirstStop = rig.io.closes;
        engine.stop();

        assertEquals(writesAfterFirstStop, rig.io.firstColors.size());
        assertEquals(closesAfterFirstStop, rig.io.closes);
        assertTrue(rig.lights.isBlackClearTerminal());
        assertFalse(rig.lights.isSessionOpen());
    }

    @Test
    public void idleStateAfterAReleasedFrameBorrowsAndWritesBlack() {
        Rig rig = new Rig();
        rig.finishStartupCycle();
        assertTrue(rig.lights.openSession(7));
        assertTrue(rig.lights.push(new int[]{0xFFFF4081}));
        rig.lights.closeSession();
        rig.resetObservations();

        Engine engine = rig.newEngine();
        engine.setState("{\"enabled\":false}");
        rig.driveToTerminal(engine, 10_000);

        assertEquals(Arrays.asList(
                LightsBackend.CLEANUP_PRIORITY,
                LightsBackend.CLEANUP_PRIORITY,
                LightsBackend.CLEANUP_PRIORITY
        ), rig.io.priorities);
        assertEquals(
                Arrays.asList(
                        ForcedBlackClear.RETRY_COLOR, ForcedBlackClear.CANONICAL_COLOR,
                        ForcedBlackClear.RETRY_COLOR, ForcedBlackClear.CANONICAL_COLOR,
                        ForcedBlackClear.RETRY_COLOR, ForcedBlackClear.CANONICAL_COLOR
                ),
                rig.io.firstColors
        );
        assertEquals(3, rig.io.closes);
        assertFalse(rig.lights.isBlackClearPending());
    }

    @Test
    public void stopRunsThreeAutomaticPassesBeforeItsReservedRetry() {
        Rig rig = new Rig();
        rig.finishStartupCycle();
        assertTrue(rig.lights.openSession(7));
        assertTrue(rig.lights.push(new int[]{0xFFFF4081}));
        rig.lights.closeSession();
        rig.resetObservations();
        rig.clock.autoAdvanceMs = LightsBackend.CLEANUP_RETRY_MS;

        rig.newEngine().stop();

        assertEquals(Arrays.asList(
                LightsBackend.CLEANUP_PRIORITY,
                LightsBackend.CLEANUP_PRIORITY,
                LightsBackend.CLEANUP_PRIORITY,
                LightsBackend.CLEANUP_PRIORITY
        ), rig.io.priorities);
        assertEquals(8, rig.io.firstColors.size());
        for (int color : rig.io.firstColors) assertEquals(0, color & 0x00FFFFFF);
        assertEquals(4, rig.io.closes);
        assertEquals(4, rig.lights.clearAttemptsUsed());
        assertFalse(rig.lights.isBlackClearPending());
    }

    @Test
    public void deferredBlankClearIsRetriedOnALaterIdleTick() {
        Rig rig = new Rig();
        rig.finishStartupCycle();
        assertTrue(rig.lights.openSession(7));
        assertTrue(rig.lights.push(new int[]{0xFFFF4081}));
        rig.lights.closeSession();
        rig.resetObservations();

        Engine engine = rig.newEngine();
        engine.setState("{\"enabled\":true,\"ambient\":{\"mode\":\"off\"}}");
        rig.tick(engine, 10_000, 10_000);              // first automatic borrow
        rig.resetObservations();
        rig.clock.now += 600;
        rig.tick(engine, 10_033, 10_033);              // still inside the retry floor
        assertTrue(rig.io.priorities.isEmpty());
        assertTrue(rig.lights.isBlackClearPending());

        rig.clock.now += 400;
        rig.tick(engine, 10_066, 10_066);              // IDLE drives the owed retry

        assertEquals(Arrays.asList(LightsBackend.CLEANUP_PRIORITY), rig.io.priorities);
        assertEquals(
                Arrays.asList(ForcedBlackClear.RETRY_COLOR, ForcedBlackClear.CANONICAL_COLOR),
                rig.io.firstColors
        );
        assertTrue(rig.lights.isBlackClearPending());
        rig.clock.now += LightsBackend.CLEANUP_RETRY_MS;
        rig.tick(engine, 10_099, 10_099);
        assertFalse(rig.lights.isBlackClearPending());
    }

    @Test
    public void failedBlankClearIsRetriedWhenWritesRecover() {
        Rig rig = new Rig();
        rig.finishStartupCycle();
        assertTrue(rig.lights.openSession(7));
        assertTrue(rig.lights.push(new int[]{0xFFFF4081}));
        rig.lights.closeSession();
        rig.resetObservations();
        rig.io.acceptWrites = false;

        Engine engine = rig.newEngine();
        engine.setState("{\"enabled\":true,\"ambient\":{\"mode\":\"off\"}}");
        rig.tick(engine, 10_000, 10_000);              // BLANK attempt fails
        assertTrue(rig.lights.isBlackClearPending());

        rig.io.acceptWrites = true;
        rig.clock.now += LightsBackend.CLEANUP_RETRY_MS;
        rig.tick(engine, 10_033, 10_033);              // IDLE retries after the floor
        rig.clock.now += LightsBackend.CLEANUP_RETRY_MS;
        rig.tick(engine, 10_066, 10_066);              // final bounded automatic pass

        assertEquals(
                Arrays.asList(
                        ForcedBlackClear.RETRY_COLOR,
                        ForcedBlackClear.RETRY_COLOR, ForcedBlackClear.CANONICAL_COLOR,
                        ForcedBlackClear.RETRY_COLOR, ForcedBlackClear.CANONICAL_COLOR
                ),
                rig.io.firstColors
        );
        assertFalse(rig.lights.isBlackClearPending());
        assertFalse(rig.lights.isSessionOpen());
    }

    @Test
    public void blankTransitionBorrowsAgainAfterReleasingTheOriginalSession() {
        Rig rig = new Rig();
        rig.finishStartupCycle();
        assertTrue(rig.lights.openSession(7));
        assertTrue(rig.lights.push(new int[]{0xFFFF4081}));
        rig.resetObservations();

        Engine engine = rig.newEngine();
        engine.setState("{\"enabled\":true,\"ambient\":{\"mode\":\"off\"}}");
        rig.tick(engine, 10_000, 10_000);
        rig.clock.now += LightsBackend.CLEANUP_RETRY_MS;
        rig.tick(engine, 10_033, 10_033);
        rig.clock.now += LightsBackend.CLEANUP_RETRY_MS;
        rig.tick(engine, 10_066, 10_066);

        assertEquals(Arrays.asList(
                LightsBackend.CLEANUP_PRIORITY,
                LightsBackend.CLEANUP_PRIORITY,
                LightsBackend.CLEANUP_PRIORITY
        ), rig.io.priorities);
        assertEquals(
                Arrays.asList(
                        ForcedBlackClear.RETRY_COLOR,
                        ForcedBlackClear.CANONICAL_COLOR,
                        ForcedBlackClear.RETRY_COLOR,
                        ForcedBlackClear.CANONICAL_COLOR,
                        ForcedBlackClear.RETRY_COLOR,
                        ForcedBlackClear.CANONICAL_COLOR,
                        ForcedBlackClear.RETRY_COLOR,
                        ForcedBlackClear.CANONICAL_COLOR
                ),
                rig.io.firstColors
        );
        assertEquals(4, rig.io.closes); // original session, then three borrowed cleanup sessions
        assertFalse(rig.lights.isSessionOpen());
        assertFalse(rig.lights.isBlackClearPending());
        assertEquals(0, rig.lights.cleanupBorrowsRemaining());
    }

    @Test
    public void lowBrightnessBreatheTroughsPreserveOneCycleUntilTerminalOff()
            throws Exception {
        Rig rig = new Rig();
        rig.finishStartupCycle();
        Engine engine = rig.newEngine();
        long base = 10_000;
        rig.rendererClock.now = base;
        engine.setState("{\"enabled\":true,\"arm\":true,\"stateRevision\":77,"
                + "\"ambientTimeoutMs\":300000,\"ambient\":{\"mode\":\"breathe\","
                + "\"color\":4294967295,\"brightness\":0.1,\"speedMs\":5000}}");
        long cycle = -1;

        for (int i = 0; i < 5; i++) {
            long peak = base + i * 5_000 + 2_500;
            rig.tick(engine, peak, peak);
            assertTrue(rig.lights.isSessionOpen());
            if (cycle < 0) cycle = rig.lights.clearCycleId();
            assertEquals(cycle, rig.lights.clearCycleId());

            long trough = base + (i + 1) * 5_000;
            rig.tick(engine, trough, trough);
            assertFalse(rig.lights.isSessionOpen());
            assertEquals(cycle, rig.lights.clearCycleId());
            assertEquals(0, rig.lights.clearAttemptsUsed());
            assertEquals(LightsBackend.MAX_CLEANUP_BORROWS,
                    rig.lights.cleanupBorrowsRemaining());
            assertTrue(rig.lights.isBlackClearPending());

            int writesAfterRelease = rig.io.firstColors.size();
            int closesAfterRelease = rig.io.closes;
            rig.tick(engine, trough + 33, trough + 33);
            assertEquals(writesAfterRelease, rig.io.firstColors.size());
            assertEquals(closesAfterRelease, rig.io.closes);
        }

        // Every open above is the normal renderer priority. A trough must never borrow cleanup.
        assertEquals(5, rig.io.priorities.size());
        for (int priority : rig.io.priorities) assertEquals(0, priority);
        JSONObject transientStatus = new JSONObject(engine.status());
        assertEquals(77, transientStatus.getLong("settledStateRevision"));
        assertEquals(0, transientStatus.getLong("releasedStateRevision"));

        rig.resetObservations();
        engine.setState("{\"enabled\":false,\"stateRevision\":78}");
        rig.driveToTerminal(engine, base + 30_000);

        assertEquals(Arrays.asList(
                LightsBackend.CLEANUP_PRIORITY,
                LightsBackend.CLEANUP_PRIORITY,
                LightsBackend.CLEANUP_PRIORITY
        ), rig.io.priorities);
        assertEquals(Arrays.asList(
                ForcedBlackClear.RETRY_COLOR, ForcedBlackClear.CANONICAL_COLOR,
                ForcedBlackClear.RETRY_COLOR, ForcedBlackClear.CANONICAL_COLOR,
                ForcedBlackClear.RETRY_COLOR, ForcedBlackClear.CANONICAL_COLOR
        ), rig.io.firstColors);
        assertEquals(3, rig.io.closes);
        assertEquals(cycle, rig.lights.clearCycleId());
        assertEquals(3, rig.lights.clearAttemptsUsed());
        assertFalse(rig.lights.isSessionOpen());
        assertTrue(rig.lights.isBlackClearTerminal());
        assertEquals(78, new JSONObject(engine.status()).getLong("releasedStateRevision"));
    }

    @Test
    public void enabledStateWithoutAmbientCannotPreserveAnOlderVisibleSession()
            throws Exception {
        Rig rig = new Rig();
        rig.finishStartupCycle();
        assertTrue(rig.lights.openSession(7));
        assertTrue(rig.lights.push(new int[]{0xFFFF4081}));
        rig.resetObservations();
        Engine engine = rig.newEngine();

        engine.setState("{\"enabled\":true,\"arm\":true,\"stateRevision\":79}");
        rig.driveToTerminal(engine, System.currentTimeMillis());

        assertEquals(Arrays.asList(
                LightsBackend.CLEANUP_PRIORITY,
                LightsBackend.CLEANUP_PRIORITY,
                LightsBackend.CLEANUP_PRIORITY
        ), rig.io.priorities);
        assertFalse(rig.lights.isSessionOpen());
        assertTrue(rig.lights.isBlackClearTerminal());
        assertEquals(79, new JSONObject(engine.status()).getLong("releasedStateRevision"));
    }

    @Test
    public void newlyAppliedAlwaysDarkAnimationGetsTerminalRecoveryInsteadOfTransientClose()
            throws Exception {
        Rig rig = new Rig();
        rig.finishStartupCycle();
        Engine engine = rig.newEngine();
        long now = 100_000;
        rig.rendererClock.now = now;

        engine.setState("{\"enabled\":true,\"arm\":true,\"stateRevision\":91,"
                + "\"ambient\":{\"mode\":\"solid\",\"color\":4294967295}}");
        rig.tick(engine, now, now);
        assertTrue(rig.lights.isSessionOpen());
        long visibleCycle = rig.lights.clearCycleId();
        rig.resetObservations();

        // Red channel 13 at the renderer, then the engine's 2% dim rounds it to RGB zero even at
        // the Breathe peak. This state generation can therefore never produce a visible frame.
        engine.setState("{\"enabled\":true,\"arm\":true,\"stateRevision\":92,"
                + "\"dim\":0.02,\"ambient\":{\"mode\":\"breathe\","
                + "\"color\":851968,\"brightness\":1.0,\"speedMs\":5000}}");
        rig.driveToTerminal(engine, now + 2_500);

        assertEquals(Arrays.asList(
                LightsBackend.CLEANUP_PRIORITY,
                LightsBackend.CLEANUP_PRIORITY,
                LightsBackend.CLEANUP_PRIORITY
        ), rig.io.priorities);
        assertEquals(3, rig.lights.clearAttemptsUsed());
        assertEquals(visibleCycle, rig.lights.clearCycleId());
        assertFalse(rig.lights.isSessionOpen());
        assertTrue(rig.lights.isBlackClearTerminal());
        assertEquals(92, new JSONObject(engine.status()).getLong("releasedStateRevision"));

        rig.resetObservations();
        rig.tick(engine, now + 5_000, now + 5_000);
        assertTrue(rig.io.priorities.isEmpty());
        assertTrue(rig.io.firstColors.isEmpty());
    }

    @Test
    public void alwaysDarkAnimationFromStartIsTerminalNotTransient() throws Exception {
        Rig rig = new Rig();
        rig.finishStartupCycle();
        Engine engine = rig.newEngine();
        engine.setState("{\"enabled\":true,\"arm\":true,\"stateRevision\":94,"
                + "\"dim\":0.02,\"ambient\":{\"mode\":\"breathe\","
                + "\"color\":851968,\"brightness\":1.0,\"speedMs\":5000}}");

        rig.tick(engine, System.currentTimeMillis(), 10_000);

        assertFalse(rig.lights.isSessionOpen());
        assertTrue(rig.lights.isBlackClearTerminal());
        assertEquals(94, new JSONObject(engine.status()).getLong("releasedStateRevision"));
    }

    @Test
    public void visibleAlertFallingBackToDarkAnimatedAmbientGetsTerminalRecovery()
            throws Exception {
        Rig rig = new Rig();
        rig.finishStartupCycle();
        Engine engine = rig.newEngine();
        long now = 100_000;
        rig.rendererClock.now = now;
        engine.setState("{\"enabled\":true,\"arm\":true,\"stateRevision\":93,"
                + "\"ambientTimeoutMs\":300000,\"dim\":0.02,"
                + "\"ambient\":{\"mode\":\"breathe\",\"color\":851968,"
                + "\"brightness\":1.0,\"speedMs\":5000},"
                + "\"alert\":{\"id\":1,\"durationMs\":1000,\"pattern\":\"solid\","
                + "\"color\":4294967295,\"brightness\":1.0}}");

        rig.tick(engine, now, now);
        assertTrue(rig.lights.isSessionOpen());
        long alertCycle = rig.lights.clearCycleId();
        rig.resetObservations();

        // The alert and ambient belong to the same state generation, but not the same output cfg.
        // Expiry must therefore run terminal recovery instead of inheriting alert trough semantics.
        rig.tick(engine, now + 1_500, now + 1_500);
        rig.driveToTerminal(engine, now + 1_533);

        assertEquals(Arrays.asList(
                LightsBackend.CLEANUP_PRIORITY,
                LightsBackend.CLEANUP_PRIORITY,
                LightsBackend.CLEANUP_PRIORITY
        ), rig.io.priorities);
        assertEquals(alertCycle, rig.lights.clearCycleId());
        assertEquals(3, rig.lights.clearAttemptsUsed());
        assertFalse(rig.lights.isSessionOpen());
        assertTrue(rig.lights.isBlackClearTerminal());
        assertEquals(93, new JSONObject(engine.status()).getLong("releasedStateRevision"));
    }

    @Test
    public void visibleStateInterruptingTerminalCleanupReceivesAFreshFullCycle()
            throws Exception {
        Rig rig = new Rig();
        rig.finishStartupCycle();
        Engine engine = rig.newEngine();
        long now = 100_000;
        rig.rendererClock.now = now;

        engine.setState("{\"enabled\":true,\"arm\":true,\"stateRevision\":81,"
                + "\"ambient\":{\"mode\":\"solid\",\"color\":4294967295}}");
        rig.tick(engine, now, now);
        long firstVisibleCycle = rig.lights.clearCycleId();
        assertTrue(rig.lights.isSessionOpen());

        engine.setState("{\"enabled\":false,\"stateRevision\":82}");
        rig.tick(engine, now + 33, now + 33);
        assertFalse(rig.lights.isSessionOpen());
        assertEquals(1, rig.lights.clearAttemptsUsed());
        assertEquals(2, rig.lights.cleanupBorrowsRemaining());

        engine.setState("{\"enabled\":true,\"arm\":true,\"stateRevision\":83,"
                + "\"ambient\":{\"mode\":\"solid\",\"color\":4294967295}}");
        rig.tick(engine, now + 66, now + 66);

        assertTrue(rig.lights.isSessionOpen());
        assertEquals(firstVisibleCycle + 1, rig.lights.clearCycleId());
        assertEquals(0, rig.lights.clearAttemptsUsed());
        assertEquals(LightsBackend.MAX_CLEANUP_BORROWS,
                rig.lights.cleanupBorrowsRemaining());

        rig.resetObservations();
        engine.setState("{\"enabled\":false,\"stateRevision\":84}");
        rig.driveToTerminal(engine, now + 99);
        assertEquals(Arrays.asList(
                LightsBackend.CLEANUP_PRIORITY,
                LightsBackend.CLEANUP_PRIORITY,
                LightsBackend.CLEANUP_PRIORITY
        ), rig.io.priorities);
        assertEquals(3, rig.lights.clearAttemptsUsed());
        assertEquals(84, new JSONObject(engine.status()).getLong("releasedStateRevision"));
    }

    @Test
    public void backwardWallClockCannotExtendAlertOrAmbientDeadlines() throws Exception {
        Rig rig = new Rig();
        rig.finishStartupCycle();
        Engine engine = rig.newEngine();
        long armedAt = 50_000;
        rig.rendererClock.now = armedAt;
        engine.setState("{\"enabled\":true,\"arm\":true,\"ambientTimeoutMs\":5000,"
                + "\"ambient\":{\"mode\":\"solid\",\"color\":4278190335},"
                + "\"alert\":{\"id\":101,\"durationMs\":1000,"
                + "\"pattern\":\"solid\",\"color\":4294901760}}");

        rig.tick(engine, 1_000_000_000, armedAt);
        assertEquals((int) 0xFFFF0000L, rig.io.lastColor());

        // The epoch clock moves backwards by over eleven days. Elapsed realtime still reaches the
        // alert deadline, so the ambient frame must take over instead of extending the alert.
        rig.tick(engine, 0, armedAt + 1_000);
        assertEquals((int) 0xFF0000FFL, rig.io.lastColor());
        assertTrue(rig.lights.isSessionOpen());

        // The same rollback cannot extend the ambient window either.
        rig.tick(engine, -1_000_000_000, armedAt + 5_000);
        assertFalse(rig.lights.isSessionOpen());
        JSONObject status = new JSONObject(engine.status());
        assertEquals(0, status.getLong("ambientRemainingMs"));
        assertTrue(status.getBoolean("ambientHeld"));
    }

    @Test
    public void backwardWallClockCannotFreezeAmbientAnimationPhase() {
        Rig rig = new Rig();
        rig.finishStartupCycle();
        Engine engine = rig.newEngine();
        long armedAt = 20_000;
        rig.rendererClock.now = armedAt;
        engine.setState("{\"enabled\":true,\"arm\":true,\"ambientTimeoutMs\":5000,"
                + "\"ambient\":{\"mode\":\"blink\",\"speedMs\":1000,"
                + "\"color\":4294967295}}");

        rig.tick(engine, 1_000_000_000, armedAt);
        assertTrue(rig.lights.isSessionOpen());

        // Half a monotonic cycle later the blink is dark even though epoch time moved backwards.
        rig.tick(engine, 0, armedAt + 500);
        assertFalse(rig.lights.isSessionOpen());
        assertEquals(0, rig.lights.clearAttemptsUsed());

        rig.tick(engine, -1_000_000_000, armedAt + 1_000);
        assertTrue(rig.lights.isSessionOpen());
    }

    @Test
    public void backwardWallClockCannotExtendSafetyRestWindow() throws Exception {
        Rig rig = new Rig();
        rig.finishStartupCycle();
        SafetyGuard shortWindow = new SafetyGuard(100, 0.5, 1_000, 20, 0.5);
        Engine engine = new Engine(rig.lights, rig.rendererClock, shortWindow);
        long armedAt = 1_000;
        rig.rendererClock.now = armedAt;
        engine.setState("{\"enabled\":true,\"arm\":true,\"ambientTimeoutMs\":1000,"
                + "\"ambient\":{\"mode\":\"solid\",\"color\":4294901760}}");

        for (int i = 0; i <= 5; i++) {
            rig.tick(engine, 10_000 - i * 1_000L, armedAt + i * 10L);
        }
        assertTrue(new JSONObject(engine.status()).getBoolean("resting"));
        assertFalse(rig.lights.isSessionOpen());

        // Re-arm at the monotonic duty-window boundary while epoch time continues backwards. The
        // safety guard must leave rest and permit output; wall time must not pin it indefinitely.
        rig.rendererClock.now = armedAt + 100;
        engine.setState("{\"enabled\":true,\"arm\":true,\"ambientTimeoutMs\":1000,"
                + "\"ambient\":{\"mode\":\"solid\",\"color\":4278255360}}");
        rig.tick(engine, -10_000, armedAt + 100);

        assertFalse(new JSONObject(engine.status()).getBoolean("resting"));
        assertTrue(rig.lights.isSessionOpen());
        assertEquals((int) 0xFF00FF00L, rig.io.lastColor());
    }

    private static final class Rig {
        final FakeIo io = new FakeIo();
        final FakeClock clock = new FakeClock();
        final FakeElapsedRealtimeClock rendererClock = new FakeElapsedRealtimeClock();
        final FakeSleeper sleeper = new FakeSleeper();
        final LightsBackend lights = new LightsBackend(io, LEDS, 33, clock, sleeper);

        Engine newEngine() {
            return new Engine(lights, rendererClock);
        }

        void tick(Engine engine, long wallTimeMs, long elapsedRealtimeMs) {
            rendererClock.now = elapsedRealtimeMs;
            engine.tickForTest(wallTimeMs, elapsedRealtimeMs);
        }

        void finishStartupCycle() {
            for (int i = 0; i < LightsBackend.MAX_CLEANUP_BORROWS; i++) {
                if (i > 0) clock.now += LightsBackend.CLEANUP_RETRY_MS;
                lights.forceBlack();
            }
            assertTrue(lights.isBlackClearTerminal());
            resetObservations();
        }

        void driveToTerminal(Engine engine, long wallNow) {
            for (int i = 0; i < LightsBackend.MAX_CLEANUP_BORROWS + 2; i++) {
                tick(engine, wallNow + i * 33, wallNow + i * 33);
                if (lights.isBlackClearTerminal() && !lights.isSessionOpen()) return;
                clock.now += LightsBackend.CLEANUP_RETRY_MS;
            }
            throw new AssertionError("cleanup did not reach a closed terminal state");
        }

        void resetObservations() {
            io.priorities.clear();
            io.firstColors.clear();
            io.closes = 0;
        }
    }

    private static final class FakeElapsedRealtimeClock implements Engine.ElapsedRealtimeClock {
        long now;

        @Override
        public long nowMs() {
            return now;
        }
    }

    private static final class FakeSleeper implements ForcedBlackClear.Sleeper {
        boolean throwRuntimeOnce;
        boolean blockOnce;
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);

        @Override
        public void sleep(long millis) throws InterruptedException {
            if (blockOnce) {
                blockOnce = false;
                entered.countDown();
                if (!release.await(2, TimeUnit.SECONDS)) {
                    throw new RuntimeException("startup test timed out");
                }
            }
            if (!throwRuntimeOnce) return;
            throwRuntimeOnce = false;
            throw new RuntimeException("startup stimulus failed");
        }
    }

    private static final class FakeClock implements LightsBackend.Clock {
        long now;
        long autoAdvanceMs;

        @Override
        public long nowMs() {
            long current = now;
            now += autoAdvanceMs;
            return current;
        }
    }

    private static final class FakeIo implements LightsBackend.SessionIo {
        int closes;
        boolean acceptWrites = true;
        boolean acceptClose = true;
        final List<Integer> priorities = new ArrayList<>();
        final List<Integer> firstColors = new ArrayList<>();

        @Override
        public boolean open(int priority) {
            priorities.add(priority);
            return true;
        }

        @Override
        public boolean close() {
            closes++;
            return acceptClose;
        }

        @Override
        public boolean set(int[] ids, int[] colors) {
            firstColors.add(colors[0]);
            return acceptWrites;
        }

        int lastColor() {
            if (firstColors.isEmpty()) throw new AssertionError("no LED frame was written");
            return firstColors.get(firstColors.size() - 1);
        }
    }
}
