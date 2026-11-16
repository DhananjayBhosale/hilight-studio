package com.hilight.studio

import android.content.pm.PackageManager
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Play crash (1.0.18-rc.1), root re-detection and the diagnostics added for tester feedback. */
class ConnectionRecoveryTest {

    @Test
    fun `a Shizuku manager that rejects this app is unavailable instead of crashing`() {
        assertEquals(ShizukuPermission.GRANTED, shizukuPermission { PackageManager.PERMISSION_GRANTED })
        assertEquals(ShizukuPermission.DENIED, shizukuPermission { PackageManager.PERMISSION_DENIED })
        // The crash: IShizukuService$Stub$Proxy.checkSelfPermission rethrew the manager's
        // IllegalStateException ("Not an attached client") on the main thread.
        assertEquals(
            ShizukuPermission.UNAVAILABLE,
            shizukuPermission { throw IllegalStateException("Not an attached client") },
        )
        assertEquals(ShizukuPermission.UNAVAILABLE, shizukuPermission { throw SecurityException() })
    }

    @Test
    fun `Shizuku errors reach diagnostics only as fixed codes`() {
        assertEquals("none", shizukuErrorCode(null))
        assertEquals("not_attached", shizukuErrorCode(R.string.shizuku_error_not_attached))
        assertEquals("too_old", shizukuErrorCode(R.string.shizuku_error_too_old))
        assertEquals("other", shizukuErrorCode(R.string.live_status_on))
    }

    @Test
    fun `root is looked for again while unavailable, at most every few seconds`() {
        val due = ROOT_PRESENCE_RECHECK_MS
        assertTrue(shouldRecheckRootPresence(RootBackend.State.UNAVAILABLE, false, due))
        assertFalse(shouldRecheckRootPresence(RootBackend.State.UNAVAILABLE, false, due - 1))
        // A presence-check error may clear; a failed start must stay visible with Retry root.
        assertTrue(shouldRecheckRootPresence(RootBackend.State.ERROR, true, due))
        assertFalse(shouldRecheckRootPresence(RootBackend.State.ERROR, false, due))
        listOf(
            RootBackend.State.AVAILABLE, RootBackend.State.RUNNING, RootBackend.State.STARTING,
            RootBackend.State.REQUESTING, RootBackend.State.DENIED, RootBackend.State.CHECKING,
        ).forEach { assertFalse(it.name, shouldRecheckRootPresence(it, false, Long.MAX_VALUE / 2)) }
    }

    @Test
    fun `diagnostics include Shizuku and the guards holding the array dark`() {
        val payload = JSONObject(RendererDiagnostics.format(
            status = HelperStatus(alive = false),
            selectedTransport = Transport.AUTO,
            activeTransport = Transport.SHIZUKU,
            appVersionName = "1.0.19", appVersionCode = 21,
            deviceModel = "Pixel", buildId = "test", sdkInt = 37,
            capturedAtEpochMs = 100, capturedAtElapsedRealtimeMs = 100,
            shizuku = ShizukuConnectionDiagnostics(
                state = ShizukuBackend.State.FAILED, installed = true, managerAlive = true,
                serverVersion = 13, permission = "unavailable", fenced = false,
                error = "not_attached",
            ),
            gates = GateDiagnostics(
                notificationAccess = false, ambientSuppression = "quiet_hours",
                alertSuppression = "free text from somewhere", quietHoursEnabled = true,
                inQuietWindow = true, dndSuppressesSignals = false, faceDownOnly = false,
                faceDown = false, screenOffOnly = true, screenOn = true,
            ),
        ))
        val shizuku = payload.getJSONObject("shizuku")
        assertEquals("failed", shizuku.getString("state"))
        assertEquals("unavailable", shizuku.getString("permission"))
        assertEquals("not_attached", shizuku.getString("error"))
        assertEquals(13, shizuku.getInt("serverVersion"))
        val gates = payload.getJSONObject("gates")
        assertFalse(gates.getBoolean("notificationAccess"))
        assertEquals("quiet_hours", gates.getString("ambientSuppression"))
        // Anything outside the fixed vocabulary is replaced, never copied.
        assertEquals("unknown", gates.getString("alertSuppression"))
    }

    @Test
    fun `a renderer that doesn't answer its status can't block the caller`() {
        val executor = Executors.newCachedThreadPool()
        try {
            val call = BoundedCall<String>(executor, timeoutMs = 50)
            assertEquals("ok", call.run { "ok" }?.getOrNull())
            val failure = call.run { throw IllegalStateException("dead") }
            assertTrue(failure!!.isFailure)

            val release = CountDownLatch(1)
            val started = System.nanoTime()
            assertNull("a stuck read times out", call.run { release.await(); "late" })
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 1_000)
            // While the stuck read still runs, later polls return at once instead of queueing.
            val again = System.nanoTime()
            assertNull(call.run { "queued" })
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - again) < 40)

            // A rebound service (a different key) is never held up by the old stuck read.
            assertEquals("rebound", call.run(key = "new binder") { "rebound" }?.getOrNull())

            release.countDown()
            Thread.sleep(50)
            assertEquals("fresh", call.run { "fresh" }?.getOrNull())
        } finally {
            executor.shutdownNow()
        }
    }
}
