package com.hilight.studio

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

@Composable
fun PrivacyRulesSection(
    rules: List<PrivacyRule>,
    onAdd: () -> Unit,
    onToggle: (PrivacyRule) -> Unit,
    onEdit: (PrivacyRule) -> Unit,
    onTest: (PrivacyRule) -> Unit,
    onDelete: (PrivacyRule) -> Unit,
) {
    PixelCard(tone = 2) {
        SectionTitle(stringResource(R.string.privacy_section_title))
        Caption(stringResource(R.string.privacy_intro))
        Caption(stringResource(R.string.privacy_default_summary))
        Button(onClick = onAdd, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Rounded.Add, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            ButtonLabel(stringResource(R.string.privacy_add))
        }
    }

    rules.forEach { rule ->
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
                    Box(Modifier.size(14.dp).background(Color(rule.color), CircleShape))
                    Column {
                        Text(
                            if (rule.activity == PrivacyActivity.MICROPHONE)
                                stringResource(R.string.privacy_microphone_active)
                            else stringResource(R.string.privacy_camera_active),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Caption(
                            if (rule.isCatchAll) stringResource(R.string.rules_any_app)
                            else rule.appLabel.ifBlank { rule.pkg }
                        )
                        Caption(
                            stringResource(
                                R.string.privacy_card_summary,
                                formatDuration(rule.lightMs),
                                formatDuration(rule.cooldownMs),
                            )
                        )
                    }
                }
                Switch(checked = rule.enabled, onCheckedChange = { onToggle(rule) })
            }
            LedStrip(
                rule.pattern,
                Ambient(
                    pattern = rule.pattern,
                    color = rule.color,
                    secondColor = rule.secondColor,
                    speedMs = rule.speedMs,
                    brightness = rule.brightness,
                ),
                active = rule.enabled,
                heightDp = 34,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilledTonalButton(onClick = { onEdit(rule) }, modifier = Modifier.weight(1f)) {
                    ButtonLabel(stringResource(R.string.common_edit))
                }
                FilledTonalButton(onClick = { onTest(rule) }, modifier = Modifier.weight(1f)) {
                    ButtonLabel(stringResource(R.string.common_test))
                }
                TextButton(onClick = { onDelete(rule) }, modifier = Modifier.weight(1f)) {
                    ButtonLabel(stringResource(R.string.common_delete))
                }
            }
        }
    }
}

@Composable
fun PrivacyActivityPickerDialog(onDismiss: () -> Unit, onPick: (PrivacyActivity) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = MaterialTheme.shapes.extraLarge,
        title = { Text(stringResource(R.string.privacy_choose_activity)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                PrivacyActivityChoice(
                    icon = { Icon(Icons.Rounded.Mic, contentDescription = null) },
                    title = stringResource(R.string.privacy_microphone_active),
                    body = stringResource(R.string.privacy_microphone_hint),
                    onClick = { onPick(PrivacyActivity.MICROPHONE) },
                )
                PrivacyActivityChoice(
                    icon = { Icon(Icons.Rounded.PhotoCamera, contentDescription = null) },
                    title = stringResource(R.string.privacy_camera_active),
                    body = stringResource(R.string.privacy_camera_hint),
                    onClick = { onPick(PrivacyActivity.CAMERA) },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { ButtonLabel(stringResource(R.string.common_cancel)) }
        },
    )
}

@Composable
private fun PrivacyActivityChoice(
    icon: @Composable () -> Unit,
    title: String,
    body: String,
    onClick: () -> Unit,
) {
    PixelCard(modifier = Modifier.padding(horizontal = 0.dp), tone = 1, onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            icon()
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Caption(body)
            }
        }
    }
}

