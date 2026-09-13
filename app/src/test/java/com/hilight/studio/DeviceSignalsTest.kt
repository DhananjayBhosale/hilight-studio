package com.hilight.studio

import android.app.NotificationManager
import org.junit.Assert.*
import org.junit.Test

class DeviceSignalsTest {
    @Test fun existingInstallationsDoNotEnableSignals() {
        val settings = DeviceSignalSettings()
        assertFalse(settings.chargingEnabled)
        assertFalse(settings.dndEnabled)
        assertFalse(settings.callsEnabled)
    }

    @Test fun chargingSwitchesAtConfiguredThreshold() {
        val settings = DeviceSignalSettings(fullPercent = 80, chargingColor = 123, chargedColor = 456)
        assertEquals(Pattern.BLINK, chargingSignalLook(settings, 79).pattern)
        assertEquals(123, chargingSignalLook(settings, 79).color)
        assertEquals(Pattern.SOLID, chargingSignalLook(settings, 80).pattern)
        assertEquals(456, chargingSignalLook(settings, 80).color)
        assertEquals(Pattern.SOLID, chargingSignalLook(settings, 100).pattern)
    }

    @Test fun dndOnlySignalsOffToOnTransitions() {
        val all = NotificationManager.INTERRUPTION_FILTER_ALL
        val priority = NotificationManager.INTERRUPTION_FILTER_PRIORITY
        val none = NotificationManager.INTERRUPTION_FILTER_NONE
        val alarms = NotificationManager.INTERRUPTION_FILTER_ALARMS
        assertFalse(dndWasActivated(null, priority))
        assertFalse(dndWasActivated(NotificationManager.INTERRUPTION_FILTER_UNKNOWN, priority))
        assertTrue(dndWasActivated(all, priority))
        assertTrue(dndWasActivated(all, none))
        assertTrue(dndWasActivated(all, alarms))
        assertFalse(dndWasActivated(priority, none))
        assertFalse(dndWasActivated(none, none))
        assertFalse(dndWasActivated(none, all))
    }
    @Test fun unknownDndStateStaysSuppressedUntilFreshOffObservation() {
        val all = NotificationManager.INTERRUPTION_FILTER_ALL
        val priority = NotificationManager.INTERRUPTION_FILTER_PRIORITY
        val unknown = NotificationManager.INTERRUPTION_FILTER_UNKNOWN
        // Startup and listener loss must not allow the independent charging timer to light up.
        assertTrue(dndBlocksSignals(null))
        val observations = listOf(priority, unknown, priority, all)
        assertEquals(listOf(true, true, true, false), observations.map(::dndBlocksSignals))
        // Reconnection only restores the current state; it is not a new activation.
        assertFalse(dndWasActivated(unknown, priority))
        assertTrue(dndWasActivated(all, priority))
    }

}
