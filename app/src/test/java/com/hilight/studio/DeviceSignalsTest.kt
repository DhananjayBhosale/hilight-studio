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
        assertFalse(settings.chargingGauge)
    }

    @Test fun chargingSwitchesAtConfiguredThreshold() {
        val settings = DeviceSignalSettings(fullPercent = 80, chargingColor = 123, chargedColor = 456)
        assertEquals(Pattern.BLINK, chargingSignalLook(settings, 79).pattern)
        assertEquals(123, chargingSignalLook(settings, 79).color)
        assertEquals(Pattern.SOLID, chargingSignalLook(settings, 80).pattern)
        assertEquals(456, chargingSignalLook(settings, 80).color)
        assertEquals(Pattern.SOLID, chargingSignalLook(settings, 100).pattern)
    }

    @Test fun gaugeUsesActualLevelIncludingPartialLedAndConfiguredFullColor() {
        val settings = DeviceSignalSettings(chargingColor = 0xFFFFFFFF.toInt(), chargedColor = 0xFF00FF00.toInt(), fullPercent = 80)
        val look = chargingGaugeLook(settings, 50)
        assertEquals(Pattern.CUSTOM, look.pattern)
        assertEquals(List(4) { 0xFFFFFFFF.toInt() } + List(4) { 0xFF000000.toInt() }, look.perLed)
        val partial = chargingGaugeLook(settings, 80).perLed
        assertEquals(List(6) { 0xFF00FF00.toInt() }, partial.take(6))
        assertTrue(((partial[6] ushr 8) and 255) in 101..102)
        assertEquals(0xFF000000.toInt(), partial[7])
        assertTrue(chargingGaugeLook(settings, -1).perLed.all { it == 0xFF000000.toInt() })
        assertTrue(chargingGaugeLook(settings, 101).perLed.all { it == settings.chargedColor })
    }

    @Test fun gaugeSignalsOnceOnPlugAndOnceAtFullWithoutRepeatedBroadcastsOrThresholdJitter() {
        val events = ChargingGaugeEvents()
        assertFalse(events.observe(false, 50, 80))
        assertTrue(events.observe(true, 50, 80))
        assertFalse(events.observe(true, 79, 80))
        assertTrue(events.observe(true, 80, 80))
        assertFalse(events.observe(true, 81, 80))
        assertFalse(events.observe(true, 79, 80))
        assertFalse(events.observe(true, 80, 80))
        assertFalse(events.observe(false, 80, 80))
        assertTrue(events.observe(true, 80, 80))
    }

    @Test fun gaugeDoesNotTreatRestartOrUnknownBatteryAsPlugEvent() {
        val events = ChargingGaugeEvents()
        assertFalse(events.observe(true, -1, 80))
        assertFalse(events.observe(true, 50, 80))
        events.reset()
        assertFalse(events.observe(true, 100, 80))
        assertFalse(events.observe(true, 100, 80))
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
