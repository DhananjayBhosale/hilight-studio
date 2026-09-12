package com.hilight.studio

import androidx.compose.ui.graphics.Color
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * On-screen mirror of the helper's renderer, so the UI can show what the LEDs will do without
 * touching the hardware. Kept deliberately in step with HiLightHelper.render().
 */
object Renderer {

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

            Pattern.METER -> {
                val phase = (t % speed) / speed.toDouble()
                if (phase < 0.75) {
                    val progress = (phase / 0.75) * n
                    val fullCount = floor(progress).toInt()
                    val partial = progress - fullCount
                    for (i in 0 until n) {
                        if (i < fullCount) {
                            out[i] = base
                        } else if (i == fullCount) {
                            out[i] = scale(base, partial)
                        } else {
                            out[i] = 0xFF000000.toInt()
                        }
                    }
                } else if (phase < 0.88) {
                    for (i in 0 until n) out[i] = base
                } else {
                    val fade = 1.0 - (phase - 0.88) / 0.12
                    for (i in 0 until n) out[i] = scale(base, fade)
                }
            }

            Pattern.STROBE -> {
                val phase = (t % speed) / speed.toDouble()
                if (phase < 0.45) {
                    val subPhase = (phase / 0.45) * 3.0
                    val frac = subPhase - floor(subPhase)
                    if (frac < 0.55) {
                        for (i in 0 until n) out[i] = base
                    }
                }
            }

            Pattern.HEARTBEAT -> {
                val phase = (t % speed) / speed.toDouble()
                val k = when {
                    phase < 0.06 -> (phase / 0.06) * 0.75
                    phase < 0.22 -> 0.75 * exp(-(phase - 0.06) * 16.0)
                    phase < 0.28 -> ((phase - 0.22) / 0.06)
                    phase < 0.60 -> exp(-(phase - 0.28) * 9.0)
                    else -> 0.0
                }
                for (i in 0 until n) out[i] = scale(base, k)
            }

            Pattern.BOUNCE -> {
                val phase = (t % speed) / speed.toDouble()
                val pos = (if (phase < 0.5) (phase * 2.0) else ((1.0 - phase) * 2.0)) * (n - 1)
                for (i in 0 until n) {
                    val dist = abs(pos - i)
                    out[i] = scale(base, max(0.0, 1.0 - dist / 1.5))
                }
            }

            Pattern.RADAR -> {
                val phase = (t % speed) / speed.toDouble()
                val head = phase * n
                for (i in 0 until n) {
                    var d = head - i
                    if (d < 0) d += n
                    out[i] = scale(base, exp(-d * 0.55))
                }
            }

            Pattern.CONVERGE -> {
                val phase = (t % speed) / speed.toDouble()
                val travel = if (phase < 0.5) (phase * 2.0) else ((1.0 - phase) * 2.0)
                val centerDist = (n - 1) / 2.0
                val p1 = travel * centerDist
                val p2 = (n - 1) - travel * centerDist
                val boost = if (travel > 0.85) (travel - 0.85) / 0.15 * 0.35 else 0.0
                for (i in 0 until n) {
                    val d1 = abs(p1 - i)
                    val d2 = abs(p2 - i)
                    val k = max(max(0.0, 1.0 - d1), max(0.0, 1.0 - d2)) + boost
                    out[i] = scale(base, min(1.0, k))
                }
            }

            Pattern.GLITCH -> {
                for (i in 0 until n) {
                    val seed = (i * 3 + 1) * 7
                    val ledPeriod = max(80L, speed / 2 + (seed % 5) * 80L)
                    val ledPhase = ((t + seed * 137L) % ledPeriod) / ledPeriod.toDouble()
                    val spike = if (ledPhase < 0.15) (ledPhase / 0.15) else exp(-(ledPhase - 0.15) * 12.0)
                    val jitter = if ((t / 40 + i * 5) % 3L == 0L && spike > 0.05) 0.3 else 0.0
                    val k = (spike * 0.85 + jitter).coerceIn(0.0, 1.0)
                    out[i] = scale(base, k)
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
