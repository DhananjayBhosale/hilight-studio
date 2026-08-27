package com.hilight.studio

import com.hilight.core.RendererContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RendererSettlementTest {

    @Test
    fun `receipt is not renderer release proof`() {
        val received = currentStatus(
            appliedStateRevision = 71,
            receivedStateRevision = 71,
            settledStateRevision = 0,
            releasedStateRevision = 0,
        )

        assertFalse(received.provesReleasedRevision(71))
    }

    @Test
    fun `dead renderer is not accepted as release proof`() {
        val vanished = currentStatus(
            alive = false,
            releasedStateRevision = 72,
        )

        assertFalse(vanished.provesReleasedRevision(72))
    }

    @Test
    fun `legacy adb upgrade requires exact idle receipt and closed session`() {
        val legacy = currentStatus(
            owner = "adb",
            rendererImplementationRevision = 0,
            appliedStateRevision = 72,
            receivedStateRevision = 72,
        )

        assertTrue(legacy.provesLegacyIdleReceipt(72))
        assertFalse(legacy.copy(sessionOpen = true).provesLegacyIdleReceipt(72))
        assertFalse(legacy.copy(owner = "root").provesLegacyIdleReceipt(72))
        assertFalse(legacy.copy(rendererInstanceId = "new-helper").provesLegacyIdleReceipt(72))
        assertFalse(legacy.copy(receivedStateRevision = 71).provesLegacyIdleReceipt(72))
    }

    @Test
    fun `release requires exact revision current renderer and no outstanding ownership`() {
        val released = currentStatus(
            appliedStateRevision = 73,
            receivedStateRevision = 73,
            settledStateRevision = 73,
            releasedStateRevision = 73,
            rendererInstanceId = "adb-73",
        )

        assertTrue(released.provesReleasedRevision(73, "adb-73"))
        assertFalse(released.copy(sessionOpen = true).provesReleasedRevision(73))
        assertFalse(released.copy(blackClearPending = true).provesReleasedRevision(73))
        assertFalse(released.copy(blackClearUnreleasedFatal = true).provesReleasedRevision(73))
        assertFalse(released.copy(privacyObserverEnabled = true).provesReleasedRevision(73))
        assertFalse(released.copy(receivedStateRevision = 72).provesReleasedRevision(73))
        assertFalse(released.copy(settledStateRevision = 72).provesReleasedRevision(73))
        assertFalse(released.copy(releasedStateRevision = 72).provesReleasedRevision(73))
        assertFalse(released.provesReleasedRevision(73, "different-instance"))
        assertFalse(
            released.copy(rendererImplementationRevision = 0).provesReleasedRevision(73),
        )
    }

    @Test
    fun `cold start fences a different live bridge before shizuku output`() {
        val adb = currentStatus(owner = "adb", uid = 2000, pid = 4321)

        assertEquals(
            Transport.ADB,
            rendererToFenceOnColdStart(Transport.SHIZUKU, adb, null),
        )
        assertEquals(
            Transport.ADB,
            rendererToFenceOnColdStart(
                Transport.SHIZUKU,
                adb.copy(rendererImplementationRevision = 0),
                null,
            ),
        )
    }

    @Test
    fun `cold start fences live shizuku before bridge output`() {
        val deadBridge = currentStatus(alive = false, owner = "adb", uid = 2000)
        val liveShizuku = currentStatus(owner = "shizuku", uid = 2000)

        assertEquals(
            Transport.SHIZUKU,
            rendererToFenceOnColdStart(Transport.ADB, deadBridge, liveShizuku),
        )
    }

    @Test
    fun `cold start ignores only absent or already selected renderers`() {
        val adb = currentStatus(owner = "adb", uid = 2000, pid = 4321)

        assertNull(rendererToFenceOnColdStart(Transport.ADB, adb, null))
        assertNull(
            rendererToFenceOnColdStart(
                Transport.SHIZUKU,
                adb.copy(alive = false, pid = -1),
                currentStatus(alive = false, owner = "shizuku", uid = 2000),
            ),
        )
    }

    @Test
    fun `expired heartbeat with a recorded pid remains a cold start ownership fence`() {
        val expired = currentStatus(
            alive = false,
            owner = "adb",
            uid = 2000,
            pid = 4321,
            rendererInstanceId = "adb-expired",
        )

        assertEquals(
            Transport.ADB,
            rendererToFenceOnColdStart(Transport.SHIZUKU, expired, null),
        )
    }

    @Test
    fun `stale renderer needs a live heartbeat but fatal ownership stays fenced until exact exit`() {
        val stale = currentStatus(rendererImplementationRevision = 0)

        assertTrue(shouldQuarantineActiveRenderer(stale))
        assertFalse(shouldQuarantineActiveRenderer(stale.copy(alive = false)))
        assertTrue(
            shouldQuarantineActiveRenderer(
                currentStatus(alive = false).copy(blackClearUnreleasedFatal = true),
            ),
        )
        assertFalse(shouldQuarantineActiveRenderer(currentStatus()))
    }

    @Test
    fun `unresolved incompatible shizuku fences adb and root routing`() {
        Transport.entries.forEach { selected ->
            assertEquals(
                Transport.SHIZUKU,
                selectRendererTransport(
                    rootRunning = false,
                    selected = selected,
                    shizukuConnected = false,
                    unresolvedIncompatibleShizuku = true,
                ),
            )
        }
        assertEquals(
            Transport.SHIZUKU,
            selectRendererTransport(
                rootRunning = true,
                selected = Transport.ROOT,
                shizukuConnected = false,
                unresolvedIncompatibleShizuku = true,
            ),
        )
    }

    @Test
    fun `normal renderer routing resumes only after incompatible shizuku fence clears`() {
        assertEquals(
            Transport.ADB,
            selectRendererTransport(false, Transport.AUTO, false, false),
        )
        assertEquals(
            Transport.SHIZUKU,
            selectRendererTransport(false, Transport.AUTO, true, false),
        )
        assertEquals(
            Transport.ROOT,
            selectRendererTransport(true, Transport.AUTO, true, false),
        )
    }

    @Test
    fun `manual cleanup pending clears on dead stale seen or superseded renderer status`() {
        val requestId = 81L

        assertTrue(shouldKeepManualCleanupRequestPending(requestId, currentStatus()))
        assertFalse(
            shouldKeepManualCleanupRequestPending(requestId, currentStatus(alive = false)),
        )
        assertFalse(
            shouldKeepManualCleanupRequestPending(
                requestId,
                currentStatus(rendererImplementationRevision = 0),
            ),
        )
        assertFalse(
            shouldKeepManualCleanupRequestPending(
                requestId,
                currentStatus(lastSeenManualBlackClearRequestId = requestId),
            ),
        )
        assertFalse(
            shouldKeepManualCleanupRequestPending(
                requestId,
                currentStatus(lastSeenManualBlackClearRequestId = requestId + 1),
            ),
        )
    }

    @Test
    fun `dark handoff coalescing retains manual cleanup but visible output abandons it`() {
        assertEquals(91L, coalescedManualCleanupRequestId(91L, false, false, null))
        assertNull(coalescedManualCleanupRequestId(91L, true, false, null))
        assertNull(coalescedManualCleanupRequestId(91L, false, true, null))
        assertEquals(92L, coalescedManualCleanupRequestId(91L, false, false, 92L))
    }

    @Test
    fun `manual cleanup transfers only when exact source has not observed it`() {
        val requestId = 93L

        assertEquals(
            requestId,
            manualCleanupRequestForDestination(requestId, currentStatus()),
        )
        assertNull(
            manualCleanupRequestForDestination(
                requestId,
                currentStatus(lastSeenManualBlackClearRequestId = requestId),
            ),
        )
        assertNull(
            manualCleanupRequestForDestination(
                requestId,
                currentStatus(lastSeenManualBlackClearRequestId = requestId + 1),
            ),
        )
    }

    @Test
    fun `fatal fenced output replays only after exact process exit`() {
        assertTrue(canReplayFatalFencedOutput(exactExitConfirmed = true))
        assertFalse(canReplayFatalFencedOutput(exactExitConfirmed = false))
    }

    private fun currentStatus(
        alive: Boolean = true,
        appliedStateRevision: Long = 0,
        receivedStateRevision: Long = appliedStateRevision,
        settledStateRevision: Long = 0,
        releasedStateRevision: Long = 0,
        owner: String = "",
        uid: Int = -1,
        pid: Int = -1,
        rendererImplementationRevision: Int = RendererContract.IMPLEMENTATION_REVISION,
        lastSeenManualBlackClearRequestId: Long = 0,
        rendererInstanceId: String = "",
    ) = HelperStatus(
        alive = alive,
        owner = owner,
        uid = uid,
        pid = pid,
        rendererInstanceId = rendererInstanceId,
        rendererContractVersion = RendererContract.CONTRACT_VERSION,
        rendererImplementationRevision = rendererImplementationRevision,
        rendererStatusSchemaVersion = RendererContract.STATUS_SCHEMA_VERSION,
        rendererClearAlgorithmVersion = RendererContract.CLEAR_ALGORITHM_VERSION,
        rendererReady = true,
        blackClearTerminal = true,
        appliedStateRevision = appliedStateRevision,
        receivedStateRevision = receivedStateRevision,
        settledStateRevision = settledStateRevision,
        releasedStateRevision = releasedStateRevision,
        lastSeenManualBlackClearRequestId = lastSeenManualBlackClearRequestId,
    )
}
