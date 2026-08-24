package com.hilight.studio

import androidx.compose.ui.graphics.Color
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin

/**
 * On-screen mirror of the helper's renderer, so the UI can show what the LEDs will do without
 * touching the hardware. Kept deliberately in step with HiLightHelper.render().
 */
object Renderer {

    /** Dimmest a partly-filled gauge LED is drawn; the same figure as the helper's PARTIAL_FLOOR. */
    private const val PARTIAL_FLOOR = 0.1

    /** Shortest hold between two one-by-one counts; the helper's STEP_HOLD_MIN_MS. */
    private const val STEP_HOLD_MIN_MS = 1200L

    /** The tip LED's blink, as in the helper: three blinks of 300 ms on, 300 ms off, then lit. */
    private const val TIP_BLINKS = 3
    private const val TIP_BLINK_MS = 300L

    private fun tipBlinkOff(sinceMs: Long, totalMs: Long): Boolean =
        sinceMs < totalMs && (sinceMs / TIP_BLINK_MS) % 2 == 1L

    /** The level that counts as full; the helper's FULL_LEVEL. */
    private const val FULL_LEVEL = 0.999

    fun frame(pattern: Pattern, tMs: Long, cfg: Ambient, colorOverride: Int? = null): IntArray {
        val n = LED_COUNT
        val out = IntArray(n)
        val base = colorOverride ?: cfg.color
        val speed = max(60, cfg.speedMs).toLong()
        val t = tMs

        when (pattern) {
            Pattern.OFF -> Unit
            Pattern.SOLID -> for (i in 0 until n) out[i] = base
            Pattern.CUSTOM -> for (i in 0 until n) out[i] = cfg.perLed[i % cfg.perLed.size]
            Pattern.GRADIENT -> for (i in 0 until n)
                out[i] = mix(base, cfg.secondColor, i.toDouble() / (n - 1))

            Pattern.BREATHE -> {
                val phase = (t % speed) / speed.toDouble()
                val k = (1 - cos(phase * 2 * PI)) / 2
                for (i in 0 until n) out[i] = scale(base, 0.05 + 0.95 * k)
            }

            Pattern.BLINK -> {
                if ((t % speed) < speed / 2) for (i in 0 until n) out[i] = base
            }

            Pattern.PULSE -> {
                val phase = (t % speed) / speed.toDouble()
                val k = if (phase < 0.12) phase / 0.12 else exp(-(phase - 0.12) * 5)
                for (i in 0 until n) out[i] = scale(base, k)
            }

            Pattern.CHASE -> {
                val head = ((t / max(1, speed / n)) % n).toInt()
                for (i in 0 until n) out[i] = if (i == head) base else 0xFF000000.toInt()
            }

            Pattern.COMET -> {
                val pos = (t % speed) / speed.toDouble() * n
                for (i in 0 until n) {
                    var d = pos - i
                    if (d < 0) d += n
                    out[i] = scale(base, max(0.0, 1 - d / 3.0))
                }
            }

            Pattern.WAVE -> {
                val phase = (t % speed) / speed.toDouble()
                for (i in 0 until n) {
                    val k = (1 + sin(2 * PI * (phase + i.toDouble() / n))) / 2
                    out[i] = scale(base, 0.08 + 0.92 * k)
                }
            }

            Pattern.RAINBOW -> {
                val phase = (t % speed) / speed.toDouble()
                for (i in 0 until n) {
                    val h = (phase + if (cfg.rainbowSpread) i.toDouble() / n else 0.0) * 360.0
                    out[i] = hsv((h % 360).toFloat())
                }
            }

            Pattern.RANDOM -> {
                // deterministic stand-in so the preview animates without flickering randomly
                val step = t / max(120, cfg.randomIntervalMs).toLong()
                for (i in 0 until n) {
                    val seed = if (cfg.randomPerLed) step * 31 + i else step
                    out[i] = hsv(((seed * 47) % 360).toFloat(), cfg.randomSaturation)
                }
            }

            Pattern.BATTERY -> {
                // the charge gauge; see Renderer.java for the maths this mirrors
                val level = cfg.level.toDouble().coerceIn(0.0, 1.0)
                val target = level * n
                val lit = kotlin.math.ceil(target - 1e-9).toLong()
                val blinkMs = if (cfg.blinkTip) 2L * TIP_BLINKS * TIP_BLINK_MS else 0L
                val fullGreen = cfg.fullGreen && level >= FULL_LEVEL
                if (fullGreen && (!cfg.fillStepwise || t >= lit * speed)) {
                    for (i in 0 until n) out[i] = cfg.fullColor
                    val b = cfg.brightness.toDouble()
                    if (b < 1.0) for (i in 0 until n) out[i] = scale(out[i], b)
                    return out
                }
                var shown = target
                var tipOff = false
                if (cfg.fillStepwise) {
                    val count = lit * speed
                    val hold = max(STEP_HOLD_MIN_MS, 3 * speed) + blinkMs
                    val cycle = count + hold + speed
                    val phase = if (fullGreen) t else t % cycle
                    if (phase < count) {
                        shown = minOf(target, (phase / speed) + (phase % speed) / speed.toDouble())
                    } else if (phase >= count + hold) {
                        shown = 0.0
                    } else {
                        tipOff = tipBlinkOff(phase - count, blinkMs)
                    }
                } else {
                    tipOff = tipBlinkOff(t, blinkMs)
                }
                val levelColor = hsv((level * 120).toFloat())
                val oddScale = cfg.oddLedScale.toDouble().coerceIn(0.0, 1.0)
                for (i in 0 until n) {
                    val k = (shown - i).coerceIn(0.0, 1.0)
                    if (k <= 0) continue
                    if (tipOff && i.toLong() == lit - 1) continue
                    val c = if (cfg.colorByLevel) levelColor else cfg.perLed[i % cfg.perLed.size]
                    var f = if (k >= 1) 1.0 else PARTIAL_FLOOR + (1 - PARTIAL_FLOOR) * k
                    if (i % 2 == 1) f *= oddScale
                    out[i] = scale(c, f)
                }
            }
        }

        val b = cfg.brightness.toDouble()
        if (b < 1.0) for (i in 0 until n) out[i] = scale(out[i], b)
        return out
    }

