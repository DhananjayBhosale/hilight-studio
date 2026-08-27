package com.hilight.studio

import com.hilight.core.RendererContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LedCleanupRetryTest {

    private val ready = HelperStatus(
        alive = true,
        rendererReady = true,
        rendererContractVersion = RendererContract.CONTRACT_VERSION,
        rendererImplementationRevision = RendererContract.IMPLEMENTATION_REVISION,
        rendererStatusSchemaVersion = RendererContract.STATUS_SCHEMA_VERSION,
        rendererClearAlgorithmVersion = RendererContract.CLEAR_ALGORITHM_VERSION,
        ledCount = 8,
        sessionOpen = false,
        blackClearPending = false,
        blackClearTerminal = true,
        privacyObserverEnabled = false,
    )

    @Test
    fun `retry is enabled only for current idle renderer while master is off`() {
        assertTrue(canRetryLedCleanup(false, ready, false, false))

        assertFalse(canRetryLedCleanup(true, ready, false, false))
        assertFalse(canRetryLedCleanup(false, ready.copy(alive = false), false, false))
        assertFalse(canRetryLedCleanup(false, ready.copy(rendererReady = false), false, false))
        assertFalse(canRetryLedCleanup(false, ready.copy(ledCount = 0), false, false))
        assertFalse(canRetryLedCleanup(
            false,
            ready.copy(blackClearUnreleasedFatal = true),
            false,
            false,
        ))
        assertFalse(canRetryLedCleanup(
            false,
            ready.copy(
                rendererImplementationRevision = RendererContract.IMPLEMENTATION_REVISION - 1,
            ),
            false,
            false,
        ))
        assertFalse(canRetryLedCleanup(false, ready.copy(sessionOpen = true), false, false))
        assertFalse(canRetryLedCleanup(
            false,
            ready.copy(blackClearPending = true, blackClearTerminal = false),
            false,
            false,
        ))
        assertFalse(canRetryLedCleanup(
            false,
            ready.copy(privacyObserverEnabled = true),
            false,
            false,
        ))
        assertFalse(canRetryLedCleanup(false, ready, true, false))
        assertFalse(canRetryLedCleanup(false, ready, false, true))
    }

    @Test
    fun `pending dispatch clears on acknowledgement death stale renderer or newer request`() {
        assertTrue(shouldKeepManualCleanupRequestPending(50, ready))
        assertFalse(shouldKeepManualCleanupRequestPending(50, ready.copy(
            lastSeenManualBlackClearRequestId = 50,
        )))
        assertFalse(shouldKeepManualCleanupRequestPending(50, ready.copy(
            lastSeenManualBlackClearRequestId = 51,
        )))
        assertFalse(shouldKeepManualCleanupRequestPending(50, ready.copy(alive = false)))
        assertFalse(shouldKeepManualCleanupRequestPending(50, ready.copy(
            rendererImplementationRevision = RendererContract.IMPLEMENTATION_REVISION - 1,
        )))
    }

    @Test
    fun `dark handoff coalescing retains request while visible output supersedes it`() {
        assertEquals(50L, coalescedManualCleanupRequestId(50, false, false, null))
        assertEquals(51L, coalescedManualCleanupRequestId(50, false, false, 51))
        assertNull(coalescedManualCleanupRequestId(50, true, false, null))
        assertNull(coalescedManualCleanupRequestId(50, false, true, null))
    }

    @Test
    fun `ordinary disabled rewrites keep pending manual request until acknowledgement`() {
        var pending: Long? = 61L
        repeat(5) {
            pending = coalescedManualCleanupRequestId(pending, false, false, null)
        }

        assertEquals(61L, pending)
        assertFalse(shouldKeepManualCleanupRequestPending(
            61L,
            ready.copy(lastSeenManualBlackClearRequestId = 61L),
        ))
    }
}
