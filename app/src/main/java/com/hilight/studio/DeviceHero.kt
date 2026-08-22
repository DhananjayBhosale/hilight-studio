package com.hilight.studio

import android.os.Build
import androidx.annotation.StringRes
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.sin

/**
 * The device drawn in the Live hero.
 *
 * Google's press renders are copyrighted, so this is a vector reconstruction rather than a bundled
 * image — which also lets the glow be driven by the live pattern maths. The layouts follow the launch
 * hardware: on the Pro and Pro XL, HiLight sits at the right-hand end of the full-width camera bar;
 * on the Pro Fold the bar is a compact block in the top-left corner; the non-Pro Pixel 11 has no
 * HiLight at all.
 */
enum class DeviceProfile(
    /**
     * Marketing name, which is not translated — a Pixel 11 Pro is called that in every language.
     * [labelRes] is set only for the fallback profile, whose label is a phrase rather than a name.
     */
    val label: String,
    @StringRes val labelRes: Int? = null,
    /** width / height of the body */
    val aspect: Float,
    val lensCount: Int,
    val hasHiLight: Boolean,
    val foldStyle: Boolean = false,
    /** body width as a fraction of the canvas — over 1 means the device runs off the sides */
    val zoom: Float = 0.93f,
    /** where the body's left edge sits, as a fraction of the canvas */
    val originX: Float = -1f,
) {
    PRO_XL("Pixel 11 Pro XL", aspect = 0.470f, lensCount = 3, hasHiLight = true),
    PRO("Pixel 11 Pro", aspect = 0.455f, lensCount = 3, hasHiLight = true),
    FOLD(
        "Pixel 11 Pro Fold", aspect = 0.950f, lensCount = 3, hasHiLight = true, foldStyle = true,
        zoom = 1.15f, originX = 0.035f,
    ),
    BASE("Pixel 11", aspect = 0.462f, lensCount = 2, hasHiLight = false),
    GENERIC("this device", labelRes = R.string.device_generic, aspect = 0.465f, lensCount = 3, hasHiLight = true),
    ;

    companion object {
        /** Best-effort match on the marketing name, which is what Build.MODEL carries on Pixels. */
        fun detect(model: String = Build.MODEL): DeviceProfile {
            val m = model.lowercase()
            return when {
                !m.contains("pixel") -> GENERIC
                m.contains("fold") -> FOLD
                m.contains("pro xl") -> PRO_XL
                m.contains("pro") -> PRO
                m.contains("pixel 11") -> BASE
                else -> GENERIC
            }
        }
    }
}

@Composable
fun rememberDeviceProfile(): DeviceProfile = remember { DeviceProfile.detect() }

/**
 * The back of the phone with HiLight lit by the real pattern maths, plus the light it pools onto the
 * surface underneath — which is how the feature is actually seen, face-down.
 *
 * The array reads as one diffused disc rather than eight pinpoints, because the eight LEDs sit behind
 * the flash window; the individual colours still drive the disc, so a chase or a rainbow visibly
 * travels around it.
 */
@Composable
fun DeviceHero(
    pattern: Pattern,
    cfg: Ambient,
    active: Boolean,
    modifier: Modifier = Modifier,
    profile: DeviceProfile = rememberDeviceProfile(),
    heightDp: Int = 190,
) {
    val frame = rememberLedFrame(pattern, cfg, active && profile.hasHiLight)
    val bloom by animateFloatAsState(
        targetValue = if (active && profile.hasHiLight) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessLow),
        label = "bloom",
    )

    // Assembled before the modifier chain because a semantics block is not a composable scope, so
    // stringResource cannot be called inside it.
    val modelName = profile.labelRes?.let { stringResource(it) } ?: profile.label
    val doing = when {
        !profile.hasHiLight -> stringResource(R.string.hero_no_array)
        !active -> stringResource(R.string.hero_array_off)
        else -> stringResource(R.string.hero_showing, stringResource(pattern.labelRes))
    }
    val description = stringResource(R.string.hero_description, modelName, doing)

    Box(
        modifier
            .fillMaxWidth()
            .height(heightDp.dp)
            .clip(RoundedCornerShape(26.dp))           // the device is cropped by the card edge
            .semantics { contentDescription = description },
    ) {
        Canvas(Modifier.fillMaxWidth().height(heightDp.dp)) {
            drawRect(Stage)
            drawRect(
                brush = Brush.radialGradient(
                    listOf(StageHigh, Stage),
                    center = Offset(size.width / 2f, size.height * 0.30f),
                    radius = size.width * 0.8f,
                ),
            )

            // Only the top of the device is shown, framed on the camera bar the way Google's own
            // close-ups are; the body deliberately runs off the bottom of the card.
            val phoneW = size.width * profile.zoom
            val phoneH = phoneW / profile.aspect
            val left =
                if (profile.originX >= 0f) size.width * profile.originX
                else (size.width - phoneW) / 2f
            val top = size.height * 0.07f
            val corner = phoneW * if (profile.foldStyle) 0.10f else 0.13f

            if (bloom > 0.01f) drawSpill(frame, left, top, phoneW, bloom)
            drawBody(left, top, phoneW, phoneH, corner)

            if (profile.foldStyle) {
                drawFoldCameraBlock(frame, left, top, phoneW, phoneH, bloom)
            } else {
                drawVisorBar(frame, left, top, phoneW, phoneH, profile, bloom)
            }
        }
    }
}

