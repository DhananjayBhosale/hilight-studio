package com.hilight.studio

import android.widget.Toast
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

val PRESET_COLORS = listOf(
    0xFFFF1744, 0xFFFF6D00, 0xFFFFD600, 0xFF00E676, 0xFF00E5FF,
    0xFF2979FF, 0xFF7C4DFF, 0xFFFF4081, 0xFFFFFFFF, 0xFFFF80AB,
).map { it.toInt() }

internal typealias PreviewLauncher = (Pattern, Int, Int, Float, Int) -> Unit

/** Launches every in-app preview through one truthful guard check without adding UI controls. */
@Composable
internal fun rememberPreviewLauncher(store: Store): PreviewLauncher {
    val context = LocalContext.current.applicationContext
    val resources = LocalResources.current
    return remember(store, context, resources) {
        { pattern, color, speedMs, brightness, durationMs ->
            val reason = store.previewSuppressionReason()
            if (reason == null) {
                store.preview(pattern, color, speedMs, brightness, durationMs)
            } else {
                Toast.makeText(
                    context,
                    resources.getString(
                        R.string.test_blocked_by_guard,
                        resources.getString(reason.shortRes),
                    ),
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }
}

/**
 * Swatches plus hue / saturation / intensity.
 *
 * The selected swatch grows on a spring and keeps a ring, the way Pixel's wallpaper and theme
 * pickers behave, and the hue track is the gradient itself rather than a tinted bar.
 */
@Composable
fun ColorPicker(
    color: Int,
    onColor: (Int) -> Unit,
    // Callers editing a named colour pass their own heading, so this stays a String. The default is
    // read from resources, which a composable default expression is allowed to do.
    label: String = stringResource(R.string.widget_colour),
) {
    val hsv = FloatArray(3).also { android.graphics.Color.colorToHSV(color, it) }
    val haptics = LocalHapticFeedback.current

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Box(
                Modifier
                    .size(30.dp)
                    .background(Color(color), CircleShape)
                    .border(2.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
            )
        }

        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            PRESET_COLORS.forEach { c ->
                val selected = c == color
                val scale by animateFloatAsState(
                    if (selected) 1.16f else 1f,
                    spring(dampingRatio = 0.42f, stiffness = Spring.StiffnessMedium),
                    label = "swatch",
                )
                Box(
                    Modifier
                        .scale(scale)
                        .size(34.dp)
                        .background(Color(c), CircleShape)
                        .border(
                            if (selected) 3.dp else 1.dp,
                            if (selected) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                            CircleShape,
                        )
                        .clickable {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            onColor(c)
                        }
                )
            }
        }

        // hue: the track is the spectrum, the thumb rides on top
        Box(
            Modifier
                .fillMaxWidth()
                .height(46.dp),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(22.dp)
                    .padding(horizontal = 6.dp)
                    .background(
                        Brush.horizontalGradient((0..6).map { Color(Renderer.hsv(it * 60f)) }),
                        CircleShape,
                    )
            )
            Slider(
                value = hsv[0],
                valueRange = 0f..359f,
                onValueChange = { h ->
                    onColor(android.graphics.Color.HSVToColor(floatArrayOf(h, hsv[1], hsv[2])))
                },
                colors = SliderDefaults.colors(
                    thumbColor = Color.White,
                    activeTrackColor = Color.Transparent,
                    inactiveTrackColor = Color.Transparent,
                    activeTickColor = Color.Transparent,
                    inactiveTickColor = Color.Transparent,
                ),
            )
        }

        PixelSlider(stringResource(R.string.widget_saturation), hsv[1], 0f..1f, { s ->
            onColor(android.graphics.Color.HSVToColor(floatArrayOf(hsv[0], s, hsv[2])))
        }) { stringResource(R.string.widget_percent, (it * 100).toInt()) }
        PixelSlider(stringResource(R.string.widget_intensity), hsv[2], 0.05f..1f, { v ->
            onColor(android.graphics.Color.HSVToColor(floatArrayOf(hsv[0], hsv[1], v)))
        }) { stringResource(R.string.widget_percent, (it * 100).toInt()) }
    }
}

/**
 * Eight saturated LED colours derived from the app's current Material You scheme — which, with
 * wallpaper colours on, is derived from the wallpaper itself.
 *
 * The scheme's key hues are taken and their saturation and value pushed up, because container tones
 * are pale by design and pale is nearly invisible on an LED.
 */
@Composable
fun wallpaperLedColours(): List<Int> {
    val scheme = MaterialTheme.colorScheme
    val seeds = listOf(scheme.primary, scheme.secondary, scheme.tertiary)
        .map { android.graphics.Color.valueOf(it.red, it.green, it.blue).toArgb() }
    val hues = seeds.map { c ->
        FloatArray(3).also { android.graphics.Color.colorToHSV(c, it) }[0]
    }
    // walk between the key hues so all eight LEDs differ but stay in the wallpaper's family
    return (0 until LED_COUNT).map { i ->
        val t = i.toFloat() / LED_COUNT * hues.size
        val a = hues[t.toInt().coerceAtMost(hues.lastIndex)]
        val b = hues[(t.toInt() + 1).coerceAtMost(hues.lastIndex)]
        val hue = a + (b - a) * (t - t.toInt())
        Renderer.hsv(hue, 1f, 1f)
    }
}