@Composable
fun PrivacyRuleEditorDialog(
    rule: PrivacyRule,
    existing: List<PrivacyRule>,
    onDismiss: () -> Unit,
    onSave: (PrivacyRule) -> Unit,
    onTest: (PrivacyRule) -> Unit,
) {
    var edited by remember(rule) { mutableStateOf(rule) }
    val replacesAnother = existing.any { it.id == edited.id && it != rule }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = MaterialTheme.shapes.extraLarge,
        title = {
            Text(
                if (edited.isCatchAll) stringResource(R.string.rules_any_app)
                else edited.appLabel.ifBlank { edited.pkg }
            )
        },
        confirmButton = {
            Button(onClick = { onSave(edited) }) {
                ButtonLabel(stringResource(R.string.common_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                ButtonLabel(stringResource(R.string.common_cancel))
            }
        },
        text = {
            Column(
                Modifier.heightIn(max = 540.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                val microphone = stringResource(R.string.privacy_microphone)
                val camera = stringResource(R.string.privacy_camera)
                SegmentedSelector(
                    options = PrivacyActivity.entries,
                    selected = edited.activity,
                    label = { if (it == PrivacyActivity.MICROPHONE) microphone else camera },
                    onSelect = { activity ->
                        val defaultColor = PrivacyRule.default(activity).color
                        edited = edited.copy(activity = activity, color = defaultColor)
                    },
                )
                Caption(stringResource(R.string.privacy_observation_note))

                PatternCarousel(
                    selected = edited.pattern,
                    options = PrivacyRule.selectablePatterns,
                    onSelect = { edited = edited.copy(pattern = it) },
                )
                if (edited.pattern == Pattern.GRADIENT) {
                    ColorPicker(
                        edited.color,
                        { edited = edited.copy(color = it) },
                        stringResource(R.string.style_gradient_start),
                    )
                    ColorPicker(
                        edited.secondColor,
                        { edited = edited.copy(secondColor = it) },
                        stringResource(R.string.style_gradient_end),
                    )
                } else {
                    ColorPicker(edited.color, { edited = edited.copy(color = it) })
                }

                GatedDurationSlider(
                    label = stringResource(R.string.privacy_light_time),
                    valueMs = edited.lightMs,
                    minMs = PrivacyRule.MIN_PHASE_MS,
                    safeMaxMs = Limits.WARN_ABOVE_MS,
                    extendedMaxMs = PrivacyRule.MAX_PHASE_MS,
                    unlockLabel = stringResource(R.string.privacy_allow_long_light),
                    warnFirst = stringResource(R.string.privacy_long_warn_first_title) to
                        stringResource(R.string.privacy_long_warn_first_body),
                    warnSecond = stringResource(R.string.privacy_long_warn_second_title) to
                        stringResource(R.string.privacy_long_warn_second_body),
                    onChange = { edited = edited.copy(lightMs = it) },
                )

                PixelSlider(
                    label = stringResource(R.string.privacy_cooldown),
                    value = edited.cooldownMs.toFloat(),
                    range = PrivacyRule.MIN_PHASE_MS.toFloat()..PrivacyRule.MAX_PHASE_MS.toFloat(),
                    onChange = { edited = edited.copy(cooldownMs = it.toInt()) },
                    typeInSeconds = true,
                ) { formatDuration(it.toInt()) }
                Caption(
                    if (edited.cooldownMs < 10_000)
                        stringResource(R.string.privacy_cooldown_short_warning)
                    else stringResource(R.string.privacy_cooldown_recommendation)
                )

                if (edited.pattern.usesSpeed) {
                    PixelSlider(
                        stringResource(R.string.rules_time_per_cycle),
                        edited.speedMs.toFloat(), 150f..5000f,
                        { edited = edited.copy(speedMs = it.toInt()) },
                        typeInSeconds = true,
                    ) { formatDuration(it.toInt()) }
                }
                PixelSlider(
                    stringResource(R.string.rules_brightness),
                    edited.brightness, 0.05f..1f,
                    { edited = edited.copy(brightness = it) },
                ) { stringResource(R.string.common_percent, (it * 100).toInt()) }

                Caption(stringResource(R.string.privacy_one_minute_cap))
                FilledTonalButton(onClick = { onTest(edited) }, modifier = Modifier.fillMaxWidth()) {
                    ButtonLabel(stringResource(R.string.rules_test_on_leds))
                }
                if (replacesAnother) Caption(stringResource(R.string.privacy_replace_warning))
            }
        },
    )
}
