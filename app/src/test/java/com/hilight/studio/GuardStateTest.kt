package com.hilight.studio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GuardStateTest {
    @Test
    fun `nothing suppresses a healthy phone`() {
        assertNull(GuardState().suppression())
    }

    @Test
    fun `battery saver pauses the array at any level`() {
        assertEquals(
            Suppression.POWER_SAVER,
            GuardState(powerSaveMode = true, batteryPct = 90).suppression(),
        )
    }

    @Test
    fun `battery saver can be opted out of`() {
        assertNull(GuardState(saverGuard = false, powerSaveMode = true).suppression())
    }

    @Test
    fun `the default threshold only bites in single digits`() {
        assertNull(GuardState(batteryPct = 11).suppression())
        assertNull(GuardState(batteryPct = 10).suppression())
        assertEquals(Suppression.LOW_BATTERY, GuardState(batteryPct = 9).suppression())
    }

    @Test
    fun `the threshold is strictly below its label`() {
        val at20 = GuardState(batteryPct = 20, batteryMinPct = 20)
        assertNull(at20.suppression())
        assertEquals(Suppression.LOW_BATTERY, at20.copy(batteryPct = 19).suppression())
    }

    @Test
    fun `a user raised threshold is honoured`() {
        assertEquals(
            Suppression.LOW_BATTERY,
            GuardState(batteryPct = 22, batteryMinPct = 25).suppression(),
        )
    }

    @Test
    fun `charging bypasses the level guard but not battery saver`() {
        assertNull(GuardState(batteryPct = 100, batteryMinPct = 50).suppression())
        assertEquals(
            Suppression.POWER_SAVER,
            GuardState(batteryPct = 100, powerSaveMode = true).suppression(),
        )
    }

    @Test
    fun `the level guard can be turned off entirely`() {
        assertNull(GuardState(batteryGuard = false, batteryPct = 1).suppression())
    }

    @Test
    fun `screen-off-only outranks every power rule`() {
        assertEquals(
            Suppression.SCREEN_ON,
            GuardState(
                screenOffOnly = true,
                screenOn = true,
                powerSaveMode = true,
                batteryPct = 1,
            ).suppression(),
        )
    }

    @Test
    fun `a dimmed quiet window still lights`() {
        assertEquals(
            Suppression.QUIET_HOURS,
            GuardState(quietEnabled = true, inQuietWindow = true).suppression(),
        )
        assertNull(
            GuardState(quietEnabled = true, quietDim = true, inQuietWindow = true).suppression(),
        )
    }

    @Test
    fun `the screen-off-only switch darkens the always-on look but not a flash`() {
        val s = GuardState(screenOffOnly = true, screenOn = true)
        assertEquals(Suppression.SCREEN_ON, s.suppression())
        assertNull(s.alertSuppression())
    }

    @Test
    fun `quiet hours silence a flash too`() {
        val s = GuardState(quietEnabled = true, inQuietWindow = true)
        assertEquals(Suppression.QUIET_HOURS, s.alertSuppression())
    }

    @Test
    fun `battery saver silences a flash too`() {
        assertEquals(
            Suppression.POWER_SAVER,
            GuardState(powerSaveMode = true).alertSuppression(),
        )
    }

    @Test
    fun `a flat battery silences a flash too`() {
        assertEquals(
            Suppression.LOW_BATTERY,
            GuardState(batteryPct = 2).alertSuppression(),
        )
    }

    @Test
    fun `a dimmed quiet window still lets a flash through`() {
        val s = GuardState(quietEnabled = true, quietDim = true, inQuietWindow = true)
        assertNull(s.alertSuppression())
    }

    @Test
    fun `screen on plus a real reason still silences a flash`() {
        val s = GuardState(screenOffOnly = true, screenOn = true, powerSaveMode = true)
        assertEquals(Suppression.POWER_SAVER, s.alertSuppression())
    }

    @Test
    fun `quiet hours outrank battery saver`() {
        assertEquals(
            Suppression.QUIET_HOURS,
            GuardState(quietEnabled = true, inQuietWindow = true, powerSaveMode = true).suppression(),
        )
    }
}