// The device keeps its own graphite palette whatever the wallpaper does: a real Pixel is dark, and the
// LEDs only read as light against a dark body.
private val Stage = Color(0xFF0C0E11)
private val StageHigh = Color(0xFF1A1D22)
private val Body = Color(0xFF23262B)
private val BodyEdge = Color(0xFF3C4045)
private val Visor = Color(0xFF0E1114)
private val Lens = Color(0xFF07090B)
private val LensRing = Color(0xFF4A4F55)

private fun DrawScope.drawBody(left: Float, top: Float, w: Float, h: Float, corner: Float) {
    drawRoundRect(Body, Offset(left, top), Size(w, h), CornerRadius(corner))
    drawRoundRect(
        BodyEdge.copy(alpha = 0.7f),
        Offset(left, top),
        Size(w, h),
        CornerRadius(corner),
        style = Stroke(width = size.height * 0.004f),
    )
    drawRoundRect(
        brush = Brush.linearGradient(
            listOf(Color.White.copy(alpha = 0.05f), Color.Transparent),
            start = Offset(left, top),
            end = Offset(left + w, top + h * 0.6f),
        ),
        topLeft = Offset(left, top),
        size = Size(w, h),
        cornerRadius = CornerRadius(corner),
    )
}

/** Pro and Pro XL: full-width pill, three lenses, HiLight at the right-hand end. */
private fun DrawScope.drawVisorBar(
    frame: IntArray,
    left: Float,
    top: Float,
    phoneW: Float,
    phoneH: Float,
    profile: DeviceProfile,
    bloom: Float,
) {
    val barH = phoneW * 0.245f
    val barTop = top + phoneH * 0.105f
    val inset = phoneW * 0.05f
    val barW = phoneW - inset * 2
    val cy = barTop + barH / 2f

    drawRoundRect(Visor, Offset(left + inset, barTop), Size(barW, barH), CornerRadius(barH / 2))
    drawRoundRect(
        LensRing.copy(alpha = 0.5f),
        Offset(left + inset, barTop),
        Size(barW, barH),
        CornerRadius(barH / 2),
        style = Stroke(width = size.height * 0.003f),
    )

    val lensR = barH * 0.31f
    val positions = if (profile.lensCount >= 3) listOf(0.14f, 0.32f, 0.50f) else listOf(0.18f, 0.40f)
    positions.forEach { fx -> drawLens(left + inset + barW * fx, cy, lensR) }

    // the small square sensor window that sits above the flash on the real bar
    val sensorSize = barH * 0.13f
    drawRoundRect(
        Color(0xFF2A2E33),
        Offset(left + inset + barW * 0.70f - sensorSize / 2, cy - barH * 0.34f),
        Size(sensorSize, sensorSize),
        CornerRadius(sensorSize * 0.25f),
    )

    if (profile.hasHiLight) {
        drawHiLightDisc(frame, Offset(left + inset + barW * 0.80f, cy), barH * 0.335f, bloom)
    } else {
        // non-Pro: plain flash, no array
        drawLens(left + inset + barW * 0.74f, cy, barH * 0.15f)
    }
}

