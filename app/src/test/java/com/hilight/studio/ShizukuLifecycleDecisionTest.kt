package com.hilight.studio

import com.hilight.core.RendererContract
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ShizukuLifecycleDecisionTest {

    @Test
    fun `visible adb state is targeted only to an exact current shell helper`() {
        val decision = safeAdbPush(visibleState(), currentAdbStatus())
        val output = JSONObject(decision.json)

        assertEquals(AdbPushSafety.TARGETED_CURRENT, decision.safety)
        assertEquals("adb-instance-1", output.getString("bridgeRendererInstanceId"))
        assertTrue(output.getBoolean("enabled"))
    }

    @Test
    fun `visible adb state is sanitized when identity is stale unresolved fatal or wrong uid`() {
        val unsafe = listOf(
            currentAdbStatus().copy(alive = false),
            currentAdbStatus().copy(identityResolved = false),
            currentAdbStatus().copy(blackClearUnreleasedFatal = true),
            currentAdbStatus().copy(uid = 0),
            currentAdbStatus().copy(rendererInstanceId = "bad instance"),
        )

        unsafe.forEach { status ->
            val decision = safeAdbPush(visibleState(), status)
            val output = JSONObject(decision.json)
            assertEquals(AdbPushSafety.SANITIZED_DISABLED, decision.safety)
            assertFalse(output.getBoolean("enabled"))
            assertFalse(output.getBoolean("privacyOutputEnabled"))
            assertFalse(output.has("bridgeRendererInstanceId"))
            assertEquals(44L, output.getLong("stateRevision"))
        }
    }

    @Test
    fun `ordinary disabled adb state remains unscoped for legacy shutdown`() {
        val idle = JSONObject()
            .put("v", 2)
            .put("stateRevision", 45L)
            .put("enabled", false)
            .put("privacyOutputEnabled", false)
            .put("arm", false)
            .put("bridgeRendererInstanceId", "stale-target")
            .toString()

        val decision = safeAdbPush(idle, HelperStatus(alive = false, owner = "adb"))

        assertEquals(AdbPushSafety.BROADCAST_DISABLED, decision.safety)
        assertFalse(JSONObject(decision.json).has("bridgeRendererInstanceId"))
    }

    @Test
    fun `Shizuku lifecycle bounds accommodate peek retry startup and bounded engine stop`() {
        assertEquals(12, ShizukuBackend.MIN_SHIZUKU_SERVER_VERSION)
        assertTrue(ShizukuBackend.PEEK_CALLBACK_TIMEOUT_MS * 2 > 7_000L)
        assertTrue(ShizukuBackend.STARTUP_CALLBACK_TIMEOUT_MS > 7_000L)
        assertTrue(ShizukuBackend.REMOVE_CONFIRM_TIMEOUT_MS > 7_000L)
        assertTrue(ShizukuBackend.RELEASE_CONFIRM_TIMEOUT_MS > 4_000L)
    }

    @Test
    fun `peek authorizes create only when first observation proves absence`() {
        assertEquals(
            ShizukuPeekAction.CREATE_ONCE,
            shizukuPeekAction(-1, priorPeekReportedExisting = false, retry = false),
        )
        assertEquals(
            ShizukuPeekAction.WAIT_FOR_CALLBACK,
            shizukuPeekAction(0, priorPeekReportedExisting = false, retry = false),
        )
        assertEquals(
            ShizukuPeekAction.FENCE,
            shizukuPeekAction(-1, priorPeekReportedExisting = true, retry = true),
        )
    }

    @Test
    fun `partial Shizuku read retains last complete binder-scoped sample`() {
        val retained = JSONObject().put("sample", "last-complete")
        val partial = JSONObject().put("pid", 123)

        assertSame(retained, retainedShizukuStatus(partial, retained))
        assertSame(retained, retainedShizukuStatus(null, retained))
    }

    @Test
    fun `explicit disconnect makes every queued startup callback removal only`() {
        assertTrue(
            shouldRemoveLateShizukuCandidate(
                disconnectRequested = true,
                ownershipFenced = true,
                expectedPeekOrCreateCallback = true,
            ),
        )
        assertTrue(
            shouldRemoveLateShizukuCandidate(
                disconnectRequested = false,
                ownershipFenced = true,
                expectedPeekOrCreateCallback = false,
            ),
        )
        assertFalse(
            shouldRemoveLateShizukuCandidate(
                disconnectRequested = false,
                ownershipFenced = true,
                expectedPeekOrCreateCallback = true,
            ),
        )
    }

    @Test
    fun `unexpected renderer death cannot release the primary renderer fence`() {
        val primary = 11L
        val unexpected = 12L

        assertFalse(
            canCompleteTrackedShizukuExit(
                primary = primary,
                confirmedDead = setOf(unexpected),
                quarantined = emptySet(),
            ),
        )
        assertFalse(
            canCompleteTrackedShizukuExit(
                primary = primary,
                confirmedDead = setOf(primary),
                quarantined = setOf(unexpected),
            ),
        )
        assertTrue(
            canCompleteTrackedShizukuExit(
                primary = primary,
                confirmedDead = setOf(primary, unexpected),
                quarantined = emptySet(),
            ),
        )
    }

    @Test
    fun `same Shizuku process may join one in-flight exact termination result`() {
        val source = HelperStatus(
            alive = true,
            identityResolved = true,
            owner = "shizuku",
            pid = 321,
        )

        assertTrue(
            canJoinShizukuTermination(
                callbackStillPending = true,
                source = source,
                current = source.copy(alive = false),
            ),
        )
        assertFalse(
            canJoinShizukuTermination(
                callbackStillPending = true,
                source = source,
                current = source.copy(pid = 322),
            ),
        )
        assertFalse(
            canJoinShizukuTermination(
                callbackStillPending = false,
                source = source,
                current = source,
            ),
        )
    }

    private fun visibleState() = JSONObject()
        .put("v", 2)
        .put("stateRevision", 44L)
        .put("enabled", true)
        .put("privacyOutputEnabled", false)
        .toString()

    private fun currentAdbStatus() = HelperStatus(
        alive = true,
        identityResolved = true,
        pid = 321,
        uid = 2_000,
        owner = "adb",
        rendererInstanceId = "adb-instance-1",
        rendererContractVersion = RendererContract.CONTRACT_VERSION,
        rendererImplementationRevision = RendererContract.IMPLEMENTATION_REVISION,
        rendererStatusSchemaVersion = RendererContract.STATUS_SCHEMA_VERSION,
        rendererClearAlgorithmVersion = RendererContract.CLEAR_ALGORITHM_VERSION,
        rendererReady = true,
    )
}
