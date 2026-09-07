package com.hilight.studio

import org.junit.Assert.*
import org.junit.Test

class PendingNotificationRemindersTest {
    @Test fun newestPendingWinsAndDismissalRestoresPrevious() {
        val queue = PendingNotificationReminders()
        queue.posted("old", "r1", 100, 1000, 15_000)
        queue.posted("new", "r2", 200, 1100, 5000)
        assertEquals("new", queue.latest()?.key)
        assertEquals(6100L, queue.latest()?.dueAtMs)
        queue.remove("new")
        assertEquals("old", queue.latest()?.key)
        queue.retain(emptySet())
        assertNull(queue.latest())
    }

    @Test fun repostReplacesOneEntryAndUnlockClearsEverything() {
        val queue = PendingNotificationReminders()
        queue.posted("same", "r", 100, 1000, 15_000)
        queue.posted("same", "updated", 200, 2000, 15_000)
        assertEquals("updated", queue.latest()?.ruleId)
        queue.defer("same", 20_000, 10_000)
        assertEquals(30_000L, queue.latest()?.dueAtMs)
        queue.clear()
        assertNull(queue.latest())
    }

    @Test fun receiptOrderWinsAcrossWallClockCorrectionsAndEqualTimestamps() {
        val queue = PendingNotificationReminders()
        queue.posted("before", "r", 1_300_000_000, 1000, 5000)
        queue.posted("after", "r", 100_000, 2000, 5000)
        assertEquals("after", queue.latest()?.key)
        queue.posted("equal", "r", 100_000, 2000, 5000)
        assertEquals("equal", queue.latest()?.key)
    }

    @Test fun intervalsStayBoundedAndNoKeyIsNotTracked() {
        val queue = PendingNotificationReminders()
        queue.posted("", "r", 0, 0, 1)
        assertNull(queue.latest())
        queue.posted("key", "r", 0, 0, 1)
        assertEquals(5000L, queue.latest()?.dueAtMs)
        queue.defer("key", 0, Int.MAX_VALUE)
        assertEquals(60_000L, queue.latest()?.dueAtMs)
    }

    @Test fun onlyExplicitIncomingCallMarkerQualifies() {
        assertTrue(isIncomingCallType(1))
        for (type in listOf(0, 2, 3, -1, 99)) assertFalse(isIncomingCallType(type))
    }

    @Test fun silentChannelsIncludeLowImportanceAndNoSoundOrVibration() {
        assertTrue(isSilentNotification(2, true, true))
        assertTrue(isSilentNotification(3, false, false))
        assertFalse(isSilentNotification(3, true, false))
        assertFalse(isSilentNotification(3, false, true))
        assertFalse(isSilentNotification(null, false, false))
        assertFalse(isSilentNotification(-1000, false, false))
    }

    @Test fun fullGradientLookReachesRendererAndLegacyRandomTimingIsPreserved() {
        val look = Ambient(pattern = Pattern.GRADIENT, color = 0xFFFF0000.toInt(), secondColor = 0xFF0000FF.toInt())
        val json = Bridge.lookAlertJson(123, look, 4000, AlertSource.NOTIFICATION)
        assertEquals("gradient", json.getString("pattern"))
        assertFalse(json.has("mode"))
        assertEquals(2, json.getJSONArray("colors").length())
        assertEquals(0xFF0000FFL, json.getJSONArray("colors").getLong(1))
        assertEquals(4000, json.getInt("durationMs"))
        assertEquals(500, AppRule("pkg", "App", pattern = Pattern.RANDOM).effectiveLook().randomIntervalMs)
        val legacyGradient = AppRule("pkg", "App", pattern = Pattern.GRADIENT, color = 0xFFFF0000.toInt())
        assertEquals(legacyGradient.color, legacyGradient.effectiveLook().secondColor)
        assertEquals(0xFF00FF00.toInt(), legacyGradient.effectiveLook(0xFF00FF00.toInt()).secondColor)
    }
}