    fun scale(color: Int, k: Double): Int {
        val kk = k.coerceIn(0.0, 1.0)
        val r = (((color shr 16) and 0xFF) * kk).toInt()
        val g = (((color shr 8) and 0xFF) * kk).toInt()
        val b = ((color and 0xFF) * kk).toInt()
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun mix(a: Int, b: Int, k: Double): Int {
        val kk = k.coerceIn(0.0, 1.0)
        val r = (((a shr 16) and 0xFF) * (1 - kk) + ((b shr 16) and 0xFF) * kk).toInt()
        val g = (((a shr 8) and 0xFF) * (1 - kk) + ((b shr 8) and 0xFF) * kk).toInt()
        val bl = ((a and 0xFF) * (1 - kk) + (b and 0xFF) * kk).toInt()
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
    }

    fun hsv(h: Float, s: Float = 1f, v: Float = 1f): Int {
        val c = v * s
        val x = c * (1 - abs((h / 60f) % 2 - 1))
        val m = v - c
        val (r, g, b) = when (((h / 60).toInt()) % 6) {
            0 -> Triple(c, x, 0f)
            1 -> Triple(x, c, 0f)
            2 -> Triple(0f, c, x)
            3 -> Triple(0f, x, c)
            4 -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        return (0xFF shl 24) or
            (((r + m) * 255).toInt() shl 16) or
            (((g + m) * 255).toInt() shl 8) or
            ((b + m) * 255).toInt()
    }
}