/** Pro Fold: compact camera block in the top-left corner, HiLight inside it. */
private fun DrawScope.drawFoldCameraBlock(
    frame: IntArray,
    left: Float,
    top: Float,
    phoneW: Float,
    phoneH: Float,
    bloom: Float,
) {
    val blockW = phoneW * 0.40f
    val blockH = phoneW * 0.165f
    val blockLeft = left + phoneW * 0.045f
    val blockTop = top + phoneH * 0.055f
    val cy = blockTop + blockH / 2f

    drawRoundRect(Visor, Offset(blockLeft, blockTop), Size(blockW, blockH), CornerRadius(blockH * 0.42f))
    drawRoundRect(
        LensRing.copy(alpha = 0.5f),
        Offset(blockLeft, blockTop),
        Size(blockW, blockH),
        CornerRadius(blockH * 0.42f),
        style = Stroke(width = size.height * 0.003f),
    )

    // three rear cameras, then the array
    val lensR = blockH * 0.28f
    listOf(0.15f, 0.35f, 0.55f).forEach { fx -> drawLens(blockLeft + blockW * fx, cy, lensR) }
    drawHiLightDisc(frame, Offset(blockLeft + blockW * 0.82f, cy), blockH * 0.30f, bloom)

    // hinge seam, so the silhouette reads as the foldable
    drawRoundRect(
        BodyEdge.copy(alpha = 0.55f),
        Offset(left + phoneW * 0.495f, top + phoneH * 0.02f),
        Size(phoneW * 0.012f, phoneH * 0.96f),
        CornerRadius(phoneW * 0.006f),
    )
}

private fun DrawScope.drawLens(cx: Float, cy: Float, r: Float) {
    drawCircle(Lens, radius = r, center = Offset(cx, cy))
    drawCircle(
        LensRing.copy(alpha = 0.85f),
        radius = r,
        center = Offset(cx, cy),
        style = Stroke(width = size.height * 0.0035f),
    )
    drawCircle(Color(0xFF10131A), radius = r * 0.6f, center = Offset(cx, cy))
    // glass highlight
    drawCircle(
        Color(0xFF3D4A6B).copy(alpha = 0.55f),
        radius = r * 0.2f,
        center = Offset(cx - r * 0.18f, cy - r * 0.2f),
    )
}

/**
 * HiLight itself: eight LEDs behind one flash window.
 *
 * Each LED is placed on a ring inside the window and blurred outward, so the result looks like the
 * single diffused disc on the real hardware while still showing pattern movement around it.
 */
private fun DrawScope.drawHiLightDisc(colors: IntArray, center: Offset, radius: Float, bloom: Float) {
    // the dark window the array shines through
    drawCircle(Color(0xFF05070A), radius = radius * 1.12f, center = center)
    drawCircle(
        LensRing.copy(alpha = 0.5f),
        radius = radius * 1.12f,
        center = center,
        style = Stroke(width = size.height * 0.0025f),
    )

    if (bloom <= 0.01f || colors.isEmpty()) {
        drawCircle(Color(0xFF0C1016), radius = radius, center = center)
        return
    }

    val n = colors.size
    var lumSum = 0f
    var r = 0f
    var g = 0f
    var b = 0f
    for (i in 0 until n) {
        val c = Color(colors[i])
        lumSum += (c.red + c.green + c.blue) / 3f
        r += c.red; g += c.green; b += c.blue
    }
    val avg = Color(r / n, g / n, b / n)
    val lum = lumSum / n

    // The lamp itself: a crisp disc of the blended colour, with each LED's own colour showing through
    // from its position inside the window. Clipped, so the light does not smear across the bar.
    clipPath(Path().apply { addOval(Rect(center = center, radius = radius)) }) {
        drawCircle(avg.copy(alpha = 0.92f * bloom), radius = radius, center = center)
        for (i in 0 until n) {
            val c = Color(colors[i])
            if ((c.red + c.green + c.blue) / 3f <= 0.01f) continue
            val angle = (i.toFloat() / n) * 2f * Math.PI.toFloat() - Math.PI.toFloat() / 2f
            val pos = Offset(
                center.x + cos(angle) * radius * 0.46f,
                center.y + sin(angle) * radius * 0.46f,
            )
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(c.copy(alpha = 0.75f * bloom), c.copy(alpha = 0f)),
                    center = pos,
                    radius = radius * 0.85f,
                ),
                radius = radius * 0.85f,
                center = pos,
            )
        }
        // hot centre
        drawCircle(
            brush = Brush.radialGradient(
                listOf(Color.White.copy(alpha = 0.30f * bloom * lum), Color.Transparent),
                center = center,
                radius = radius * 0.6f,
            ),
            radius = radius * 0.6f,
            center = center,
        )
    }

    // bloom outside the window, kept modest so the disc keeps its edge
    drawCircle(
        brush = Brush.radialGradient(
            listOf(avg.copy(alpha = 0.34f * bloom * lum), Color.Transparent),
            center = center,
            radius = radius * 2.5f,
        ),
        radius = radius * 2.5f,
        center = center,
    )
}

