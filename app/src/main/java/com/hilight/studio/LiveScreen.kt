package com.hilight.studio

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Casino
import androidx.compose.material.icons.rounded.Flare
import androidx.compose.material.icons.rounded.Nightlight
import androidx.compose.material.icons.rounded.Waves
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay

/**
 * Explains what the safety limits are doing, so a dark array never looks like a fault.
 *
 * The countdown ticks locally between status polls, which arrive about every 1.5 s.
 */
@Composable
private fun SafetyState(status: HelperStatus) {
    var elapsedMs by remember(status.ambientRemainingMs, status.ambientHeld) { mutableLongStateOf(0L) }
    LaunchedEffect(status.ambientRemainingMs, status.ambientHeld) {
        while (true) {
            delay(500)
            elapsedMs += 500
        }
    }
    val remaining = (status.ambientRemainingMs - elapsedMs).coerceAtLeast(0)

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        when {
            status.resting ->
                Caption("Resting to protect the LEDs — they have been lit too much recently.")
            status.ambientHeld || remaining == 0L ->
                Caption("Auto-off reached. Change the style or flip the switch to light it again.")
            else ->
                Caption("Auto-off in ${remaining / 1000}s · duty used ${status.dutyPct}%")
        }
        Caption("renderer pid ${status.pid} · ${if (status.sessionOpen) "session open" else "session closed"}")
    }
}

/** White-light effects have no colour of their own, so give their tiles a readable accent. */
@Composable
private fun tileAccent(label: String, color: Int): Color = when {
    color != 0xFFFFFFFF.toInt() -> Color(color)
    label == "Rainbow" -> Color(0xFF7C4DFF)
    else -> Color(0xFFFFB300)
}

/** Home surface: the phone itself, the master switch, and one-tap effects. */
@Composable
fun LiveScreen(store: Store) {
    val enabled by store.enabled.collectAsStateWithLifecycle()
    val ambient by store.ambient.collectAsStateWithLifecycle()
    val status by store.status.collectAsStateWithLifecycle()
    val rules by store.rules.collectAsStateWithLifecycle()
    val suppression by store.suppression.collectAsStateWithLifecycle()
    val previewLook by store.previewLook.collectAsStateWithLifecycle()

    val profile = rememberDeviceProfile()

    PixelCard(tone = 0) {
        // while a test is running the hero shows the test, not the ambient look
        val shown = previewLook ?: ambient
        DeviceHero(
            pattern = if (enabled) shown.pattern else Pattern.OFF,
            cfg = shown,
            active = enabled && status.alive,
            profile = profile,
        )
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                when {
                    !profile.hasHiLight -> "HiLight not available"
                    previewLook != null -> "Testing · ${shown.pattern.label}"
                    enabled -> "HiLight · ${ambient.pattern.label}"
                    else -> "HiLight is with the system"
                },
                style = MaterialTheme.typography.titleMedium,
            )
            Caption(profile.label)
        }
        Caption(
            when {
                !profile.hasHiLight ->
                    "${profile.label} has no HiLight array — the feature is Pro-only."
                !status.alive -> "Connect a renderer in Setup to drive the array."
                enabled -> "Turn the phone over to see it for real."
                else -> "Take over the array with the switch below."
            }
        )
    }

    PixelCard(tone = 2) {
        PixelToggleRow(
            title = if (enabled) "Driving HiLight" else "System has HiLight",
            subtitle = null,
            checked = enabled,
            onChange = { store.setEnabled(it) },
        )
        suppression?.let {
            Caption(
                when (it) {
                    Suppression.QUIET_HOURS -> "Quiet hours: the array stays dark until your window ends."
                    Suppression.LOW_BATTERY -> "Battery is low, so the array is paused. Charging resumes it."
                    Suppression.POWER_SAVER -> "Battery Saver is on, so the array is paused."
                    Suppression.SCREEN_ON -> "Set to light only while the screen is off."
                }
            )
        }
        AnimatedVisibility(
            visible = status.alive && enabled && suppression == null,
            enter = fadeIn(tween(200)) + expandVertically(tween(240)),
            exit = fadeOut(tween(120)) + shrinkVertically(tween(200)),
        ) {
            SafetyState(status)
        }
    }

    val tests: List<Triple<String, ImageVector, Pair<Pattern, Int>>> = listOf(
        Triple("Rainbow", Icons.Rounded.AutoAwesome, Pattern.RAINBOW to 0xFFFFFFFF.toInt()),
        Triple("Random", Icons.Rounded.Casino, Pattern.RANDOM to 0xFFFFFFFF.toInt()),
        // tile accents are chosen for legibility; the effect colours themselves are above
        Triple("Comet", Icons.Rounded.Flare, Pattern.COMET to 0xFF00E5FF.toInt()),
        Triple("Pulse", Icons.Rounded.Bolt, Pattern.PULSE to 0xFFFF1744.toInt()),
        Triple("Breathe", Icons.Rounded.Nightlight, Pattern.BREATHE to 0xFF7C4DFF.toInt()),
        Triple("Wave", Icons.Rounded.Waves, Pattern.WAVE to 0xFF00E676.toInt()),
    )

    PixelCard {
        SectionTitle("Try an effect")
        Caption("Fires for four seconds on the real LEDs, then returns to your style.")
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            tests.chunked(3).forEach { row ->
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    row.forEach { (label, icon, spec) ->
                        PixelTile(
                            label = label,
                            icon = icon,
                            accent = tileAccent(label, spec.second),
                            enabled = enabled && status.alive,
                            modifier = Modifier.weight(1f),
                        ) { store.preview(spec.first, spec.second, 1200, 1f) }
                    }
                }
            }
        }
    }

    PixelCard {
        SectionTitle("App rules", trailing = { Caption("${rules.count { it.enabled }} on") })
        if (rules.isEmpty()) {
            Caption("Nothing yet. Add per-app colours in the Apps tab.")
        } else {
            rules.forEach { r ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(r.label, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        (if (r.randomColor) "random" else r.pattern.label) +
                            " · " + r.conditionLabel(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (r.enabled) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
