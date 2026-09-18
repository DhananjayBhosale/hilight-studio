package com.hilight.studio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppColorTest {
    @Test fun ignoresBackgroundAndTransparentPixels() {
        val brand = 0xFF24B070.toInt()
        val pixels = IntArray(100) { if (it < 80) -1 else brand } +
            IntArray(100) { 0x00FF0000 }
        assertEquals(brand, dominantIconColor(pixels))
    }

    @Test fun monochromeAndMissingArtworkFallBackToManualColor() {
        assertNull(dominantIconColor(intArrayOf(-1, 0xFF808080.toInt(), 0xFF101010.toInt(), 0)))
        assertNull(dominantIconColor(intArrayOf()))
        assertNull(dominantIconColor(IntArray(2304) { if (it == 0) 0xFFFF0000.toInt() else -1 }))
    }

    @Test fun choosesMostCommonColorWithoutMixingDistinctBrandColors() {
        val red = 0xFFFF2020.toInt()
        val blue = 0xFF2040FF.toInt()
        assertEquals(red, dominantIconColor(IntArray(100) { if (it < 70) red else blue }))
    }
}
