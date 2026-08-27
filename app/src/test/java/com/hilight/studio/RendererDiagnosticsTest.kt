package com.hilight.studio

import com.hilight.core.RendererContract
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RendererDiagnosticsTest {

    @Test
    fun `payload contains only allowlisted renderer lifecycle sections`() {
        val payload = JSONObject(
            RendererDiagnostics.format(
                status = HelperStatus(
                    alive = true,
                    ageMs = 250,
                    pid = 321,
                    uid = 2000,
                    owner = "private.package.canary",
                    rendererInstanceId = "adb-test:abc",
                    ledCount = 8,
                    sessionOpen = false,
                    blackClearPending = true,
                    blackClearTerminal = false,
                    blackClearResult = "framework_effective_unverified",
                    blackClearAttemptResult = "binder_accepted_unverified",
                    blackClearStage = "closed",
                    blackClearTimestampElapsedMs = 88_000,
                    blackClearCycleId = 9,
                    blackClearCycleSource = "manual",
                    blackClearAttemptsUsed = 2,
                    blackClearAttemptsRemaining = 2,
                    blackClearStopAttemptAvailable = true,
                    blackClearCloseFailures = 0,
                    blackClearUnreleasedFatal = false,
                    lightMinUpdatePeriodMs = 33,
                    blackClearStrategy = "alpha_black_then_zero",
                    blackClearStrategyVersion = RendererContract.CLEAR_ALGORITHM_VERSION,
                    rendererVersionCode = 10,
                    rendererVersionName = "1.0.9",
                    rendererContractVersion = RendererContract.CONTRACT_VERSION,
                    rendererImplementationRevision = RendererContract.IMPLEMENTATION_REVISION,
                    rendererStatusSchemaVersion = RendererContract.STATUS_SCHEMA_VERSION,
                    rendererClearAlgorithmVersion = RendererContract.CLEAR_ALGORITHM_VERSION,
                    rendererServiceVersion = RendererContract.shizukuServiceVersion(10),
                    rendererReady = true,
                    mode = "private notification text canary",
                    appliedStateRevision = 77,
                    receivedStateRevision = 77,
                    settledStateRevision = 76,
                    releasedStateRevision = 75,
                    lastSeenManualBlackClearRequestId = 501,
                    lastAcceptedManualBlackClearRequestId = 500,
                    privacyObserverEnabled = true,
                    privacyObserverState = "private account canary",
                ),
                selectedTransport = Transport.AUTO,
                activeTransport = Transport.SHIZUKU,
                appVersionName = "1.0.9",
                appVersionCode = 10,
                deviceModel = "Pixel 11 Pro",
                buildId = "A9.260820.001",
                sdkInt = 37,
                capturedAtEpochMs = 1_777_777_777_000,
                capturedAtElapsedRealtimeMs = 88_250,
            )
        )

        assertEquals(
            setOf(
                "schemaVersion",
                "capturedAtEpochMs",
                "app",
                "device",
                "transport",
                "renderer",
                "session",
                "cleanup",
                "state",
            ),
            payload.keySet(),
        )
        assertEquals(RendererDiagnostics.SCHEMA_VERSION, payload.getInt("schemaVersion"))
        assertEquals(setOf("versionName", "versionCode"), payload.objectKeys("app"))
        assertEquals(setOf("model", "buildId", "sdkInt"), payload.objectKeys("device"))
        assertEquals(setOf("selected", "active"), payload.objectKeys("transport"))
        assertEquals(
            setOf(
                "alive",
                "statusAgeMs",
                "pid",
                "uid",
                "instanceId",
                "ready",
                "compatibility",
                "stale",
                "versionName",
                "versionCode",
                "contractVersion",
                "implementationRevision",
                "statusSchemaVersion",
                "clearAlgorithmVersion",
                "serviceVersion",
            ),
            payload.objectKeys("renderer"),
        )
        assertEquals(
            setOf("open", "ledCount", "minUpdatePeriodMs"),
            payload.objectKeys("session"),
        )
        assertEquals(
            setOf(
                "pending",
                "terminal",
                "result",
                "attemptResult",
                "stage",
                "strategy",
                "strategyVersion",
                "cycleId",
                "cycleSource",
                "attemptsUsed",
                "attemptsRemaining",
                "stopAttemptAvailable",
                "closeFailures",
                "unreleasedFatal",
                "lastEventAgeMs",
                "lastSeenManualRequestId",
                "lastAcceptedManualRequestId",
            ),
            payload.objectKeys("cleanup"),
        )
        assertEquals(
            setOf(
                "appliedRevision",
                "receivedRevision",
                "settledRevision",
                "releasedRevision",
                "privacyObserverEnabled",
            ),
            payload.objectKeys("state"),
        )

        assertEquals("shizuku", payload.getJSONObject("transport").getString("active"))
        assertEquals(321, payload.getJSONObject("renderer").getInt("pid"))
        assertEquals("adb-test:abc", payload.getJSONObject("renderer").getString("instanceId"))
        assertEquals("current", payload.getJSONObject("renderer").getString("compatibility"))
        assertEquals(
            "framework_effective_unverified",
            payload.getJSONObject("cleanup").getString("result"),
        )
        assertEquals(250, payload.getJSONObject("cleanup").getLong("lastEventAgeMs"))
        assertEquals("manual", payload.getJSONObject("cleanup").getString("cycleSource"))
        assertEquals(501, payload.getJSONObject("cleanup").getLong("lastSeenManualRequestId"))
        assertEquals(500, payload.getJSONObject("cleanup").getLong("lastAcceptedManualRequestId"))
        assertEquals(77, payload.getJSONObject("state").getLong("appliedRevision"))
        assertEquals(75, payload.getJSONObject("state").getLong("releasedRevision"))
        assertTrue(payload.getJSONObject("state").getBoolean("privacyObserverEnabled"))
    }

    @Test
    fun `payload cannot copy unrelated status strings or common sensitive fields`() {
        val result = RendererDiagnostics.format(
            status = HelperStatus(
                alive = true,
                owner = "com.private.messages",
                mode = "Your verification code is 123456",
                privacyObserverState = "person@example.com",
                rendererVersionName = "unsafe version with spaces and secret@example.com",
                blackClearResult = "private result person@example.com",
                blackClearAttemptResult = "verification code 123456",
                blackClearStage = "com.private.messages",
                blackClearCycleSource = "private source person@example.com",
                blackClearStrategy = "private_package_scrub",
            ),
            selectedTransport = Transport.ADB,
            activeTransport = Transport.ADB,
            appVersionName = "1.0.9",
            appVersionCode = 10,
            deviceModel = "Pixel 11 Pro XL",
            buildId = "A9.260820.001",
            sdkInt = 37,
            capturedAtEpochMs = 123,
            capturedAtElapsedRealtimeMs = 456,
        )

        listOf(
            "com.private.messages",
            "verification code",
            "123456",
            "person@example.com",
            "secret@example.com",
            "notification",
            "packageName",
            "account",
            "serial",
            "androidId",
            "logcat",
        ).forEach { forbidden ->
            assertFalse("payload leaked $forbidden", result.contains(forbidden, ignoreCase = true))
        }
        assertTrue(result.contains("\"versionName\": \"unknown\""))
    }

    @Test
    fun `build labels drop control characters and have a hard length cap`() {
        val result = JSONObject(
            RendererDiagnostics.format(
                status = HelperStatus(alive = false),
                selectedTransport = Transport.AUTO,
                activeTransport = Transport.ADB,
                appVersionName = "1.0.9",
                appVersionCode = 10,
                deviceModel = "Pixel\n11 Pro",
                buildId = "X".repeat(100),
                sdkInt = 37,
                capturedAtEpochMs = 123,
                capturedAtElapsedRealtimeMs = 456,
            )
        )

        val device = result.getJSONObject("device")
        assertEquals("Pixel11 Pro", device.getString("model"))
        assertEquals(80, device.getString("buildId").length)
    }

    @Test
    fun `canonical readback stage is retained and event age is never negative`() {
        val result = JSONObject(
            RendererDiagnostics.format(
                status = HelperStatus(
                    alive = false,
                    blackClearStage = "canonical_black_readback",
                    blackClearTimestampElapsedMs = 900,
                ),
                selectedTransport = Transport.ADB,
                activeTransport = Transport.ADB,
                appVersionName = "1.0.9",
                appVersionCode = 10,
                deviceModel = "Pixel 11 Pro",
                buildId = "A9.260820.001",
                sdkInt = 37,
                capturedAtEpochMs = 123,
                capturedAtElapsedRealtimeMs = 800,
            )
        )

        val cleanup = result.getJSONObject("cleanup")
        assertEquals("canonical_black_readback", cleanup.getString("stage"))
        assertEquals(0, cleanup.getLong("lastEventAgeMs"))
    }

    private fun JSONObject.objectKeys(name: String): Set<String> = getJSONObject(name).keySet()

    private fun JSONObject.keySet(): Set<String> = keys().asSequence().toSet()
}
