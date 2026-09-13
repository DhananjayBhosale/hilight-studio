package com.hilight.studio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class AppColorTest {

    @Test
    fun rgbToHsvPrimaryColors() {
        val hsv = FloatArray(3)

        AppColor.rgbToHsv(0xFFFF0000.toInt(), hsv)
        assertEquals(0f, hsv[0], 0.5f)
        assertEquals(1f, hsv[1], 0.01f)
        assertEquals(1f, hsv[2], 0.01f)

        AppColor.rgbToHsv(0xFF00FF00.toInt(), hsv)
        assertEquals(120f, hsv[0], 0.5f)
        assertEquals(1f, hsv[1], 0.01f)
        assertEquals(1f, hsv[2], 0.01f)

        AppColor.rgbToHsv(0xFF0000FF.toInt(), hsv)
        assertEquals(240f, hsv[0], 0.5f)
        assertEquals(1f, hsv[1], 0.01f)
        assertEquals(1f, hsv[2], 0.01f)

        AppColor.rgbToHsv(0xFFFFFFFF.toInt(), hsv)
        assertEquals(0f, hsv[1], 0.01f)
        assertEquals(1f, hsv[2], 0.01f)

        AppColor.rgbToHsv(0xFF000000.toInt(), hsv)
        assertEquals(0f, hsv[2], 0.01f)
    }

    @Test
    fun hsvToRgbPrimaryColors() {
        assertEquals(0xFFFF0000.toInt(), AppColor.hsvToRgb(0f, 1f, 1f))
        assertEquals(0xFF00FF00.toInt(), AppColor.hsvToRgb(120f, 1f, 1f))
        assertEquals(0xFF0000FF.toInt(), AppColor.hsvToRgb(240f, 1f, 1f))
        assertEquals(0xFFFFFFFF.toInt(), AppColor.hsvToRgb(0f, 0f, 1f))
        assertEquals(0xFF000000.toInt(), AppColor.hsvToRgb(0f, 0f, 0f))
    }

    @Test
    fun extractDominantColorPureRed() {
        val pixels = IntArray(100) { 0xFFFF0000.toInt() }
        val dominant = AppColor.extractDominantColor(pixels)
        val hsv = FloatArray(3)
        AppColor.rgbToHsv(dominant, hsv)

        // Saturated red: hue near 0 or 360, high saturation, high value
        assertTrue("Hue should be red (${hsv[0]})", hsv[0] <= 15f || hsv[0] >= 345f)
        assertTrue("Saturation should be high (${hsv[1]})", hsv[1] >= 0.70f)
        assertTrue("Value should be high (${hsv[2]})", hsv[2] >= 0.85f)
    }

    @Test
    fun extractDominantColorPureGreen() {
        val pixels = IntArray(100) { 0xFF00E676.toInt() }
        val dominant = AppColor.extractDominantColor(pixels)
        val hsv = FloatArray(3)
        AppColor.rgbToHsv(dominant, hsv)

        assertTrue("Hue should be green (${hsv[0]})", hsv[0] in 120f..160f)
        assertTrue("Saturation should be high (${hsv[1]})", hsv[1] >= 0.70f)
        assertTrue("Value should be high (${hsv[2]})", hsv[2] >= 0.85f)
    }

    @Test
    fun extractDominantColorWithWhiteBackground() {
        // Icon with 75% white background and 25% vibrant blue logo (e.g. Telegram / Twitter)
        val blue = 0xFF1DA1F2.toInt()
        val white = 0xFFFFFFFF.toInt()
        val pixels = IntArray(100) { i -> if (i < 75) white else blue }

        val dominant = AppColor.extractDominantColor(pixels)
        val hsv = FloatArray(3)
        AppColor.rgbToHsv(dominant, hsv)

        // Should extract the blue hue, ignoring white background
        assertTrue("Dominant hue should be blue (${hsv[0]})", hsv[0] in 190f..220f)
        assertTrue("Saturation should be boosted for LED (${hsv[1]})", hsv[1] >= 0.70f)
    }

    @Test
    fun extractDominantColorWithBlackBackground() {
        // Icon with 70% black background and 30% green logo (e.g. Spotify)
        val green = 0xFF1DB954.toInt()
        val black = 0xFF121212.toInt()
        val pixels = IntArray(100) { i -> if (i < 70) black else green }

        val dominant = AppColor.extractDominantColor(pixels)
        val hsv = FloatArray(3)
        AppColor.rgbToHsv(dominant, hsv)

        // Should extract the green hue, ignoring black background
        assertTrue("Dominant hue should be green (${hsv[0]})", hsv[0] in 120f..160f)
        assertTrue("Saturation should be high (${hsv[1]})", hsv[1] >= 0.70f)
    }

    @Test
    fun extractDominantColorIgnoresTransparentPixels() {
        // 80% transparent pixels, 20% purple pixels
        val purple = 0xFF7C4DFF.toInt()
        val transparent = 0x00000000
        val pixels = IntArray(100) { i -> if (i < 80) transparent else purple }

        val dominant = AppColor.extractDominantColor(pixels)
        val hsv = FloatArray(3)
        AppColor.rgbToHsv(dominant, hsv)

        assertTrue("Dominant hue should be purple (${hsv[0]})", hsv[0] in 240f..280f)
    }

    @Test
    fun extractDominantColorMonochromeFallsBackToWhite() {
        // Pure black and white icon (e.g. GitHub or X)
        val pixels = IntArray(100) { i -> if (i < 50) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
        val dominant = AppColor.extractDominantColor(pixels)

        assertEquals("Monochrome icon should fall back to white for LED visibility", 0xFFFFFFFF.toInt(), dominant)
    }

    @Test
    fun extractDominantColorEmptyInput() {
        val dominant = AppColor.extractDominantColor(IntArray(0))
        assertEquals(AppRule.DEFAULT_COLOR, dominant)
    }

    @Test
    fun nextWholeAppRuleCustomInitialColor() {
        val rule = nextWholeAppRule("com.example.app", "Example", emptyList(), initialColor = 0xFF123456.toInt())
        assertEquals(0xFF123456.toInt(), rule?.color)
    }

    @Test
    fun appRuleAppColorJsonRoundTrip() {
        val rule = AppRule(
            pkg = AppRule.ANY_APP,
            label = "Any app",
            pattern = Pattern.PULSE,
            appColor = true,
            color = 0xFF2979FF.toInt(),
        )
        val json = rule.toPrefsJson()
        assertTrue(json.getBoolean("appColor"))
        val restored = AppRule.fromJson(json)
        assertTrue(restored.appColor)
        assertTrue(restored.isCatchAll)
        assertEquals(0xFF2979FF.toInt(), restored.color)
    }

    @Test
    fun withLookResetsAppColor() {
        val rule = AppRule("com.example", "Example", appColor = true)
        val look = Ambient(pattern = Pattern.SOLID, color = 0xFFFF0000.toInt())
        val updated = rule.withLook(look)
        org.junit.Assert.assertFalse(updated.appColor)
        assertEquals(0xFFFF0000.toInt(), updated.color)
    }
}


