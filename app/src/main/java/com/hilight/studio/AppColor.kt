package com.hilight.studio

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import androidx.core.graphics.drawable.toBitmap
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Extracts a saturated dominant brand colour from an app's icon, suitable for display on the Pixel LED ring.
 */
object AppColor {

    private val colorCache = object : LinkedHashMap<String, Int>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Int>): Boolean = size > 64
    }

    /**
     * Resolves the app icon for [pkg] and extracts its dominant colour. Returns `null` if [pkg] is the
     * catch-all rule, invalid, or the application info/icon cannot be loaded.
     */
    fun extractAppColor(context: Context, pkg: String): Int? {
        if (pkg == AppRule.ANY_APP || pkg.isBlank()) return null
        synchronized(colorCache) {
            colorCache[pkg]?.let { return it }
        }
        return runCatching {
            val pm = context.packageManager
            val ai = pm.getApplicationInfo(pkg, 0)
            val icon = pm.getApplicationIcon(ai)
            val color = extractDominantColor(icon)
            synchronized(colorCache) { colorCache[pkg] = color }
            color
        }.getOrNull()
    }

    /**
     * Extracts a dominant colour from a [Drawable] by rendering it to a bitmap.
     */
    fun extractDominantColor(drawable: Drawable, width: Int = 64, height: Int = 64): Int {
        val bmp = drawable.toBitmap(width, height)
        return extractDominantColor(bmp)
    }

    /**
     * Extracts a dominant colour from a [Bitmap].
     */
    fun extractDominantColor(bitmap: Bitmap): Int {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        return extractDominantColor(pixels)
    }

    /**
     * Analyzes [pixels] (in ARGB_8888 format) using hue binning and saturation weighting.
     * Pure Kotlin implementation without Android framework dependencies so it runs in JVM unit tests.
     */
    internal fun extractDominantColor(pixels: IntArray): Int {
        if (pixels.isEmpty()) return AppRule.DEFAULT_COLOR

        val numBins = 36
        val binSize = 360f / numBins
        val binWeights = DoubleArray(numBins)
        val binSin = DoubleArray(numBins)
        val binCos = DoubleArray(numBins)
        val binSat = DoubleArray(numBins)
        val binVal = DoubleArray(numBins)

        val hsv = FloatArray(3)
        var totalValidPixels = 0
        var chromaticPixels = 0

        for (pixel in pixels) {
            val alpha = (pixel ushr 24) and 0xFF
            if (alpha < 128) continue
            totalValidPixels++

            rgbToHsv(pixel, hsv)
            val h = hsv[0]
            val s = hsv[1]
            val v = hsv[2]

            // Ignore near-black and washed-out greys/whites (s >= 0.18f filters out white/grey)
            if (s >= 0.18f && v >= 0.18f) {
                chromaticPixels++
                val binIndex = ((h / binSize).toInt() % numBins).coerceIn(0, numBins - 1)
                // Quadratic saturation weighting ensures vivid brand hues dominate over pale backgrounds
                val weight = (s * s * v).toDouble()
                val rad = Math.toRadians(h.toDouble())

                binWeights[binIndex] += weight
                binSin[binIndex] += sin(rad) * weight
                binCos[binIndex] += cos(rad) * weight
                binSat[binIndex] += s * weight
                binVal[binIndex] += v * weight
            }
        }

        // Predominantly monochrome or grayscale icons (e.g., GitHub, Uber, X) fall back to white light
        if (chromaticPixels == 0 || chromaticPixels < totalValidPixels * 0.02f) {
            return 0xFFFFFFFF.toInt()
        }

        // Smooth adjacent bins to prevent edge artifacts across bin boundaries
        var bestBin = 0
        var maxScore = -1.0
        for (i in 0 until numBins) {
            val prev = (i - 1 + numBins) % numBins
            val next = (i + 1) % numBins
            val score = binWeights[i] * 2.0 + binWeights[prev] + binWeights[next]
            if (score > maxScore) {
                maxScore = score
                bestBin = i
            }
        }

        val totalWeight = binWeights[bestBin]
        if (totalWeight <= 0.0) return 0xFFFFFFFF.toInt()

        val avgHue = ((Math.toDegrees(atan2(binSin[bestBin], binCos[bestBin])) % 360.0 + 360.0) % 360.0).toFloat()
        val avgSat = (binSat[bestBin] / totalWeight).toFloat()
        val avgVal = (binVal[bestBin] / totalWeight).toFloat()

        // Push saturation and intensity so container tones become vibrant on the LED ring
        val finalSat = avgSat.coerceAtLeast(0.70f)
        val finalVal = avgVal.coerceAtLeast(0.85f)

        return hsvToRgb(avgHue, finalSat, finalVal)
    }

    /** Converts RGB colour Int to HSV components: h in [0, 360), s in [0, 1], v in [0, 1]. */
    internal fun rgbToHsv(color: Int, outHsv: FloatArray) {
        val r = ((color shr 16) and 0xFF) / 255f
        val g = ((color shr 8) and 0xFF) / 255f
        val b = (color and 0xFF) / 255f

        val max = maxOf(r, maxOf(g, b))
        val min = minOf(r, minOf(g, b))
        val delta = max - min

        val v = max
        val s = if (max == 0f) 0f else delta / max

        val h = when {
            delta == 0f -> 0f
            max == r -> (60f * ((g - b) / delta) + 360f) % 360f
            max == g -> (60f * ((b - r) / delta) + 120f) % 360f
            else -> (60f * ((r - g) / delta) + 240f) % 360f
        }

        outHsv[0] = h
        outHsv[1] = s
        outHsv[2] = v
    }

    /** Converts HSV components (h in [0, 360), s in [0, 1], v in [0, 1]) to 0xFFRRGGBB ARGB Int. */
    internal fun hsvToRgb(h: Float, s: Float, v: Float): Int {
        val hNorm = ((h % 360f) + 360f) % 360f
        val sClamped = s.coerceIn(0f, 1f)
        val vClamped = v.coerceIn(0f, 1f)

        val c = vClamped * sClamped
        val x = c * (1f - abs((hNorm / 60f) % 2f - 1f))
        val m = vClamped - c

        val (r1, g1, b1) = when ((hNorm / 60f).toInt()) {
            0 -> Triple(c, x, 0f)
            1 -> Triple(x, c, 0f)
            2 -> Triple(0f, c, x)
            3 -> Triple(0f, x, c)
            4 -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }

        val r = ((r1 + m) * 255f + 0.5f).toInt().coerceIn(0, 255)
        val g = ((g1 + m) * 255f + 0.5f).toInt().coerceIn(0, 255)
        val b = ((b1 + m) * 255f + 0.5f).toInt().coerceIn(0, 255)

        return 0xFF000000.toInt() or (r shl 16) or (g shl 8) or b
    }
}
