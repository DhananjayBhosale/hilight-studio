package com.hilight.studio

import com.hilight.core.RendererContract
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeManualCleanupTest {

    @Test
    fun `ordinary documents omit manual cleanup request`() {
        val state = JSONObject(
            Bridge.stateJson(
                enabled = false,
                priority = 0,
                ambient = Ambient(),
                alert = null,
            )
        )

        assertFalse(state.has("manualBlackClearRequestId"))
    }

    @Test
    fun `manual document adds only opaque request id to ordinary shape`() {
        val ordinary = JSONObject(
            Bridge.stateJson(false, 0, Ambient(), null, arm = false)
        )
        val manual = JSONObject(
            Bridge.stateJson(
                enabled = false,
                priority = 0,
                ambient = Ambient(),
                alert = null,
                arm = false,
                manualBlackClearRequestId = 808L,
            )
        )

        assertEquals(ordinary.keySet() + "manualBlackClearRequestId", manual.keySet())
        assertEquals(808L, manual.getLong("manualBlackClearRequestId"))
        listOf("manualBlackClearStrategy", "manualBlackClearColors", "manualBlackClearCount")
            .forEach { assertFalse(manual.has(it)) }
        assertTrue(manual.getBoolean("enabled").not())
        assertFalse(manual.has("alert"))
    }

    @Test
    fun `incompatible renderer idle fence is minimal and can only disable output`() {
        val state = JSONObject(Bridge.incompatibleRendererSafeIdleJson(909L))

        assertEquals(
            setOf(
                "v",
                "stateRevision",
                "enabled",
                "arm",
                "privacyObserverEnabled",
                "privacyOutputEnabled",
            ),
            state.keySet(),
        )
        assertEquals(909L, state.getLong("stateRevision"))
        assertFalse(state.getBoolean("enabled"))
        assertFalse(state.getBoolean("arm"))
        assertFalse(state.getBoolean("privacyObserverEnabled"))
        assertFalse(state.getBoolean("privacyOutputEnabled"))
    }

    @Test
    fun `torn status read retains last good process until heartbeat is explicitly stale`() {
        val cache = Bridge.BridgeStatusCache()
        val heartbeat = currentHeartbeat(timestampMs = 10_000L).toString()

        val good = cache.read(heartbeat, 10_100L)
        val torn = cache.read("{\"ts\":", 11_000L)
        val placeholder = cache.read("{}", 12_000L)
        val stale = cache.read(null, 14_001L)

        assertTrue(good.alive)
        assertEquals(4321, torn.pid)
        assertEquals("adb-instance-1", torn.rendererInstanceId)
        assertTrue(torn.alive)
        assertFalse(torn.identityResolved)
        assertTrue(placeholder.alive)
        assertFalse(placeholder.identityResolved)
        assertFalse(stale.alive)
        assertEquals(4321, stale.pid)
        assertFalse(stale.identityResolved)
    }

    @Test
    fun `valid partial current status cannot replace the last complete snapshot`() {
        val cache = Bridge.BridgeStatusCache()
        val complete = currentHeartbeat(timestampMs = 10_000L)
            .put("releasedStateRevision", 41L)
            .put("lastAcceptedManualBlackClearRequestId", 808L)
            .toString()
        val partial = JSONObject()
            .put("ts", 11_000L)
            .put("pid", 4321)
            .put("uid", 2000)
            .put("owner", "adb")
            .put("rendererInstanceId", "adb-instance-1")
            .toString()

        cache.read(complete, 10_100L)
        val retained = cache.read(partial, 11_100L)

        assertTrue(retained.alive)
        assertFalse(retained.identityResolved)
        assertEquals(1_100L, retained.ageMs)
        assertEquals(41L, retained.releasedStateRevision)
        assertEquals(808L, retained.lastAcceptedManualBlackClearRequestId)
    }

    @Test
    fun `current-looking status without instance is torn not legacy`() {
        val cache = Bridge.BridgeStatusCache()
        val complete = currentHeartbeat(timestampMs = 10_000L).toString()
        val currentWithoutInstance = JSONObject()
            .put("ts", 11_000L)
            .put("pid", 9999)
            .put("uid", 2000)
            .put("owner", "adb")
            .put("rendererContractVersion", RendererContract.CONTRACT_VERSION)
            .toString()

        cache.read(complete, 10_100L)
        val retained = cache.read(currentWithoutInstance, 11_100L)

        assertEquals(4321, retained.pid)
        assertEquals("adb-instance-1", retained.rendererInstanceId)
        assertFalse(retained.identityResolved)
    }

    @Test
    fun `minimal unscoped legacy status remains backward compatible`() {
        val cache = Bridge.BridgeStatusCache()
        val legacy = JSONObject()
            .put("ts", 20_000L)
            .put("pid", 8765)
            .put("uid", 2000)
            .put("owner", "adb")
            .put("appliedStateRevision", 12L)
            .toString()

        val status = cache.read(legacy, 20_100L)

        assertTrue(status.alive)
        assertEquals(8765, status.pid)
        assertEquals("", status.rendererInstanceId)
        assertEquals(12L, status.receivedStateRevision)
        assertTrue(status.identityResolved)
    }

    @Test
    fun `owner and uid mismatch is not accepted as a bridge identity`() {
        val mismatched = JSONObject()
            .put("ts", 20_000L)
            .put("pid", 8765)
            .put("uid", 2000)
            .put("owner", "root")
            .put("appliedStateRevision", 12L)
            .toString()

        val status = Bridge.BridgeStatusCache().read(mismatched, 20_100L)

        assertFalse(status.alive)
        assertFalse(status.identityResolved)
        assertEquals(-1, status.pid)
    }

    @Test
    fun `file bridge state can be scoped only by a safe renderer instance id`() {
        val ordinary = Bridge.stateJson(true, 0, Ambient(), null)
        val scoped = JSONObject(Bridge.targetRenderer(ordinary, "adb-instance.2"))
        val rejected = JSONObject(Bridge.targetRenderer(ordinary, "bad id; kill 1"))

        assertEquals("adb-instance.2", scoped.getString("bridgeRendererInstanceId"))
        assertFalse(rejected.has("bridgeRendererInstanceId"))
        assertEquals("adb-instance.2", Bridge.targetedRendererInstanceId(scoped.toString()))
        assertTrue(Bridge.stateTargetsRenderer(scoped.toString(), "adb-instance.2"))
        assertFalse(Bridge.stateTargetsRenderer(scoped.toString(), "adb-instance.20"))
        assertEquals(null, Bridge.targetedRendererInstanceId("{"))
    }

    @Test
    fun `fresh invalid status without history is explicitly identity unresolved`() {
        val status = Bridge.BridgeStatusCache().read("{\"ts\":", 12_000L)

        assertFalse(status.alive)
        assertFalse(status.identityResolved)
        assertEquals(-1, status.pid)
    }

    @Test
    fun `fatal source stays pinned across healthy successor until exact forget`() {
        val cache = Bridge.BridgeStatusCache()
        val fatalA = currentHeartbeat(timestampMs = 10_000L)
            .put("pid", 111)
            .put("rendererInstanceId", "adb-fatal-a")
            .put("blackClearUnreleasedFatal", true)
            .put("blackClearResult", "unreleased_fatal")
            .toString()
        val healthyB = currentHeartbeat(timestampMs = 11_000L)
            .put("pid", 222)
            .put("rendererInstanceId", "adb-healthy-b")
            .toString()

        assertEquals(111, cache.read(fatalA, 10_100L).pid)
        val pinned = cache.read(healthyB, 11_100L)
        assertEquals(111, pinned.pid)
        assertEquals("adb-fatal-a", pinned.rendererInstanceId)
        assertTrue(pinned.blackClearUnreleasedFatal)

        cache.forget("adb-fatal-a")
        val accepted = cache.read(healthyB, 11_200L)
        assertEquals(222, accepted.pid)
        assertEquals("adb-healthy-b", accepted.rendererInstanceId)
        assertFalse(accepted.blackClearUnreleasedFatal)
    }

    @Test
    fun `forgotten fatal cannot be repinned by stale on-disk heartbeat`() {
        val cache = Bridge.BridgeStatusCache()
        val fatal = currentHeartbeat(timestampMs = 10_000L)
            .put("pid", 111)
            .put("rendererInstanceId", "adb-fatal-a")
            .put("blackClearUnreleasedFatal", true)
            .put("blackClearResult", "unreleased_fatal")
            .toString()

        assertTrue(cache.read(fatal, 10_100L).blackClearUnreleasedFatal)
        cache.forget("adb-fatal-a")
        val staleDiskRead = cache.read(fatal, 10_200L)

        assertFalse(staleDiskRead.alive)
        assertEquals(-1, staleDiskRead.pid)
        assertFalse(staleDiskRead.blackClearUnreleasedFatal)
        assertTrue(staleDiskRead.identityResolved)
    }

    @Test
    fun `same fatal process heartbeat refreshes liveness without clearing fatal fence`() {
        val cache = Bridge.BridgeStatusCache()
        val fatal = currentHeartbeat(timestampMs = 10_000L)
            .put("pid", 111)
            .put("rendererInstanceId", "adb-fatal-a")
            .put("blackClearUnreleasedFatal", true)
            .put("blackClearResult", "unreleased_fatal")
            .toString()
        val laterSuccessShaped = currentHeartbeat(timestampMs = 20_000L)
            .put("pid", 111)
            .put("rendererInstanceId", "adb-fatal-a")
            .toString()

        cache.read(fatal, 10_100L)
        val stillPinned = cache.read(laterSuccessShaped, 20_100L)

        assertTrue(stillPinned.alive)
        assertEquals(100L, stillPinned.ageMs)
        assertTrue(stillPinned.blackClearUnreleasedFatal)
        assertEquals("unreleased_fatal", stillPinned.blackClearResult)
    }

    @Test
    fun `legacy tombstone rejects stale sample but permits later process identity reuse`() {
        val cache = Bridge.BridgeStatusCache()
        val legacy = JSONObject()
            .put("ts", 20_000L)
            .put("pid", 8765)
            .put("uid", 2000)
            .put("owner", "adb")
            .put("appliedStateRevision", 12L)
        cache.read(legacy.toString(), 20_100L)
        cache.forget("")

        assertFalse(cache.read(legacy.toString(), 20_200L).alive)

        val laterReuse = JSONObject(legacy.toString()).put("ts", 30_000L).toString()
        val accepted = cache.read(laterReuse, 30_100L)
        assertTrue(accepted.alive)
        assertEquals(8765, accepted.pid)
        assertTrue(accepted.identityResolved)
    }

    private fun currentHeartbeat(timestampMs: Long): JSONObject = JSONObject()
        .put("ts", timestampMs)
        .put("pid", 4321)
        .put("uid", 2000)
        .put("owner", "adb")
        .put("rendererInstanceId", "adb-instance-1")
        .put("ledCount", 1)
        .put("session", false)
        .put("blackClearPending", false)
        .put("blackClearTerminal", true)
        .put("blackClearUnreleasedFatal", false)
        .put("blackClearResult", "succeeded")
        .put("blackClearAttemptResult", "succeeded")
        .put("blackClearStage", "closed")
        .put("blackClearTimestampElapsedMs", 9_900L)
        .put("blackClearCycleId", 4L)
        .put("blackClearCycleSource", "automatic")
        .put("blackClearAttemptsUsed", 1)
        .put("blackClearAttemptsRemaining", 0)
        .put("blackClearStopAttemptAvailable", false)
        .put("blackClearCloseFailures", 0)
        .put("lightMinUpdatePeriodMs", 50L)
        .put("blackClearStrategy", "alpha-black")
        .put("blackClearStrategyVersion", 3)
        .put("receivedStateRevision", 41L)
        .put("settledStateRevision", 41L)
        .put("releasedStateRevision", 41L)
        .put("lastSeenManualBlackClearRequestId", 808L)
        .put("lastAcceptedManualBlackClearRequestId", 808L)
        .put("privacyObserverEnabled", false)
        .put("privacyObserverState", "stopped")
        .put("privacyPhase", "inactive")
        .put("rendererContractVersion", RendererContract.CONTRACT_VERSION)
        .put("rendererImplementationRevision", RendererContract.IMPLEMENTATION_REVISION)
        .put("rendererStatusSchemaVersion", RendererContract.STATUS_SCHEMA_VERSION)
        .put("rendererClearAlgorithmVersion", RendererContract.CLEAR_ALGORITHM_VERSION)

    private fun JSONObject.keySet(): Set<String> = keys().asSequence().toSet()
}
