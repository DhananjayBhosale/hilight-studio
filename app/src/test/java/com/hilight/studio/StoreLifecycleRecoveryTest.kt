package com.hilight.studio

import com.hilight.core.RendererContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StoreLifecycleRecoveryTest {

    @Test
    fun `release fence covers source cleanup stop and destination cleanup but stays bounded`() {
        val requiredMs = 3_000L + 4_000L + 3_000L
        assertTrue(Store.RELEASE_FENCE_TIMEOUT_MS >= requiredMs)
        assertTrue(Store.RELEASE_FENCE_TIMEOUT_MS <= 30_000L)
    }

    @Test
    fun `exact exit must be followed by destination cleanup proof before replay`() {
        assertEquals(
            HandoffReplayStage.WAIT_FOR_SOURCE_EXIT,
            handoffReplayStage(false, false, false),
        )
        assertEquals(
            HandoffReplayStage.RUN_DESTINATION_CLEANUP,
            handoffReplayStage(true, false, false),
        )
        assertEquals(
            HandoffReplayStage.RUN_DESTINATION_CLEANUP,
            handoffReplayStage(true, true, false),
        )
        assertEquals(
            HandoffReplayStage.REPLAY,
            handoffReplayStage(true, true, true),
        )

        val cleanup = currentStatus(
            receivedStateRevision = 44,
            settledStateRevision = 44,
            releasedStateRevision = 44,
            lastSeenManualBlackClearRequestId = 91,
            lastAcceptedManualBlackClearRequestId = 91,
        )
        assertTrue(cleanup.provesPostExitCleanup(44, 91))
        assertFalse(cleanup.copy(lastAcceptedManualBlackClearRequestId = 0)
            .provesPostExitCleanup(44, 91))
        assertFalse(cleanup.copy(releasedStateRevision = 43)
            .provesPostExitCleanup(44, 91))
    }

    @Test
    fun `fatal adb fence waits without authority then uses newly available authority`() {
        assertEquals(
            FatalTerminationAuthority.NONE,
            fatalTerminationAuthority(Transport.ADB, false, false),
        )
        assertEquals(
            FatalTerminationAuthority.SHIZUKU,
            fatalTerminationAuthority(Transport.ADB, true, false),
        )
        assertEquals(
            FatalTerminationAuthority.ROOT,
            fatalTerminationAuthority(Transport.ADB, false, true),
        )
    }

    @Test
    fun `expired exact bridge identity routes through root validated stop`() {
        val expired = currentStatus(
            alive = false,
            owner = "adb",
            pid = 4321,
            rendererInstanceId = "adb-expired",
        )

        assertTrue(shouldUseRootValidatedStopPath(Transport.ADB, true, expired))
        assertFalse(shouldUseRootValidatedStopPath(Transport.ADB, false, expired))
        assertFalse(shouldUseRootValidatedStopPath(
            Transport.SHIZUKU,
            true,
            expired.copy(owner = "shizuku"),
        ))
    }

    @Test
    fun `fresh bridge instance triggers one unarmed store repush decision`() {
        val fresh = currentStatus(
            owner = "adb",
            pid = 4321,
            rendererInstanceId = "adb-new",
        )

        assertTrue(shouldRepushForBridgeInstance(Transport.ADB, fresh, "adb-old", false))
        assertFalse(shouldRepushForBridgeInstance(Transport.ADB, fresh, "adb-new", false))
        val root = fresh.copy(owner = "root", uid = 0, rendererInstanceId = "same-id")
        assertTrue(shouldRepushForBridgeInstance(
            Transport.ROOT,
            root,
            "same-id",
            false,
            lastTargetedTransport = Transport.ADB,
        ))
        assertFalse(shouldRepushForBridgeInstance(Transport.ADB, fresh, "adb-old", true))
        assertFalse(shouldRepushForBridgeInstance(
            Transport.ADB,
            fresh.copy(identityResolved = false),
            "adb-old",
            false,
        ))
    }

    @Test
    fun `visible bridge target rejects torn and expired current identity`() {
        val current = currentStatus()
        assertTrue(isSafeBridgeVisibleTarget(Transport.ADB, current))
        assertFalse(isSafeBridgeVisibleTarget(
            Transport.ADB,
            current.copy(identityResolved = false),
        ))
        assertFalse(isSafeBridgeVisibleTarget(Transport.ADB, current.copy(alive = false)))
        assertFalse(isSafeBridgeVisibleTarget(
            Transport.ADB,
            current.copy(blackClearUnreleasedFatal = true),
        ))
    }

    @Test
    fun `root push sanitizes visible state without a fresh exact root identity`() {
        val visible = """{"stateRevision":72,"enabled":true}"""
        val root = currentStatus(
            owner = "root",
            uid = 0,
            rendererInstanceId = "root-current",
        )
        assertEquals(AdbPushSafety.TARGETED_CURRENT, safeRootPush(visible, root).safety)
        assertEquals(
            AdbPushSafety.SANITIZED_DISABLED,
            safeRootPush(visible, root.copy(identityResolved = false)).safety,
        )
        assertEquals(
            AdbPushSafety.SANITIZED_DISABLED,
            safeRootPush(visible, root.copy(alive = false)).safety,
        )
    }

    @Test
    fun `root authority stays available after external adb stop failure`() {
        assertEquals(
            RootBackend.State.AVAILABLE,
            rootStateAfterExactStopFailure("adb", RootBackend.State.AVAILABLE),
        )
        assertEquals(
            RootBackend.State.ERROR,
            rootStateAfterExactStopFailure("root", RootBackend.State.RUNNING),
        )
    }

    @Test
    fun `confirmed shizuku exit clears only matching lifecycle ownership`() {
        assertTrue(confirmedShizukuExitAffectsLifecycle(Transport.SHIZUKU, null))
        assertTrue(confirmedShizukuExitAffectsLifecycle(null, Transport.SHIZUKU))
        assertFalse(confirmedShizukuExitAffectsLifecycle(Transport.ADB, Transport.ROOT))
        assertTrue(confirmedShizukuExitMatches(12L, 12L))
        assertFalse(confirmedShizukuExitMatches(12L, 11L))
        assertFalse(confirmedShizukuExitMatches(null, 12L))
        assertFalse(confirmedShizukuExitMatches(0L, 0L))
    }

    @Test
    fun `incompatible shizuku exit restores an underlying adb or fatal lifecycle`() {
        assertTrue(shouldRestoreLifecycleAfterIncompatibleShizukuExit(
            Transport.ADB,
            null,
            false,
        ))
        assertTrue(shouldRestoreLifecycleAfterIncompatibleShizukuExit(
            null,
            null,
            false,
            suspendedDriving = Transport.ADB,
        ))
        assertTrue(shouldRestoreLifecycleAfterIncompatibleShizukuExit(
            null,
            "adb",
            false,
        ))
        assertTrue(shouldRestoreLifecycleAfterIncompatibleShizukuExit(
            Transport.ADB,
            "adb",
            true,
        ))
        assertFalse(shouldRestoreLifecycleAfterIncompatibleShizukuExit(
            Transport.SHIZUKU,
            null,
            false,
        ))
    }

    @Test
    fun `direct shizuku disconnect always chooses a different fallback`() {
        assertEquals(Transport.ADB, directShizukuDisconnectFallback(false))
        assertEquals(Transport.ROOT, directShizukuDisconnectFallback(true))
    }

    @Test
    fun `exact source exit advances once per lifecycle generation`() {
        assertTrue(shouldAcceptExactExit(11, 10))
        assertFalse(shouldAcceptExactExit(11, 11))
    }

    @Test
    fun `root takeover confirms exit then runs destination cleanup before routing`() {
        assertEquals(
            RootStartCompletion.CONFIRM_SOURCE_EXIT,
            rootStartCompletion(
                completingConfirmedHandoff = false,
                sourcePresent = true,
                sourceExitAlreadyConfirmed = false,
                destinationStarted = true,
            ),
        )
        assertEquals(
            RootStartCompletion.RESUME_DESTINATION_CLEANUP,
            rootStartCompletion(
                completingConfirmedHandoff = true,
                sourcePresent = false,
                sourceExitAlreadyConfirmed = true,
                destinationStarted = true,
            ),
        )
        assertEquals(
            RootStartCompletion.WAIT_FOR_DESTINATION,
            rootStartCompletion(
                completingConfirmedHandoff = true,
                sourcePresent = false,
                sourceExitAlreadyConfirmed = true,
                destinationStarted = false,
            ),
        )
        assertEquals(
            RootStartCompletion.RETAIN_SOURCE_FENCE,
            rootStartCompletion(
                completingConfirmedHandoff = false,
                sourcePresent = true,
                sourceExitAlreadyConfirmed = false,
                destinationStarted = false,
            ),
        )
        assertTrue(shouldKeepRootDestinationAfterExactExit(
            requestedDestination = Transport.ROOT,
            fencedDestination = null,
            rootReadyOrAvailable = true,
        ))
        assertTrue(shouldKeepRootDestinationAfterExactExit(
            requestedDestination = Transport.ADB,
            fencedDestination = Transport.ROOT,
            rootReadyOrAvailable = true,
        ))
        assertFalse(shouldKeepRootDestinationAfterExactExit(
            requestedDestination = Transport.ROOT,
            fencedDestination = Transport.ROOT,
            rootReadyOrAvailable = false,
        ))
    }

    @Test
    fun `cold unresolved bridge sample retries three times then yields boundedly`() {
        val unresolved = currentStatus(identityResolved = false)
        assertTrue(
            (Store.COLD_BRIDGE_DISCOVERY_SAMPLES - 1) *
                Store.COLD_BRIDGE_DISCOVERY_INTERVAL_MS >= 250L,
        )
        assertTrue(shouldRetryColdBridgeDiscovery(unresolved, 1, 3))
        assertTrue(shouldRetryColdBridgeDiscovery(unresolved, 2, 3))
        assertFalse(shouldRetryColdBridgeDiscovery(unresolved, 3, 3))
        assertFalse(shouldRetryColdBridgeDiscovery(
            unresolved.copy(identityResolved = true),
            1,
            3,
        ))
    }

    @Test
    fun `header refuses stale fatal unresolved or lifecycle fenced renderer`() {
        val ready = currentStatus()
        assertTrue(rendererConnectedForUi(ready, false))
        assertFalse(rendererConnectedForUi(ready.copy(identityResolved = false), false))
        assertFalse(rendererConnectedForUi(ready.copy(alive = false), false))
        assertFalse(rendererConnectedForUi(ready.copy(blackClearUnreleasedFatal = true), false))
        assertFalse(rendererConnectedForUi(ready, true))
    }

    private fun currentStatus(
        alive: Boolean = true,
        identityResolved: Boolean = true,
        owner: String = "adb",
        uid: Int = 2000,
        pid: Int = 100,
        rendererInstanceId: String = "adb-current",
        receivedStateRevision: Long = 0,
        settledStateRevision: Long = 0,
        releasedStateRevision: Long = 0,
        lastSeenManualBlackClearRequestId: Long = 0,
        lastAcceptedManualBlackClearRequestId: Long = 0,
    ) = HelperStatus(
        alive = alive,
        identityResolved = identityResolved,
        owner = owner,
        uid = uid,
        pid = pid,
        rendererInstanceId = rendererInstanceId,
        rendererReady = true,
        ledCount = 8,
        rendererContractVersion = RendererContract.CONTRACT_VERSION,
        rendererImplementationRevision = RendererContract.IMPLEMENTATION_REVISION,
        rendererStatusSchemaVersion = RendererContract.STATUS_SCHEMA_VERSION,
        rendererClearAlgorithmVersion = RendererContract.CLEAR_ALGORITHM_VERSION,
        blackClearTerminal = true,
        receivedStateRevision = receivedStateRevision,
        settledStateRevision = settledStateRevision,
        releasedStateRevision = releasedStateRevision,
        lastSeenManualBlackClearRequestId = lastSeenManualBlackClearRequestId,
        lastAcceptedManualBlackClearRequestId = lastAcceptedManualBlackClearRequestId,
    )
}
