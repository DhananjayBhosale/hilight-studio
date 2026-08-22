package com.hilight.studio

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivacyRuleTest {

    @Test
    fun `microphone and camera defaults are distinct and safe`() {
        val microphone = PrivacyRule.default(PrivacyActivity.MICROPHONE)
        val camera = PrivacyRule.default(PrivacyActivity.CAMERA)

        assertEquals(10_000, microphone.lightMs)
        assertEquals(10_000, microphone.cooldownMs)
        assertEquals(0xFFFF1744.toInt(), microphone.color)
        assertEquals(0xFF00E676.toInt(), camera.color)
    }

    @Test
    fun `preferences round trip keeps identity and clamps unsafe malformed timing`() {
        val original = PrivacyRule.default(
            activity = PrivacyActivity.MICROPHONE,
            pkg = "com.example.recorder",
            appLabel = "Recorder",
        ).copy(lightMs = 45_000, cooldownMs = 12_000, brightness = 0.6f)

        assertEquals(original, PrivacyRule.fromJson(original.toPrefsJson()))

        val malformed = original.toPrefsJson().apply {
            put("lightMs", 999_999)
            put("cooldownMs", -1)
            put("brightness", 9.0)
        }
        val clamped = PrivacyRule.fromJson(malformed)!!
        assertEquals(60_000, clamped.lightMs)
        assertEquals(1_000, clamped.cooldownMs)
        assertEquals(1f, clamped.brightness)
    }

    @Test
    fun `unknown future activity is skipped`() {
        val json = JSONObject().apply {
            put("activity", "location")
            put("pkg", AppRule.ANY_APP)
        }

        assertNull(PrivacyRule.fromJson(json))
    }

    @Test
    fun `every offered look and arbitrary colours reach preferences and renderer`() {
        // internal patterns such as the charging gauge need an input no rule can supply
        val expected = Pattern.entries.filter { it != Pattern.OFF && it != Pattern.CUSTOM && !it.internal }
        assertEquals(expected, PrivacyRule.selectablePatterns)
        assertTrue(PrivacyRule.selectablePatterns.containsAll(
            listOf(
                Pattern.BREATHE,
                Pattern.BLINK,
                Pattern.PULSE,
                Pattern.CHASE,
                Pattern.COMET,
                Pattern.WAVE,
                Pattern.RAINBOW,
                Pattern.RANDOM,
            )
        ))
        assertFalse(PrivacyRule.selectablePatterns.contains(Pattern.OFF))
        assertFalse(PrivacyRule.selectablePatterns.contains(Pattern.CUSTOM))
        assertFalse(PrivacyRule.selectablePatterns.contains(Pattern.BATTERY))

        PrivacyRule.selectablePatterns.forEach { pattern ->
            val original = PrivacyRule.default(PrivacyActivity.MICROPHONE).copy(
                pattern = pattern,
                color = 0xFF123456.toInt(),
                secondColor = 0xFFABCDEF.toInt(),
                lightMs = 27_000,
                cooldownMs = 13_000,
                speedMs = 2_345,
                brightness = 0.42f,
            )

            val restored = PrivacyRule.fromJson(original.toPrefsJson())!!
            assertEquals(original, restored)

            val renderer = restored.toRendererJson()
            assertEquals(pattern.key, renderer.getString("pattern"))
            assertEquals(27_000, renderer.getInt("lightMs"))
            assertEquals(13_000, renderer.getInt("cooldownMs"))
            assertEquals(2_345, renderer.getInt("speedMs"))
            assertEquals(0.42, renderer.getDouble("brightness"), 0.0001)
            if (pattern == Pattern.GRADIENT) {
                val colours = renderer.getJSONArray("colors")
                assertEquals(0xFF123456L, colours.getLong(0))
                assertEquals(0xFFABCDEFL, colours.getLong(1))
            } else {
                assertEquals(0xFF123456L, renderer.getLong("color"))
            }
        }
    }
}
