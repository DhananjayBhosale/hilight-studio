package com.hilight.studio

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp

val PRESET_COLORS = listOf(
    0xFFFF1744, 0xFFFF6D00, 0xFFFFD600, 0xFF00E676, 0xFF00E5FF,
    0xFF2979FF, 0xFF7C4DFF, 0xFFFF4081, 0xFFFFFFFF, 0xFFFF80AB,
).map { it.toInt() }

@Composable
fun ColorPicker(color: Int, onColor: (Int) -> Unit, label: String = "Colour") {
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

        PixelSlider("Saturation", hsv[1], 0f..1f, { s ->
            onColor(android.graphics.Color.HSVToColor(floatArrayOf(hsv[0], s, hsv[2])))
        }) { "${(it * 100).toInt()}%" }
        PixelSlider("Intensity", hsv[2], 0.05f..1f, { v ->
            onColor(android.graphics.Color.HSVToColor(floatArrayOf(hsv[0], hsv[1], v)))
        }) { "${(it * 100).toInt()}%" }
    }
}

@Composable
fun wallpaperLedColours(): List<Int> {
    val scheme = MaterialTheme.colorScheme
    val seeds = listOf(scheme.primary, scheme.secondary, scheme.tertiary)
        .map { android.graphics.Color.valueOf(it.red, it.green, it.blue).toArgb() }
    val hues = seeds.map { c ->
        FloatArray(3).also { android.graphics.Color.colorToHSV(c, it) }[0]
    }

    return (0 until LED_COUNT).map { i ->
        val t = i.toFloat() / LED_COUNT * hues.size
        val a = hues[t.toInt().coerceAtMost(hues.lastIndex)]
        val b = hues[(t.toInt() + 1).coerceAtMost(hues.lastIndex)]
        val hue = a + (b - a) * (t - t.toInt())
        Renderer.hsv(hue, 1f, 1f)
    }
}
