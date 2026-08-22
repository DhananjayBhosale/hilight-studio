package com.hilight.studio

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * The charging gauge: one card, because there is one battery.
 *
 * [batteryPct] is the level right now, so the strip on the card and the Test button both show what
 * the array would actually draw rather than a made-up figure.
 */
@Composable
fun ChargingRuleSection(
    rule: ChargingRule,
    batteryPct: Int,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onTest: () -> Unit,
) {
    PixelCard(tone = 2) {
        SectionTitle(stringResource(R.string.charging_section_title))
        Caption(stringResource(R.string.charging_intro))
    }

    PixelCard {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(Icons.Rounded.BatteryChargingFull, contentDescription = null)
                Column {
                    Text(
                        stringResource(R.string.charging_rule_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Caption(chargingSummary(rule))
                }
            }
            Switch(checked = rule.enabled, onCheckedChange = onToggle)
        }
        LedStrip(
            Pattern.BATTERY,
            rule.previewLook(batteryPct / 100f),
            active = rule.enabled,
            heightDp = 34,
        )
        Caption(stringResource(R.string.charging_now_level, batteryPct))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FilledTonalButton(onClick = onEdit, modifier = Modifier.weight(1f)) {
                ButtonLabel(stringResource(R.string.common_edit))
            }
            FilledTonalButton(onClick = onTest, modifier = Modifier.weight(1f)) {
                ButtonLabel(stringResource(R.string.common_test))
            }
        }
    }
}

/** "On plug-in · When full · 8 s", plus the repeat interval when one is set. */
@Composable
private fun chargingSummary(rule: ChargingRule): String {
    // resolved unconditionally so the composable call count does not depend on the rule
    val plugIn = stringResource(R.string.charging_trigger_plug_in)
    val full = stringResource(R.string.charging_trigger_full)
    val moments = listOfNotNull(plugIn.takeIf { rule.onPlugIn }, full.takeIf { rule.onFull })
    val moment = if (moments.isEmpty()) stringResource(R.string.charging_no_triggers)
    else moments.joinToString(" · ")
    val base = stringResource(R.string.charging_card_summary, moment, formatDuration(rule.durationMs))
    val repeat = stringResource(
        R.string.charging_repeat_summary,
        stringResource(R.string.duration_minutes, rule.repeatEveryMin),
    )
    return if (rule.repeatEveryMin > 0) "$base · $repeat" else base
}

