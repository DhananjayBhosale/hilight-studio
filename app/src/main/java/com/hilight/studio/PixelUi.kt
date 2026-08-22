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
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

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

@Composable
fun PixelSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
    format: (Float) -> String = { "%.0f".format(it) },
) {
    Column {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Box(
                Modifier
                    .background(MaterialTheme.colorScheme.secondaryContainer, CircleShape)
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
}

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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> SegmentedSelector(
    options: List<T>,
    selected: T,
    label: (T) -> String,
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
            ) { ButtonLabel(if (step == 1) "Continue" else confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onCancelled) { ButtonLabel("Keep it short") }
        },
    )
}

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
            confirmLabel = "I understand",
            onConfirmed = {
                asking = false
                unlocked = true
            },
            onCancelled = { asking = false },
        )
    }
}

fun formatDuration(ms: Int): String = when {
    ms >= 60_000 -> {
        val m = ms / 60_000
        val s = (ms % 60_000) / 1000
        if (s == 0) "${m}m" else "${m}m ${s}s"
    }
    ms >= 10_000 -> "${ms / 1000}s"
    ms >= 1_000 -> "%.1fs".format(ms / 1000f)
    else -> "${ms}ms"
}
