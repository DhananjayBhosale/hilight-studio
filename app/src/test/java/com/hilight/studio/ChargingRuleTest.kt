package com.hilight.studio

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChargingRuleTest {

    @Test
    fun `defaults are off, fire on both moments, and colour by level`() {
        val rule = ChargingRule()

        assertFalse(rule.enabled)
        assertTrue(rule.onPlugIn)
        assertTrue(rule.onFull)
        assertEquals(ChargingColorMode.LEVEL, rule.colorMode)
        assertEquals(ChargingRule.DEFAULT_DURATION_MS, rule.durationMs)
        assertEquals(0, rule.repeatEveryMin)
        assertEquals(LED_COUNT, rule.perLed.size)
    }

    @Test
    fun `preferences round trip keeps every field`() {
        val original = ChargingRule(
            enabled = true,
            onPlugIn = false,
            colorMode = ChargingColorMode.PER_LED,
            color = 0xFFFF1744.toInt(),
            perLed = List(LED_COUNT) { 0xFF000000.toInt() or (it + 1) },
            durationMs = 12_000,
            fill = ChargingFill.ALL,
            stepMs = 900,
            blinkTip = false,
            brightness = 0.6f,
            onlyWhenScreenOff = true,
            repeatEveryMin = 5,
            alternate = false,
            alternateLevel = 0.4f,
        )

        assertEquals(original, ChargingRule.fromJson(original.toPrefsJson()))
    }

    @Test
    fun `alternating dims every second led only while it is on`() {
        val on = ChargingRule(alternate = true, alternateLevel = 0.3f)
        assertEquals(0.3f, on.oddLedScale)
        assertEquals(0.3f, on.previewLook(1f).oddLedScale)
        assertEquals(0.3, Bridge.chargingAlertJson(1, on, 1f).getDouble("oddScale"), 1e-6)

        val off = on.copy(alternate = false)
        assertEquals(1f, off.oddLedScale)
        assertEquals(1.0, Bridge.chargingAlertJson(2, off, 1f).getDouble("oddScale"), 1e-6)

        // a rule saved before the option existed comes back alternating, the readable default
        assertTrue(ChargingRule.fromJson(JSONObject()).alternate)
        assertEquals(ChargingRule.MAX_ALTERNATE_LEVEL,
            ChargingRule.fromJson(JSONObject().put("alternateLevel", 5.0)).alternateLevel)
    }

    @Test
    fun `malformed values are clamped or replaced on load`() {
        val malformed = ChargingRule().toPrefsJson().apply {
            put("durationMs", 999_999)
            put("stepMs", 1)
            put("fill", "wobble")
            put("brightness", 9.0)
            put("repeatEveryMin", 999)
            put("colorMode", "plaid")
            put("perLed", org.json.JSONArray().put(1L).put(2L))
        }

        val loaded = ChargingRule.fromJson(malformed)
        assertEquals(Limits.RULE_MAX_MS, loaded.durationMs)
        assertEquals(ChargingRule.MIN_STEP_MS, loaded.stepMs)
        assertEquals(ChargingFill.STEP, loaded.fill)
        assertEquals(1f, loaded.brightness)
        assertEquals(ChargingRule.MAX_REPEAT_MIN, loaded.repeatEveryMin)
        assertEquals(ChargingColorMode.LEVEL, loaded.colorMode)
        assertEquals(ChargingRule.redToGreen(), loaded.perLed)
    }

    @Test
    fun `an empty preferences object is the default rule`() {
        assertEquals(ChargingRule(), ChargingRule.fromJson(JSONObject()))
    }

    @Test
    fun `a single colour preview lights every led the same`() {
        val red = 0xFFFF0000.toInt()
        val look = ChargingRule(colorMode = ChargingColorMode.SINGLE, color = red).previewLook(0.5f)

        assertEquals(Pattern.BATTERY, look.pattern)
        assertEquals(List(LED_COUNT) { red }, look.perLed)
        assertFalse(look.colorByLevel)
        assertEquals(0.5f, look.level)
    }

    @Test
    fun `a by-level preview says so and clamps the level`() {
        val look = ChargingRule().previewLook(3f)

        assertTrue(look.colorByLevel)
        assertEquals(1f, look.level)
    }

    @Test
    fun `red to green runs from pure red to pure green`() {
        val scale = ChargingRule.redToGreen()

        assertEquals(0xFFFF0000.toInt(), scale.first())
        assertEquals(0xFF00FF00.toInt(), scale.last())
    }

    @Test
    fun `the gauge alert carries the level and the colour mode`() {
        val perLed = ChargingRule(colorMode = ChargingColorMode.PER_LED)
        val json = Bridge.chargingAlertJson(7, perLed, 0.73f)

        assertEquals("battery", json.getString("pattern"))
        assertEquals("charging", json.getString("source"))
        assertEquals("step", json.getString("fill"))
        assertEquals(ChargingRule.DEFAULT_STEP_MS, json.getInt("speedMs"))
        assertTrue(json.getBoolean("blinkTip"))
        assertTrue(ChargingRule().previewLook(1f).blinkTip)
        assertFalse(Bridge.chargingAlertJson(10, perLed.copy(blinkTip = false), 1f).getBoolean("blinkTip"))
        assertEquals("all", Bridge.chargingAlertJson(9, perLed.copy(fill = ChargingFill.ALL), 1f).getString("fill"))
        assertEquals(0.73, json.getDouble("level"), 1e-6)
        assertFalse(json.getBoolean("byLevel"))
        assertEquals(LED_COUNT, json.getJSONArray("colors").length())

        val byLevel = Bridge.chargingAlertJson(8, ChargingRule(), 2f)
        assertTrue(byLevel.getBoolean("byLevel"))
        assertEquals(1.0, byLevel.getDouble("level"), 1e-6)
        assertFalse(byLevel.has("colors"))
    }
}