/**
 * The gauge's editor, as a full screen of cards rather than a dialog.
 *
 * Every other editor in the app is an AlertDialog, and this one started as one too. It has twice
 * their controls, though, and in a dialog's width the three-way selectors wrapped, the preset chips
 * were cut off, and the sliders sat on top of each other with nothing to say what belonged to what.
 * A screen laid out the way the tabs are — one card per subject, each with its own title — gives the
 * controls room and makes the grouping visible. Back or the close button discards; Save keeps.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChargingRuleEditorDialog(
    rule: ChargingRule,
    batteryPct: Int,
    onDismiss: () -> Unit,
    onSave: (ChargingRule) -> Unit,
    onTest: (ChargingRule) -> Unit,
) {
    var edited by remember(rule) { mutableStateOf(rule) }
    var editingLed by remember { mutableIntStateOf(0) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text(stringResource(R.string.charging_rule_title)) },
                        navigationIcon = {
                            IconButton(onClick = onDismiss) {
                                Icon(
                                    Icons.Rounded.Close,
                                    contentDescription = stringResource(R.string.common_cancel),
                                )
                            }
                        },
                        actions = {
                            Button(
                                onClick = { onSave(edited) },
                                modifier = Modifier.padding(end = 12.dp),
                            ) { ButtonLabel(stringResource(R.string.common_save)) }
                        },
                    )
                },
            ) { pad ->
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(pad)
                        .verticalScroll(rememberScrollState()),
                ) {
                    // What the array will draw right now, at the real level.
                    PixelCard(tone = 2) {
                        LedStrip(Pattern.BATTERY, edited.previewLook(batteryPct / 100f), heightDp = 46)
                        Caption(stringResource(R.string.charging_now_level, batteryPct))
                    }

                    PixelCard {
                        SectionTitle(stringResource(R.string.charging_group_when))
                        ToggleRow(stringResource(R.string.charging_trigger_plug_in), edited.onPlugIn) {
                            edited = edited.copy(onPlugIn = it)
                        }
                        ToggleRow(stringResource(R.string.charging_trigger_full), edited.onFull) {
                            edited = edited.copy(onFull = it)
                        }
                    }

                    PixelCard {
                        SectionTitle(stringResource(R.string.charging_group_colours))
                        // A preset sets the mode and colours below, which stay editable; the
                        // highlighted chip follows the colours rather than the last tap.
                        ChipCarousel(
                            selected = ChargingPreset.matching(edited),
                            options = ChargingPreset.entries,
                            key = { it.name },
                            label = { stringResource(it.labelRes) },
                            onSelect = { edited = it.applyTo(edited) },
                            leading = { LedDots(it.previewLook()) },
                        )
                        SegmentedSelector(
                            options = ChargingColorMode.entries,
                            selected = edited.colorMode,
                            label = { stringResource(it.labelRes) },
                            onSelect = { edited = edited.copy(colorMode = it) },
                        )
                        when (edited.colorMode) {
                            ChargingColorMode.LEVEL ->
                                Caption(stringResource(R.string.charging_by_level_hint))

                            ChargingColorMode.SINGLE ->
                                ColorPicker(edited.color, { edited = edited.copy(color = it) })

                            ChargingColorMode.PER_LED -> {
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                                ) {
                                    edited.perLed.forEachIndexed { i, c ->
                                        LedSwatch(
                                            color = c,
                                            selected = i == editingLed,
                                            modifier = Modifier.weight(1f),
                                        ) { editingLed = i }
                                    }
                                }
                                ColorPicker(
                                    color = edited.perLed[editingLed],
                                    onColor = { c ->
                                        edited = edited.copy(
                                            perLed = edited.perLed.toMutableList()
                                                .also { it[editingLed] = c },
                                        )
                                    },
                                    label = stringResource(R.string.style_led_number, editingLed + 1),
                                )
                            }
                        }
                    }

                    PixelCard {
                        SectionTitle(stringResource(R.string.charging_group_readability))
                        SegmentedSelector(
                            options = ChargingFill.entries,
                            selected = edited.fill,
                            label = { stringResource(it.labelRes) },
                            onSelect = { edited = edited.copy(fill = it) },
                        )
                        if (edited.fill == ChargingFill.STEP) {
                            PixelSlider(
                                stringResource(R.string.charging_step_time),
                                edited.stepMs.toFloat(),
                                ChargingRule.MIN_STEP_MS.toFloat()..ChargingRule.MAX_STEP_MS.toFloat(),
                                { edited = edited.copy(stepMs = it.toInt()) },
                            ) { formatDuration(it.toInt()) }
                        }
                        ToggleRow(stringResource(R.string.charging_blink_tip), edited.blinkTip) {
                            edited = edited.copy(blinkTip = it)
                        }
                        ToggleRow(stringResource(R.string.charging_alternate), edited.alternate) {
                            edited = edited.copy(alternate = it)
                        }
                        if (edited.alternate) {
                            PixelSlider(
                                stringResource(R.string.charging_alternate_level),
                                edited.alternateLevel, 0f..ChargingRule.MAX_ALTERNATE_LEVEL,
                                { edited = edited.copy(alternateLevel = it) },
                            ) {
                                if (it < 0.01f) stringResource(R.string.charging_alternate_off)
                                else stringResource(R.string.common_percent, (it * 100).toInt())
                            }
                        }
                        Caption(stringResource(R.string.charging_readability_hint))
                    }

                    PixelCard {
                        SectionTitle(stringResource(R.string.charging_group_timing))
                        GatedDurationSlider(
                            label = stringResource(R.string.rules_show_for),
                            valueMs = edited.durationMs,
                            minMs = ChargingRule.MIN_DURATION_MS,
                            safeMaxMs = Limits.WARN_ABOVE_MS,
                            extendedMaxMs = Limits.RULE_MAX_MS,
                            unlockLabel = stringResource(R.string.rules_allow_one_minute),
                            warnFirst = stringResource(R.string.charging_duration_warn_first_title) to
                                stringResource(R.string.charging_duration_warn_first_body),
                            warnSecond = stringResource(R.string.charging_duration_warn_second_title) to
                                stringResource(R.string.charging_duration_warn_second_body),
                            onChange = { edited = edited.copy(durationMs = it) },
                        )
                        PixelSlider(
                            stringResource(R.string.rules_brightness),
                            edited.brightness, 0.05f..1f,
                            { edited = edited.copy(brightness = it) },
                        ) { stringResource(R.string.common_percent, (it * 100).toInt()) }
                        PixelSlider(
                            stringResource(R.string.charging_repeat),
                            edited.repeatEveryMin.toFloat(),
                            0f..ChargingRule.MAX_REPEAT_MIN.toFloat(),
                            { edited = edited.copy(repeatEveryMin = it.toInt()) },
                        ) {
                            if (it < 1f) stringResource(R.string.charging_repeat_off)
                            else stringResource(R.string.duration_minutes, it.toInt())
                        }
                        ToggleRow(
                            stringResource(R.string.rules_only_screen_off),
                            edited.onlyWhenScreenOff,
                        ) { edited = edited.copy(onlyWhenScreenOff = it) }
                    }

                    PixelCard(tone = 0) {
                        FilledTonalButton(
                            onClick = { onTest(edited) },
                            modifier = Modifier.fillMaxWidth(),
                        ) { ButtonLabel(stringResource(R.string.rules_test_on_leds)) }
                        Caption(stringResource(R.string.charging_safety_note))
                    }

                    Spacer(Modifier.height(28.dp))
                }
            }
        }
    }
}

/**
 * Eight still dots showing what [look] settles to, for a preset chip.
 *
 * Drawn from one settled frame rather than through [LedStrip], which animates every strip at the
 * hardware frame rate: a row of a dozen chips would otherwise run a dozen 30 fps loops for nothing.
 */
@Composable
private fun LedDots(look: Ambient) {
    val frame = remember(look) { Renderer.frame(Pattern.BATTERY, SETTLED_MS, look) }
    Canvas(Modifier.width(52.dp).height(12.dp)) {
        val n = frame.size
        val step = size.width / n
        val r = minOf(step, size.height) / 2.4f
        for (i in 0 until n) {
            val c = Color(frame[i])
            val centre = Offset(i * step + step / 2f, size.height / 2f)
            drawCircle(Color.Black.copy(alpha = 0.18f), radius = r * 1.15f, center = centre)
            drawCircle(c, radius = r, center = centre)
        }
    }
}

/** Long after any fill sweep has finished. */
private const val SETTLED_MS = 100_000L
