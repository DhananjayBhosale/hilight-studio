package com.hilight.studio

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import android.widget.Toast
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun AmbientScreen(store: Store) {
    val ambient by store.ambient.collectAsStateWithLifecycle()
    val enabled by store.enabled.collectAsStateWithLifecycle()
    var editingLed by rememberSaveable { mutableIntStateOf(0) }

    PresetsCard(store)

    PixelCard(tone = 2) {
        SectionTitle("Always-on style")
        LedStrip(ambient.pattern, ambient, active = enabled, heightDp = 46)
        PatternCarousel(
            selected = ambient.pattern,
            options = Pattern.entries,
            onSelect = { store.setAmbient(ambient.copy(pattern = it)) },
        )
        if (!enabled) {
            Text(
                "Control is off — turn it on in Live to see this on the hardware.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }

    AnimatedContent(
        targetState = ambient.pattern,
        transitionSpec = { fadeIn(tween(180)).togetherWith(fadeOut(tween(120))) },
        label = "patternOptions",
    ) { pattern ->
        Column {
            when (pattern) {
                Pattern.RANDOM -> PixelCard {
                    SectionTitle("Random colours")
                    PixelSlider(
                        "Change every",
                        ambient.randomIntervalMs.toFloat(),
                        150f..8000f,
                        { store.setAmbient(ambient.copy(randomIntervalMs = it.toInt())) },
                    ) { "${(it / 100).toInt() / 10f}s" }
                    ToggleRow("A colour per LED", ambient.randomPerLed) {
                        store.setAmbient(ambient.copy(randomPerLed = it))
                    }
                    ToggleRow("Fade between colours", ambient.randomSmooth) {
                        store.setAmbient(ambient.copy(randomSmooth = it))
                    }
                    PixelSlider(
                        "Saturation",
                        ambient.randomSaturation,
                        0.2f..1f,
                        { store.setAmbient(ambient.copy(randomSaturation = it)) },
                    ) { "${(it * 100).toInt()}%" }
                }

                Pattern.RAINBOW -> PixelCard {
                    SectionTitle("Rainbow")
                    ToggleRow("Spread across the array", ambient.rainbowSpread) {
                        store.setAmbient(ambient.copy(rainbowSpread = it))
                    }
                    Caption("Off puts every LED on the same hue and cycles them together.")
                }

                Pattern.CUSTOM -> PixelCard {
                    SectionTitle("Per-LED colours")
                    Caption("LED 1 sits closest to the flash. Tap one, then pick its colour.")
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        ambient.perLed.forEachIndexed { i, c ->
                            LedSwatch(
                                color = c,
                                selected = i == editingLed,
                                modifier = Modifier.weight(1f),
                            ) { editingLed = i }
                        }
                    }
                    ColorPicker(
                        color = ambient.perLed[editingLed],
                        onColor = { c ->
                            store.setAmbient(
                                ambient.copy(
                                    perLed = ambient.perLed.toMutableList().also { it[editingLed] = c })
                            )
                        },
                        label = "LED ${editingLed + 1}",
                    )
                    PixelSlider(
                        "Rotate around array",
                        ambient.rotateMs.toFloat(),
                        0f..2000f,
                        { store.setAmbient(ambient.copy(rotateMs = it.toInt())) },
                    ) { if (it < 50) "off" else "${it.toInt()}ms" }
                    val wallpaper = wallpaperLedColours()
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        FilledTonalButton(
                            onClick = {
                                store.setAmbient(
                                    ambient.copy(
                                        perLed = List(LED_COUNT) { i -> Renderer.hsv(i * 360f / LED_COUNT) })
                                )
                            },
                            modifier = Modifier.weight(1f),
                        ) { ButtonLabel("Rainbow") }
                        FilledTonalButton(
                            onClick = { store.setAmbient(ambient.copy(perLed = wallpaper)) },
                            modifier = Modifier.weight(1f),
                        ) { ButtonLabel("Wallpaper") }
                        FilledTonalButton(
                            onClick = {
                                store.setAmbient(ambient.copy(perLed = List(LED_COUNT) { ambient.color }))
                            },
                            modifier = Modifier.weight(1f),
                        ) { ButtonLabel("Solid") }
                    }
                }

                Pattern.GRADIENT -> PixelCard {
                    SectionTitle("Gradient")
                    ColorPicker(ambient.color, { store.setAmbient(ambient.copy(color = it)) }, "Start")
                    ColorPicker(
                        ambient.secondColor,
                        { store.setAmbient(ambient.copy(secondColor = it)) },
                        "End",
                    )
                }

                Pattern.OFF -> PixelCard {
                    SectionTitle("Off")
                    Caption("The array stays dark until an app rule fires.")
                }

                else -> PixelCard {
                    SectionTitle("Colour")
                    ColorPicker(ambient.color, { store.setAmbient(ambient.copy(color = it)) })
                }
            }

            if (pattern != Pattern.OFF) {
                PixelCard {
                    SectionTitle("Timing")
                    if (pattern.usesSpeed) {
                        PixelSlider(
                            "Time per cycle",
                            ambient.speedMs.toFloat(),
                            150f..8000f,
                            { store.setAmbient(ambient.copy(speedMs = it.toInt())) },
                        ) { formatDuration(it.toInt()) }
                        pattern.cycleMeaning?.let { Caption(it) }
                        Caption("Shorter is faster.")
                    }
                    PixelSlider(
                        "Brightness",
                        ambient.brightness,
                        0.02f..1f,
                        { store.setAmbient(ambient.copy(brightness = it)) },
                    ) { "${(it * 100).toInt()}%" }
                    Caption("The LEDs have no brightness channel, so this scales the RGB values.")
                }
            }
        }
    }
}

@Composable
private fun PresetsCard(store: Store) {
    val ctx = LocalContext.current
    val presets by store.presets.collectAsStateWithLifecycle()
    var naming by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var importText by remember { mutableStateOf("") }

    PixelCard {
        SectionTitle("Presets", trailing = { Caption("${presets.size} saved") })
        if (presets.isEmpty()) {
            Caption("Save the current look to come back to it later.")
        } else {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                presets.forEach { preset ->
                    Row(
                        Modifier
                            .background(
                                MaterialTheme.colorScheme.surfaceContainerHighest,
                                CircleShape,
                            )
                            .clickable { store.applyPreset(preset) }
                            .padding(start = 16.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(preset.name, style = MaterialTheme.typography.labelLarge)
                        Text(
                            "✕",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .clickable { store.deletePreset(preset) }
                                .padding(horizontal = 6.dp),
                        )
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FilledTonalButton(
                onClick = { name = ""; naming = true },
                modifier = Modifier.weight(1f),
            ) { ButtonLabel("Save") }
            FilledTonalButton(
                onClick = { shareText(ctx, store.exportPresets()) },
                enabled = presets.isNotEmpty(),
                modifier = Modifier.weight(1f),
            ) { ButtonLabel("Export") }
            FilledTonalButton(
                onClick = { importText = ""; importing = true },
                modifier = Modifier.weight(1f),
            ) { ButtonLabel("Import") }
        }
    }

    if (naming) {
        AlertDialog(
            onDismissRequest = { naming = false },
            shape = MaterialTheme.shapes.extraLarge,
            title = { Text("Name this look") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text("Name") },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    store.savePreset(name)
                    naming = false
                }) { ButtonLabel("Save") }
            },
            dismissButton = { TextButton(onClick = { naming = false }) { ButtonLabel("Cancel") } },
        )
    }

    if (importing) {
        AlertDialog(
            onDismissRequest = { importing = false },
            shape = MaterialTheme.shapes.extraLarge,
            title = { Text("Paste exported presets") },
            text = {
                OutlinedTextField(
                    value = importText,
                    onValueChange = { importText = it },
                    label = { Text("JSON") },
                    modifier = Modifier.heightIn(max = 220.dp),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val added = store.importPresets(importText)
                    Toast.makeText(
                        ctx,
                        if (added == null) "That JSON could not be read" else "Imported $added",
                        Toast.LENGTH_SHORT,
                    ).show()
                    importing = false
                }) { ButtonLabel("Import") }
            },
            dismissButton = { TextButton(onClick = { importing = false }) { ButtonLabel("Cancel") } },
        )
    }
}

