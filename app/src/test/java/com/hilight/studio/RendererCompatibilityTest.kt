package com.hilight.studio

import com.hilight.core.RendererContract
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RendererCompatibilityTest {

    @Test
    fun `live renderer with current contract is usable`() {
        val status = currentStatus()

        assertEquals(RendererCompatibility.CURRENT, status.rendererCompatibility)
        assertFalse(status.rendererStale)
    }

    @Test
    fun `legacy renderer with no identity is stale`() {
        val status = HelperStatus(alive = true, rendererReady = true)

        assertEquals(RendererCompatibility.UNKNOWN, status.rendererCompatibility)
        assertTrue(status.rendererStale)
    }

    @Test
    fun `mismatched implementation is stale`() {
        val status = currentStatus().copy(
            rendererImplementationRevision = RendererContract.IMPLEMENTATION_REVISION - 1,
        )

        assertEquals(RendererCompatibility.INCOMPATIBLE, status.rendererCompatibility)
        assertTrue(status.rendererStale)
    }

    @Test
    fun `current identity with failed engine is stale`() {
        assertTrue(currentStatus().copy(rendererReady = false).rendererStale)
    }

    @Test
    fun `expired current identity remains current and is not stale`() {
        val status = currentStatus().copy(alive = false, pid = 1234)

        assertEquals(RendererCompatibility.CURRENT, status.rendererCompatibility)
        assertFalse(status.rendererStale)
    }

    @Test
    fun `expired legacy identity remains recorded and stale`() {
        val status = HelperStatus(
            alive = false,
            pid = 1234,
            rendererReady = true,
        )

        assertEquals(RendererCompatibility.UNKNOWN, status.rendererCompatibility)
        assertTrue(status.rendererStale)
    }

    @Test
    fun `default absence is neither alive nor stale`() {
        val status = HelperStatus(alive = false)

        assertEquals(RendererCompatibility.UNKNOWN, status.rendererCompatibility)
        assertFalse(status.rendererStale)
    }

    @Test
    fun `Shizuku handshake accepts only a ready current renderer`() {
        val expectedService = RendererContract.shizukuServiceVersion(10)

        assertEquals(
            null,
            shizukuRendererCompatibilityFailure(currentJson(), expectedService, 10),
        )
    }

    @Test
    fun `Shizuku handshake rejects legacy unknown and wrong build renderers`() {
        val expectedService = RendererContract.shizukuServiceVersion(10)

        assertTrue(
            shizukuRendererCompatibilityFailure(JSONObject(), expectedService, 10)
                ?.startsWith("renderer status incomplete") == true,
        )
        assertTrue(
            shizukuRendererCompatibilityFailure(
                currentJson().put("rendererServiceVersion", 9),
                expectedService,
                10,
            )?.startsWith("renderer build mismatch") == true,
        )
    }

    @Test
    fun `Shizuku handshake rejects a renderer whose engine failed`() {
        val status = currentJson().put("rendererReady", false)

        assertEquals(
            "renderer engine is not ready",
            shizukuRendererCompatibilityFailure(
                status,
                RendererContract.shizukuServiceVersion(10),
                10,
            ),
        )
    }

    @Test
    fun `Shizuku handshake rejects a partial current-looking status`() {
        val partial = currentJson().apply { remove("releasedStateRevision") }

        assertTrue(
            shizukuRendererCompatibilityFailure(
                partial,
                RendererContract.shizukuServiceVersion(10),
                10,
            )?.startsWith("renderer status incomplete") == true,
        )
        assertFalse(isCompleteShizukuRendererStatus(partial))
        assertTrue(isCompleteShizukuRendererStatus(currentJson()))
    }

    @Test
    fun `v1_0_8 Shizuku renderer is removed before service 1004 is admitted`() {
        val expectedService = RendererContract.shizukuServiceVersion(10)
        val oldRenderer = currentJson()
            .put("rendererVersionCode", 9)
            .put("rendererVersionName", "1.0.8")
            .put("rendererServiceVersion", RendererContract.shizukuServiceVersion(9))
        val safeIdle = JSONObject(Bridge.incompatibleRendererSafeIdleJson(10L))

        assertEquals(1_004, expectedService)
        assertEquals(
            ShizukuPeekAction.WAIT_FOR_CALLBACK,
            shizukuPeekAction(9, priorPeekReportedExisting = false, retry = false),
        )
        assertTrue(
            shizukuRendererCompatibilityFailure(oldRenderer, expectedService, 10)
                ?.startsWith("renderer build mismatch") == true,
        )
        assertFalse(safeIdle.getBoolean("enabled"))
        assertFalse(safeIdle.getBoolean("arm"))
        assertFalse(canCompleteTrackedShizukuExit("old", emptySet(), emptySet()))
        assertTrue(canCompleteTrackedShizukuExit("old", setOf("old"), emptySet()))
        assertEquals(
            null,
            shizukuRendererCompatibilityFailure(currentJson(), expectedService, 10),
        )
    }

    private fun currentStatus() = HelperStatus(
        alive = true,
        rendererContractVersion = RendererContract.CONTRACT_VERSION,
        rendererImplementationRevision = RendererContract.IMPLEMENTATION_REVISION,
        rendererStatusSchemaVersion = RendererContract.STATUS_SCHEMA_VERSION,
        rendererClearAlgorithmVersion = RendererContract.CLEAR_ALGORITHM_VERSION,
        rendererReady = true,
    )

    private fun currentJson() = JSONObject()
        .put("pid", 1234)
        .put("uid", 2000)
        .put("ledCount", 8)
        .put("session", false)
        .put("blackClearPending", false)
        .put("blackClearTerminal", true)
        .put("blackClearUnreleasedFatal", false)
        .put("blackClearResult", "completed_unverified")
        .put("blackClearAttemptResult", "framework_effective_unverified")
        .put("blackClearStage", "terminal")
        .put("blackClearTimestampElapsedMs", 100L)
        .put("blackClearCycleId", 1L)
        .put("blackClearCycleSource", "startup")
        .put("blackClearAttemptsUsed", 3)
        .put("blackClearAttemptsRemaining", 0)
        .put("blackClearStopAttemptAvailable", true)
        .put("blackClearCloseFailures", 0)
        .put("lightMinUpdatePeriodMs", 16L)
        .put("blackClearStrategy", "fresh_low_priority_session")
        .put("blackClearStrategyVersion", 1)
        .put("receivedStateRevision", 9L)
        .put("settledStateRevision", 9L)
        .put("releasedStateRevision", 9L)
        .put("lastSeenManualBlackClearRequestId", 0L)
        .put("lastAcceptedManualBlackClearRequestId", 0L)
        .put("privacyObserverEnabled", false)
        .put("privacyObserverState", "stopped")
        .put("privacyPhase", "inactive")
        .put("rendererContractVersion", RendererContract.CONTRACT_VERSION)
        .put("rendererImplementationRevision", RendererContract.IMPLEMENTATION_REVISION)
        .put("rendererStatusSchemaVersion", RendererContract.STATUS_SCHEMA_VERSION)
        .put("rendererClearAlgorithmVersion", RendererContract.CLEAR_ALGORITHM_VERSION)
        .put("rendererServiceVersion", RendererContract.shizukuServiceVersion(10))
        .put("rendererVersionCode", 10)
        .put("rendererVersionName", "1.0.9")
        .put("rendererReady", true)
}