/** Soft pool of colour under the phone. */
private fun DrawScope.drawSpill(colors: IntArray, left: Float, top: Float, phoneW: Float, bloom: Float) {
    var r = 0f
    var g = 0f
    var b = 0f
    colors.forEach {
        val c = Color(it)
        r += c.red; g += c.green; b += c.blue
    }
    val n = colors.size.coerceAtLeast(1)
    val avg = Color(r / n, g / n, b / n)
    val lum = (avg.red + avg.green + avg.blue) / 3f
    if (lum < 0.02f) return
    val center = Offset(left + phoneW / 2f, top + phoneW * 0.22f)
    val radius = phoneW * 1.25f
    drawCircle(
        brush = Brush.radialGradient(
            listOf(avg.copy(alpha = 0.32f * bloom * lum), Color.Transparent),
            center = center,
            radius = radius,
        ),
        radius = radius,
        center = center,
    )
}

/**
 * Drives previews at the hardware's own frame rate using the shared pattern maths, so what is on
 * screen matches what the LEDs are doing.
 */
@Composable
fun rememberLedFrame(
    pattern: Pattern,
    cfg: Ambient,
    active: Boolean = true,
    animate: Boolean = true,
): IntArray {
    val dark = IntArray(LED_COUNT) { 0xFF000000.toInt() }
    val frame by produceState(dark, pattern, cfg, active, animate) {
        if (!active) {
            value = dark
            return@produceState
        }
        if (!animate) {
            val t = when (pattern) {
                Pattern.BREATHE -> cfg.speedMs / 2L
                Pattern.PULSE -> (cfg.speedMs * 0.12f).toLong()
                Pattern.RANDOM -> cfg.randomIntervalMs.toLong()
                else -> cfg.speedMs / 3L
            }
            value = Renderer.frame(pattern, t, cfg)
            return@produceState
        }
        val start = System.currentTimeMillis()
        while (true) {
            value = Renderer.frame(pattern, System.currentTimeMillis() - start, cfg)
            delay(33)                       // Light.getMinUpdatePeriodMillis()
        }
    }
    return frame
}

/** Compact strip used on rule cards: an abstraction of the array, one dot per addressable LED. */
@Composable
fun LedStrip(
    pattern: Pattern,
    cfg: Ambient,
    modifier: Modifier = Modifier,
    active: Boolean = true,
    animate: Boolean = true,
    heightDp: Int = 40,
) {
    val frame = rememberLedFrame(pattern, cfg, active, animate)
    val patternName = stringResource(pattern.labelRes)
    val label = if (active) stringResource(R.string.hero_strip_preview, patternName)
    else stringResource(R.string.hero_strip_off, patternName)
    Canvas(
        modifier
            .fillMaxWidth()
            .height(heightDp.dp)
            .semantics { contentDescription = label },
    ) {
        val n = frame.size
        val gap = size.width / (n * 3.2f)
        val d = (size.width - gap * (n - 1)) / n
        val r = minOf(d, size.height) / 2.6f
        for (i in 0 until n) {
            val c = Color(frame[i])
            val cx = i * (d + gap) + d / 2f
            val cyc = size.height / 2f
            val lum = (c.red + c.green + c.blue) / 3f
            if (lum > 0.02f) {
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(c.copy(alpha = 0.45f * lum), Color.Transparent),
                        center = Offset(cx, cyc),
                        radius = r * 3.4f,
                    ),
                    radius = r * 3.4f,
                    center = Offset(cx, cyc),
                )
            }
            drawCircle(Color.Black.copy(alpha = 0.14f), radius = r * 1.2f, center = Offset(cx, cyc))
            drawCircle(c, radius = r, center = Offset(cx, cyc))
        }
    }
}