private fun shareText(ctx: android.content.Context, text: String) {
    val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(android.content.Intent.EXTRA_TEXT, text)
    }
    ctx.startActivity(android.content.Intent.createChooser(send, "Export presets"))
}

@Composable
fun PatternCarousel(
    selected: Pattern,
    options: List<Pattern>,
    onSelect: (Pattern) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val listState = rememberLazyListState()

    LaunchedEffect(selected, options) {
        val index = options.indexOf(selected)
        if (index >= 0) listState.animateScrollToItem(index, scrollOffset = -120)
    }

    LazyRow(
        state = listState,
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(options, key = { it.key }) { p ->
            val isSelected = p == selected
            val bg by animateColorAsState(
                if (isSelected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceContainerHighest,
                label = "chipBg",
            )
            val fg by animateColorAsState(
                if (isSelected) MaterialTheme.colorScheme.onPrimary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                label = "chipFg",
            )
            val scale by animateFloatAsState(
                if (isSelected) 1.04f else 1f,
                spring(dampingRatio = 0.4f, stiffness = Spring.StiffnessMedium),
                label = "chipScale",
            )
            Box(
                Modifier
                    .scale(scale)
                    .background(bg, CircleShape)
                    .clickable {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onSelect(p)
                    }
                    .padding(horizontal = 18.dp, vertical = 11.dp),
            ) {
                Text(p.label, style = MaterialTheme.typography.labelLarge, color = fg)
            }
        }
    }
}

@Composable
private fun LedSwatch(
    color: Int,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val scale by animateFloatAsState(
        if (selected) 1.1f else 1f,
        spring(dampingRatio = 0.4f, stiffness = Spring.StiffnessMedium),
        label = "ledSwatch",
    )
    Box(
        modifier
            .scale(scale)
            .aspectRatio(1f)
            .background(Color(color), CircleShape)
            .border(
                if (selected) 3.dp else 1.dp,
                if (selected) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                CircleShape,
            )
            .clickable {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {}
}
