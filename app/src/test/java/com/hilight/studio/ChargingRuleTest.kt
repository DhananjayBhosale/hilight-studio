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
            fullGreen = false,
            fullColor = 0xFF2979FF.toInt(),
            plays = 3,
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
            put("plays", 99)
            put("colorMode", "plaid")
            put("perLed", org.json.JSONArray().put(1L).put(2L))
        }

        val loaded = ChargingRule.fromJson(malformed)
        assertEquals(Limits.RULE_MAX_MS, loaded.durationMs)
        assertEquals(ChargingRule.MIN_STEP_MS, loaded.stepMs)
        assertEquals(ChargingFill.STEP, loaded.fill)
        assertEquals(1f, loaded.brightness)
        assertEquals(ChargingRule.MAX_REPEAT_MIN, loaded.repeatEveryMin)
        assertEquals(ChargingRule.MAX_PLAYS, loaded.plays)
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
    fun `a counting gauge lasts exactly its plays and never a trailing gap`() {
        // default rule: 400 ms steps, tip blink on, two plays. At 50% four LEDs are lit, so one
        // cycle is 1600 count + 1200 hold + 1800 blink + 400 gap = 5000 ms
        val rule = ChargingRule()
        assertEquals(2 * 5000 - 400, rule.showingMs(50))
        assertEquals(3 * 5000 - 400, rule.copy(plays = 3).showingMs(50))
        // without the blink the hold alone remains: 1600 + 1200 + 400 = 3200 per cycle
        assertEquals(2 * 3200 - 400, rule.copy(blinkTip = false).showingMs(50))
        // a partly-lit boundary LED still counts as a step: 73% is 5.84 LEDs, six steps
        assertEquals(2 * (6 * 400 + 1200 + 1800 + 400) - 400, rule.showingMs(73))
    }

    @Test
    fun `a full battery counts once and then holds for the show-for time`() {
        val rule = ChargingRule(durationMs = 5_000)
        assertEquals(8 * 400 + 5_000, rule.showingMs(100))
        // without the solid ring a full battery just plays its counts like any other level
        val counting = rule.copy(fullGreen = false)
        assertEquals(2 * (8 * 400 + 1200 + 1800 + 400) - 400, counting.showingMs(100))
    }

    @Test
    fun `a static gauge simply shows for its duration`() {
        val rule = ChargingRule(fill = ChargingFill.ALL, durationMs = 6_000)
        assertEquals(6_000, rule.showingMs(50))
        assertEquals(6_000, rule.showingMs(100))
    }

    @Test
    fun `a showing never exceeds the rule ceiling`() {
        val slow = ChargingRule(stepMs = ChargingRule.MAX_STEP_MS, plays = ChargingRule.MAX_PLAYS, fullGreen = false)
        assertTrue(slow.showingMs(100) <= Limits.RULE_MAX_MS)
        assertTrue(slow.showingMs(100) >= ChargingRule.MIN_DURATION_MS)
        // the cycle the app sizes from is the renderer's own
        assertEquals(
            8 * 1000 + 3000 + 1800 + 1000,
            com.hilight.core.Renderer.gaugeCycleMs(8, 1000, true),
        )
    }

    @Test
    fun `the gauge alert carries the level and the colour mode`() {
        val perLed = ChargingRule(colorMode = ChargingColorMode.PER_LED)
        val json = Bridge.chargingAlertJson(7, perLed, 0.73f)

        assertEquals("battery", json.getString("pattern"))
        assertEquals("charging", json.getString("source"))
        // the showing is sized from the animation at that level unless told otherwise
        assertEquals(perLed.showingMs(73), json.getInt("durationMs"))
        assertEquals(4_321, Bridge.chargingAlertJson(7, perLed, 0.73f, 4_321).getInt("durationMs"))
        assertEquals("step", json.getString("fill"))
        assertEquals(ChargingRule.DEFAULT_STEP_MS, json.getInt("speedMs"))
        assertTrue(json.getBoolean("blinkTip"))
        assertTrue(json.getBoolean("fullGreen"))
        assertEquals(ChargingRule.DEFAULT_FULL_COLOR.toUInt().toLong(), json.getLong("fullColor"))
        assertTrue(ChargingRule().previewLook(1f).blinkTip)
        assertTrue(ChargingRule().previewLook(1f).fullGreen)
        assertEquals(ChargingRule.DEFAULT_FULL_COLOR, ChargingRule().previewLook(1f).fullColor)
        val blue = perLed.copy(fullColor = 0xFF2979FF.toInt())
        assertEquals(0xFF2979FFL, Bridge.chargingAlertJson(12, blue, 1f).getLong("fullColor"))
        assertEquals(0xFF2979FF.toInt(), blue.previewLook(1f).fullColor)
        assertFalse(Bridge.chargingAlertJson(11, perLed.copy(fullGreen = false), 1f).getBoolean("fullGreen"))
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
