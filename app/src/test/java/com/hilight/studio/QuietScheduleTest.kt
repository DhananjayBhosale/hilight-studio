package com.hilight.studio

import org.junit.Assert.*
import org.junit.Test

class QuietScheduleTest {
    private val off = List(7) { QuietDay(false) }

    @Test fun `Friday overnight continues into disabled Saturday but not Sunday`() {
        val days = off.toMutableList().apply { this[4] = QuietDay(true, 1380, 420) }
        assertFalse(isQuietAt(days, 4, 1379))
        assertTrue(isQuietAt(days, 4, 1380))
        assertTrue(isQuietAt(days, 5, 419))
        assertFalse(isQuietAt(days, 5, 420))
        assertFalse(isQuietAt(days, 6, 300))
    }

    @Test fun `Sunday carries into Monday and intervals exclude end`() {
        val days = off.toMutableList().apply { this[6] = QuietDay(true, 1200, 120); this[0] = QuietDay(true, 600, 660) }
        assertTrue(isQuietAt(days, 0, 119))
        assertFalse(isQuietAt(days, 0, 120))
        assertTrue(isQuietAt(days, 0, 600))
        assertFalse(isQuietAt(days, 0, 660))
    }

    @Test fun `equal times remain disabled and legacy daily schedule is preserved`() {
        assertFalse(isQuietAt(List(7) { QuietDay(true, 30, 30) }, 0, 30))
        val fallback = QuietDay(true, 1300, 400)
        val days = decodeQuietDays(null, fallback)
        assertEquals(List(7) { fallback }, days)
        for (day in 0..6) for (minute in 0..1439) {
            assertEquals(minute >= 1300 || minute < 400, isQuietAt(days, day, minute))
        }
    }

    @Test fun `schedule persistence round trips and corrupt values fall back safely`() {
        val days = List(7) { QuietDay(it % 2 == 0, 1200 + it, 300 + it) }
        assertEquals(days, decodeQuietDays(encodeQuietDays(days), QuietDay()))
        for (bad in listOf("", "1,0,10", List(7) { "1,-1,1500" }.joinToString(";"))) {
            assertEquals(List(7) { QuietDay() }, decodeQuietDays(bad, QuietDay()))
        }
    }

    @Test fun `scheduling ignores pose but exposes quiet and power guards before countdown`() {
        val held = GuardState(faceDownOnly = true, faceDown = false)
        assertNull(held.scheduledTestSuppressionReason())
        assertEquals(Suppression.NOT_FACE_DOWN, held.alertSuppression())
        assertEquals(Suppression.QUIET_HOURS, held.copy(quietEnabled = true, inQuietWindow = true).scheduledTestSuppressionReason())
        assertEquals(Suppression.POWER_SAVER, held.copy(powerSaveMode = true).scheduledTestSuppressionReason())
        assertEquals(Suppression.LOW_BATTERY, held.copy(batteryPct = 1).scheduledTestSuppressionReason())
    }
}
