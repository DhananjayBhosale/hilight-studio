package com.hilight.studio

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.ripple
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/**
 * The Pixel-flavoured building blocks: tonal cards, springy presses, pill selectors and the little
 * animated status affordances the system UI uses.
 */

/** Pixel's system surfaces squash slightly when touched, on a spring rather than a curve. */
@Composable
private fun Modifier.pressSquash(pressed: Boolean, min: Float = 0.965f): Modifier {
    val scale by animateFloatAsState(
        targetValue = if (pressed) min else 1f,
        animationSpec = spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessMedium),
        label = "press",
    )
    return this.scale(scale)
}

@Composable
fun PixelCard(
    modifier: Modifier = Modifier,
    tone: Int = 1,
    shape: Shape = MaterialTheme.shapes.large,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val color = when (tone) {
        0 -> MaterialTheme.colorScheme.surfaceContainerLow
        2 -> MaterialTheme.colorScheme.surfaceContainerHigh
        3 -> MaterialTheme.colorScheme.surfaceContainerHighest
        else -> MaterialTheme.colorScheme.surfaceContainer
    }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    var base = modifier
        .fillMaxWidth()
        .padding(horizontal = 16.dp, vertical = 6.dp)
    if (onClick != null) {
        base = base
            .pressSquash(pressed)
            .clip(shape)
            .clickable(interactionSource = interaction, indication = ripple(), onClick = onClick)
        Box(base.background(color)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                content()
            }
        }
        return
    }
    Box(base.background(color, shape)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            content()
        }
    }
}

@Composable
fun SectionTitle(text: String, trailing: (@Composable () -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = MaterialTheme.typography.titleMedium)
        trailing?.invoke()
    }
}

@Composable
fun Caption(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

/** Status chip with a breathing dot, the way Pixel shows a live connection. */
@Composable
fun LivePill(text: String, ok: Boolean, modifier: Modifier = Modifier) {
    val bg by animateColorAsState(
        if (ok) MaterialTheme.colorScheme.secondaryContainer
        else MaterialTheme.colorScheme.errorContainer,
        label = "pillBg",
    )
    val fg by animateColorAsState(
        if (ok) MaterialTheme.colorScheme.onSecondaryContainer
        else MaterialTheme.colorScheme.onErrorContainer,
        label = "pillFg",
    )
    Row(
        modifier
            .background(bg, CircleShape)
            .padding(start = 10.dp, end = 14.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        BreathingDot(fg, animate = ok)
        Text(text, style = MaterialTheme.typography.labelLarge, color = fg)
    }
}

@Composable
fun BreathingDot(color: Color, animate: Boolean, size: Int = 8) {
    val transition = rememberInfiniteTransition(label = "dot")
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1100), repeatMode = RepeatMode.Reverse),
        label = "dotAlpha",
    )
    Box(
        Modifier
            .size(size.dp)
            .background(color.copy(alpha = if (animate) alpha else 0.5f), CircleShape)
    )
}

/** Quick-Settings style tile: big rounded square, icon over label, springy. */
@Composable
fun PixelTile(
    label: String,
    icon: ImageVector,
    accent: Color,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val haptics = LocalHapticFeedback.current
    val container by animateColorAsState(
        if (enabled) accent.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surfaceContainerHigh,
        label = "tileBg",
    )
    Column(
        modifier
            .pressSquash(pressed, min = 0.94f)
            // clip first: an unclipped ripple paints a rectangle outside the tile's rounded shape
            .clip(MaterialTheme.shapes.medium)
            .background(container)
            .clickable(interactionSource = interaction, indication = ripple()) {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onClick()
            }
            .padding(vertical = 14.dp, horizontal = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (enabled) accent else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            textAlign = TextAlign.Center,
            color = if (enabled) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Big primary toggle row, haptic on change like the system's own switches. */
@Composable
fun PixelToggleRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.fillMaxWidth(0.72f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Caption(subtitle)
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onChange(it)
            },
        )
    }
}

@Composable
fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val haptics = LocalHapticFeedback.current
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Switch(
            checked = checked,
            onCheckedChange = {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onChange(it)
            },
        )
    }
}

