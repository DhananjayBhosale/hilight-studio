package com.hilight.studio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GlyphPatternsTest {
    private val patterns = listOf(Pattern.METER, Pattern.STROBE, Pattern.HEARTBEAT,
        Pattern.BOUNCE, Pattern.RADAR, Pattern.CONVERGE, Pattern.GLITCH)
    private val core = com.hilight.core.Renderer()

    @Test
    fun `new patterns render the same preview and device frames across timing and brightness`() {
        for (pattern in patterns) for (speed in listOf(60, 1000, 3370)) {
            for (brightness in listOf(0f, 0.4f, 1f)) {
                val look = Ambient(pattern = pattern, speedMs = speed, brightness = brightness,
                    color = 0xFF20E676.toInt())
                for (time in (0L..7000L step 37L) + listOf(60_000L, 8_000_000_000L)) {
                    assertArrayEquals("$pattern speed=$speed brightness=$brightness time=$time",
                        core.frame(look.toJson(), time, LED_COUNT), Renderer.frame(pattern, time, look))
                }
                assertEquals(look, Ambient.fromJson(look.toPrefsJson()))
            }
        }
    }

    private fun frame(pattern: Pattern, time: Long): IntArray = core.frame(
        Ambient(pattern = pattern, color = 0xFFFFFFFF.toInt(), brightness = 1f, speedMs = 1000).toJson(),
        time, LED_COUNT,
    )
    private fun lit(frame: IntArray) = frame.count { it and 0xFFFFFF != 0 }

    @Test
    fun `meter fills progressively and pulsed patterns include a dark rest`() {
        assertEquals(0, lit(frame(Pattern.METER, 0)))
        assertEquals(4, lit(frame(Pattern.METER, 375)))
        assertEquals(8, lit(frame(Pattern.METER, 750)))
        assertEquals(0, lit(frame(Pattern.METER, 1000)))
        assertEquals(8, lit(frame(Pattern.STROBE, 0)))
        assertEquals(0, lit(frame(Pattern.STROBE, 500)))
        assertTrue(lit(frame(Pattern.HEARTBEAT, 60)) > 0)
        assertEquals(0, lit(frame(Pattern.HEARTBEAT, 650)))
    }

    @Test
    fun `bounce returns to its origin and converge remains symmetric`() {
        assertArrayEquals(frame(Pattern.BOUNCE, 0).reversedArray(), frame(Pattern.BOUNCE, 500))
        assertArrayEquals(frame(Pattern.BOUNCE, 0), frame(Pattern.BOUNCE, 1000))
        for (time in 0L..1000L step 25L) {
            val frame = frame(Pattern.CONVERGE, time)
            assertArrayEquals(frame.reversedArray(), frame)
        }
    }
}
