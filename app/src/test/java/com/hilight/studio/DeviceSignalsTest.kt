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
        assertEquals(Pattern.BLINK, settings.chargingPattern)
    }

    @Test fun callSignalKeepsOriginalPulseUntilAStyleIsChosen() {
        val settings = DeviceSignalSettings(callColor = 0xFF00FF00.toInt())
        assertEquals(Pattern.PULSE, settings.callPattern)
        val look = callSignalLook(settings)
        assertEquals(Pattern.PULSE, look.pattern)
        assertEquals(settings.callColor, look.color)
        assertEquals(1000, look.speedMs)
        assertEquals(Pattern.PULSE, callPatternOf(null))
        assertEquals(Pattern.PULSE, callPatternOf("future-style"))
        listOf(Pattern.OFF, Pattern.CUSTOM, Pattern.GRADIENT).forEach {
            assertEquals(Pattern.PULSE, callPatternOf(it.key))
        }
    }

    @Test fun selectedCallAnimationsReachRenderer() {
        callPatterns.forEach { pattern ->
            val settings = DeviceSignalSettings(callPattern = pattern, callColor = 0xFFFF0000.toInt())
            val look = callSignalLook(settings)
            assertEquals(pattern, callPatternOf(pattern.key))
            assertEquals(pattern.key, look.toJson().getString("mode"))
            assertEquals(settings.callColor, look.color)
            assertTrue(look.speedMs in 600..DeviceSignals.SIGNAL_DURATION_MS)
        }
    }

    @Test fun ringingCallRenewsItsAlertInsteadOfRestartingTheAnimation() {
        val look = callSignalLook(DeviceSignalSettings(callPattern = Pattern.WAVE))
        val first = Bridge.lookAlertJson(100, look, 2500, AlertSource.NOTIFICATION)
        val refresh = Bridge.lookAlertJson(2100, look, 2500, AlertSource.NOTIFICATION)
        val renewed = requireNotNull(Bridge.renewedAlertJson(first, refresh, 2100))
        assertEquals("the renderer keys restarts on the id", 100, renewed.getLong("id"))
        assertEquals(2100, renewed.getLong("renewal"))
        assertEquals(2500, renewed.getInt("durationMs"))

        val restyled = Bridge.lookAlertJson(
            2100, callSignalLook(DeviceSignalSettings(callPattern = Pattern.PULSE)), 2500,
            AlertSource.NOTIFICATION,
        )
        assertNull("a changed look must start a fresh alert", Bridge.renewedAlertJson(first, restyled, 2100))
    }

    @Test fun batteryLevelColorRunsFromRedThroughAmberToGreen() {
        assertEquals(0xFFFF0000.toInt(), batteryLevelColor(0))
        assertEquals(0xFFFFFF00.toInt(), batteryLevelColor(50))
        assertEquals(0xFF00FF00.toInt(), batteryLevelColor(100))
        assertEquals(batteryLevelColor(0), batteryLevelColor(-5))
        assertEquals(batteryLevelColor(100), batteryLevelColor(140))
        // gradual: red falls and green rises monotonically
        (0..99).forEach { p ->
            val a = batteryLevelColor(p); val b = batteryLevelColor(p + 1)
            assertTrue((b shr 8 and 255) >= (a shr 8 and 255))
            assertTrue((b shr 16 and 255) <= (a shr 16 and 255))
        }
    }

    @Test fun chargingColorFollowsLevelForStyleAndGaugeButFullStaysChargedColor() {
        val settings = DeviceSignalSettings(chargingColorByLevel = true, chargingPattern = Pattern.BREATHE, fullPercent = 90)
        assertEquals(batteryLevelColor(30), chargingSignalLook(settings, 30).color)
        assertEquals(Pattern.BREATHE, chargingSignalLook(settings, 30).pattern)
        assertEquals(settings.chargedColor, chargingSignalLook(settings, 95).color)
        val gaugeFirstLed = chargingGaugeLook(settings.copy(chargingGauge = true), 30).perLed.first()
        assertEquals(batteryLevelColor(30), gaugeFirstLed)
        // off by default: existing installs keep their chosen charging color
        assertEquals(DeviceSignalSettings().chargingColor, chargingSignalLook(DeviceSignalSettings(), 30).color)
    }

    @Test fun chargingStyleKeysRestoreSafelyAcrossUpgrades() {
        assertEquals(Pattern.BLINK, chargingPatternOf(null))
        assertEquals(Pattern.BLINK, chargingPatternOf("future-style"))
        listOf(Pattern.OFF, Pattern.CUSTOM, Pattern.GRADIENT).forEach {
            assertEquals(Pattern.BLINK, chargingPatternOf(it.key))
        }
        chargingPatterns.forEach { pattern ->
            assertEquals(pattern, chargingPatternOf(pattern.key))
        }
    }

    @Test fun selectedChargingAnimationsReachRendererAndFullStillUsesSteadyChargedColor() {
        chargingPatterns.forEach { pattern ->
            val settings = DeviceSignalSettings(chargingPattern = pattern,
                chargingColor = 0xFFFF0000.toInt(), chargedColor = 0xFF00FF00.toInt(), fullPercent = 80)
            val look = chargingSignalLook(settings, 79)
            assertEquals(pattern.key, look.toJson().getString("mode"))
            assertEquals(settings.chargingColor, look.color)
            assertTrue(look.speedMs in 600..DeviceSignals.SIGNAL_DURATION_MS)
            val full = chargingSignalLook(settings, 80)
            assertEquals(Pattern.SOLID, full.pattern)
            assertEquals(settings.chargedColor, full.color)
        }
    }

    @Test fun heartbeatAndRainbowFitTheBriefChargingWindow() {
        val settings = DeviceSignalSettings()
        assertEquals(600, chargingSignalLook(settings, 50).speedMs)
        assertEquals(1200, chargingSignalLook(settings.copy(chargingPattern = Pattern.HEARTBEAT), 50).speedMs)
        val rainbow = chargingSignalLook(settings.copy(chargingPattern = Pattern.RAINBOW), 50)
        assertEquals(2000, rainbow.speedMs)
        assertTrue(rainbow.toJson().getBoolean("spread"))
    }

    @Test fun chargingStyleDoesNotChangeTheBatteryGauge() {
        val original = DeviceSignalSettings(chargingGauge = true)
        chargingPatterns.forEach { pattern ->
            assertEquals(chargingGaugeLook(original, 55),
                chargingGaugeLook(original.copy(chargingPattern = pattern), 55))
        }
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