/** Slider with the value shown in a tonal badge that animates as it changes. */
@Composable
fun PixelSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
    // Opt-in for ms-valued sliders: tapping the badge opens a dialog to type the time in seconds,
    // since the slider's hundredth-steps are hard to land exactly by drag.
    typeInSeconds: Boolean = false,
    // Composable because the value badge often shows a duration, and a duration's units come from
    // resources now that they have to be translated.
    format: @Composable (Float) -> String = { "%.0f".format(it) },
) {
    var typing by remember { mutableStateOf(false) }
    Column {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Box(
                Modifier
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.secondaryContainer)
                    .then(if (typeInSeconds) Modifier.clickable { typing = true } else Modifier)
                    .padding(horizontal = 10.dp, vertical = 3.dp)
            ) {
                Text(
                    format(value),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            colors = SliderDefaults.colors(
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            ),
        )
    }
    if (typing) {
        // Prefilled in seconds, trailing zeros trimmed; the separator may be a comma in locales
        // that write one, so parsing accepts both.
        var text by remember {
            mutableStateOf("%.2f".format(value / 1000f).trimEnd('0').trimEnd('.', ','))
        }
        val typedMs = text.trim().replace(',', '.').toFloatOrNull()?.times(1000f)
        AlertDialog(
            onDismissRequest = { typing = false },
            shape = MaterialTheme.shapes.extraLarge,
            title = { Text(label) },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.duration_seconds_field)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
            },
            confirmButton = {
                TextButton(
                    enabled = typedMs != null,
                    onClick = {
                        typing = false
                        // Clamped to the slider's own range, so typing cannot pass a safety gate.
                        if (typedMs != null) onChange(typedMs.coerceIn(range))
                    },
                ) { ButtonLabel(stringResource(R.string.common_save)) }
            },
            dismissButton = {
                TextButton(onClick = { typing = false }) {
                    ButtonLabel(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}

/**
 * Button text that never wraps.
 *
 * Buttons sharing a row are equal width, so a label that wraps makes one button taller than its
 * neighbours — which is exactly how the row ends up looking crooked.
 */
@Composable
fun ButtonLabel(text: String) {
    Text(
        text,
        maxLines = 1,
        softWrap = false,
        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        style = MaterialTheme.typography.labelLarge,
    )
}

/** Pill segmented selector — the control Pixel uses for small either/or choices. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> SegmentedSelector(
    options: List<T>,
    selected: T,
    // Composable because every caller resolves the label from resources, and pre-building a map of
    // them outside the lambda is a workaround rather than a design.
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    SingleChoiceSegmentedButtonRow(modifier.fillMaxWidth().selectableGroup()) {
        options.forEachIndexed { i, option ->
            SegmentedButton(
                selected = option == selected,
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onSelect(option)
                },
                shape = SegmentedButtonDefaults.itemShape(index = i, count = options.size),
                label = { Text(label(option), style = MaterialTheme.typography.labelLarge) },
            )
        }
    }
}

/**
 * Two-step confirmation, used before letting the LEDs stay on longer than 30 seconds.
 *
 * Deliberately two dialogs rather than one: the first explains, the second makes the user commit. Any
 * dismissal anywhere cancels, so the safe default survives an accidental tap.
 */
@Composable
fun DoubleConfirm(
    firstTitle: String,
    firstBody: String,
    secondTitle: String,
    secondBody: String,
    confirmLabel: String,
    onConfirmed: () -> Unit,
    onCancelled: () -> Unit,
) {
    var step by remember { mutableIntStateOf(1) }
    val title = if (step == 1) firstTitle else secondTitle
    val body = if (step == 1) firstBody else secondBody

    AlertDialog(
        onDismissRequest = onCancelled,
        shape = MaterialTheme.shapes.extraLarge,
        title = { Text(title) },
        text = { Text(body, style = MaterialTheme.typography.bodyMedium) },
        confirmButton = {
            TextButton(
                onClick = { if (step == 1) step = 2 else onConfirmed() },
            ) {
                ButtonLabel(
                    if (step == 1) stringResource(R.string.common_continue) else confirmLabel
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onCancelled) {
                ButtonLabel(stringResource(R.string.common_keep_it_short))
            }
        },
    )
}

/** Slider whose upper range is gated behind [DoubleConfirm]. */
@Composable
fun GatedDurationSlider(
    label: String,
    valueMs: Int,
    safeMaxMs: Int,
    extendedMaxMs: Int,
    minMs: Int,
    unlockLabel: String,
    warnFirst: Pair<String, String>,
    warnSecond: Pair<String, String>,
    onChange: (Int) -> Unit,
) {
    var unlocked by remember(valueMs > safeMaxMs) { mutableStateOf(valueMs > safeMaxMs) }
    var asking by remember { mutableStateOf(false) }

    PixelSlider(
        label = label,
        value = valueMs.toFloat(),
        range = minMs.toFloat()..(if (unlocked) extendedMaxMs else safeMaxMs).toFloat(),
        onChange = { onChange(it.toInt()) },
        typeInSeconds = true,
    ) { formatDuration(it.toInt()) }

    ToggleRow(unlockLabel, unlocked) { wanted ->
        if (wanted) {
            asking = true
        } else {
            unlocked = false
            if (valueMs > safeMaxMs) onChange(safeMaxMs)
        }
    }

    if (asking) {
        DoubleConfirm(
            firstTitle = warnFirst.first,
            firstBody = warnFirst.second,
            secondTitle = warnSecond.first,
            secondBody = warnSecond.second,
            confirmLabel = stringResource(R.string.common_i_understand),
            onConfirmed = {
                asking = false
                unlocked = true
            },
            onCancelled = { asking = false },
        )
    }
}

/** Sub-second values must not read as "0s", which is what integer seconds would give. */
@Composable
fun formatDuration(ms: Int): String = when {
    ms >= 60_000 -> {
        val m = ms / 60_000
        val s = (ms % 60_000) / 1000
        if (s == 0) stringResource(R.string.duration_minutes, m)
        else stringResource(R.string.duration_minutes_seconds, m, s)
    }
    ms >= 10_000 -> stringResource(R.string.duration_seconds, ms / 1000)
    // The fraction is formatted with the default locale so that a comma decimal separator appears
    // where that is what a reader expects.
    ms >= 1_000 -> stringResource(R.string.duration_seconds_fraction, "%.1f".format(ms / 1000f))
    else -> stringResource(R.string.duration_ms, ms)
}
