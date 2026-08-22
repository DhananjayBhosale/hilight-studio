package com.hilight.studio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChargingPresetTest {

    @Test
    fun `every preset paints eight colours and is recognised once applied`() {
        ChargingPreset.entries.forEach { preset ->
            assertEquals(preset.name, LED_COUNT, preset.perLed.size)
            val applied = preset.applyTo(ChargingRule())
            assertTrue(preset.name, preset.matches(applied))
            assertEquals(preset.name, preset, ChargingPreset.matching(applied))
        }
    }

    @Test
    fun `the default rule is the traffic light`() {
        assertEquals(ChargingPreset.TRAFFIC_LIGHT, ChargingPreset.matching(ChargingRule()))
    }

    @Test
    fun `a tweaked colour turns a preset into a custom look`() {
        val fire = ChargingPreset.FIRE.applyTo(ChargingRule())
        val tweaked = fire.copy(perLed = fire.perLed.toMutableList().also { it[3] = 0xFF0000FF.toInt() })

        assertNull(ChargingPreset.matching(tweaked))

        val ice = ChargingPreset.ICE.applyTo(ChargingRule())
        assertNull(ChargingPreset.matching(ice.copy(color = 0xFF123456.toInt())))
    }

    @Test
    fun `presets leave the fields they do not speak for alone`() {
        val custom = ChargingRule(durationMs = 20_000, brightness = 0.4f, onlyWhenScreenOff = true)

        val applied = ChargingPreset.OCEAN.applyTo(custom)
        assertEquals(20_000, applied.durationMs)
        assertEquals(0.4f, applied.brightness)
        assertTrue(applied.onlyWhenScreenOff)

        // the by-level preset does not wipe hand-picked per-LED colours either
        val perLed = ChargingPreset.SUNSET.applyTo(custom)
        assertEquals(perLed.perLed, ChargingPreset.TRAFFIC_LIGHT.applyTo(perLed).perLed)
    }

    @Test
    fun `single colour presets carry their colour into the rule`() {
        val white = ChargingPreset.WHITE.applyTo(ChargingRule())

        assertEquals(ChargingColorMode.SINGLE, white.colorMode)
        assertEquals(0xFFFFFFFF.toInt(), white.color)
        assertEquals(List(LED_COUNT) { 0xFFFFFFFF.toInt() }, white.previewLook(1f).perLed)
    }

    @Test
    fun `gradients run between their end hues`() {
        val fire = ChargingPreset.FIRE.perLed
        assertEquals(Renderer.hsv(0f), fire.first())
        assertEquals(Renderer.hsv(50f), fire.last())

        // a sweep past 360 wraps instead of overflowing the hue wheel
        val sunset = ChargingPreset.SUNSET.perLed
        assertEquals(Renderer.hsv(300f), sunset.first())
        assertEquals(Renderer.hsv(40f), sunset.last())
    }

    @Test
    fun `the by-level miniature is shown part way up`() {
        assertEquals(0.6f, ChargingPreset.TRAFFIC_LIGHT.previewLook().level)
        assertEquals(1f, ChargingPreset.OCEAN.previewLook().level)
    }

    @Test
    fun `miniatures are still even though the rule counts one by one`() {
        ChargingPreset.entries.forEach {
            assertEquals(it.name, false, it.previewLook().fillStepwise)
            assertEquals(it.name, false, it.previewLook().blinkTip)
            assertEquals(it.name, false, it.previewLook().fullGreen)
        }
        // the rule itself keeps its counting mode when a preset is applied
        assertEquals(ChargingFill.STEP, ChargingPreset.OCEAN.applyTo(ChargingRule()).fill)
    }
}
