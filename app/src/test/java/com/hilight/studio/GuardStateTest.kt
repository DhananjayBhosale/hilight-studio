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
    fun `face down gate defaults off and fails closed when enabled`() {
        assertNull(GuardState(faceDown = false).suppression())
        assertEquals(
            Suppression.NOT_FACE_DOWN,
            GuardState(faceDownOnly = true, faceDown = false).suppression(),
        )
        assertNull(GuardState(faceDownOnly = true, faceDown = true).suppression())
    }

    @Test
    fun `global face down gate covers notification and privacy suppression`() {
        val state = GuardState(faceDownOnly = true, faceDown = false)

        assertEquals(Suppression.NOT_FACE_DOWN, state.alertSuppression())
    }

    @Test
    fun `screen off explanation keeps its existing priority`() {
        assertEquals(
            Suppression.SCREEN_ON,
            GuardState(
                screenOffOnly = true,
                screenOn = true,
                faceDownOnly = true,
                faceDown = false,
            ).suppression(),
        )
    }

    @Test
    fun `manual preview bypasses only face down gate`() {
        val faceGate = GuardState(faceDownOnly = true, faceDown = false)
        val batteryGate = faceGate.copy(batteryPct = 1)

        assertNull(faceGate.outputSuppression(true, AlertSource.PREVIEW))
        assertEquals(
            Suppression.NOT_FACE_DOWN,
            faceGate.outputSuppression(true, AlertSource.NOTIFICATION),
        )
        assertEquals(
            Suppression.LOW_BATTERY,
            batteryGate.outputSuppression(true, AlertSource.PREVIEW),
        )
        assertNull(faceGate.previewSuppressionReason())
        assertEquals(Suppression.LOW_BATTERY, batteryGate.previewSuppressionReason())
    }

    @Test
    fun `preview warnings cover quiet hours saver and low battery`() {
        assertEquals(
            Suppression.QUIET_HOURS,
            GuardState(quietEnabled = true, inQuietWindow = true).previewSuppressionReason(),
        )
        assertEquals(
            Suppression.POWER_SAVER,
            GuardState(powerSaveMode = true).previewSuppressionReason(),
        )
        assertEquals(
            Suppression.LOW_BATTERY,
            GuardState(batteryPct = 1).previewSuppressionReason(),
        )
        assertNull(
            GuardState(
                quietEnabled = true,
                quietDim = true,
                inQuietWindow = true,
            ).previewSuppressionReason(),
        )
    }

    @Test
    fun `settings suppression reasons route to their owning section`() {
        assertEquals(SettingsSuppressionSection.GLOW, Suppression.SCREEN_ON.settingsSection())
        assertEquals(SettingsSuppressionSection.GLOW, Suppression.NOT_FACE_DOWN.settingsSection())
        assertEquals(SettingsSuppressionSection.PAUSE, Suppression.QUIET_HOURS.settingsSection())
        assertEquals(SettingsSuppressionSection.PAUSE, Suppression.POWER_SAVER.settingsSection())
        assertEquals(SettingsSuppressionSection.PAUSE, Suppression.LOW_BATTERY.settingsSection())
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
        // the reported case: 10 or 11 percent off the charger used to go dark at the old 20 default
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
        // Store maps a charging phone to 100, so the level guard cannot fire
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

    // ---------------------------------------------------------------- notification flashes

    @Test
    fun `the screen-off-only switch darkens the always-on look but not a flash`() {
        val s = GuardState(screenOffOnly = true, screenOn = true)
        assertEquals(Suppression.SCREEN_ON, s.suppression())
        assertNull(s.alertSuppression())
        assertNull(s.outputSuppression(true, AlertSource.NOTIFICATION))
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
        // the exemption is for the screen switch alone, not a blanket pass
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
