package com.hilight.studio

import android.content.Intent
import android.content.pm.ApplicationInfo
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class InstalledApp(val pkg: String, val label: String, val info: ApplicationInfo?)

/** "Show X for app Y" rules. */
@Composable
fun AppRulesScreen(store: Store) {
    val rules by store.rules.collectAsStateWithLifecycle()
    var picking by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<AppRule?>(null) }

    PixelCard(tone = 2) {
        SectionTitle("Per-app rules")
        Caption("Choose an app, then what HiLight does when it notifies you — or while it is open.")
        Button(onClick = { picking = true }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Rounded.Add, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            ButtonLabel("Add app rule")
        }
    }

    rules.groupBy { it.pkg }.values.forEachIndexed { index, appRules ->
        val app = appRules.first()
        // cards ease in rather than appearing, staggered down the list
        AnimatedVisibility(
            visible = true,
            enter = fadeIn(tween(220, delayMillis = index * 40)) +
                slideInVertically(spring(dampingRatio = Spring.DampingRatioLowBouncy)) { it / 6 } +
                scaleIn(tween(240), initialScale = 0.97f),
        ) {
            RuleGroupCard(
                label = app.label,
                rules = appRules,
                onAdd = { editing = AppRule(pkg = app.pkg, label = app.label) },
                onToggle = { rule, enabled -> store.upsertRule(rule.copy(enabled = enabled)) },
                onEdit = { editing = it },
                onTest = {
                    store.preview(it.pattern, it.color, it.speedMs, it.brightness, it.durationMs)
                },
                onDelete = { store.removeRule(it) },
            )
        }
    }

    if (picking) {
        AppPickerDialog(
            onDismiss = { picking = false },
            onPick = { app ->
                picking = false
                editing = AppRule(pkg = app.pkg, label = app.label)
            },
        )
    }

    editing?.let { rule ->
        RuleEditorDialog(
            rule = rule,
            onDismiss = { editing = null },
            onSave = {
                store.upsertRule(it)
                editing = null
            },
            onTest = { store.preview(it.pattern, it.color, it.speedMs, it.brightness, it.durationMs) },
        )
    }
}

@Composable
private fun RuleGroupCard(
    label: String,
    rules: List<AppRule>,
    onAdd: () -> Unit,
    onToggle: (AppRule, Boolean) -> Unit,
    onEdit: (AppRule) -> Unit,
    onTest: (AppRule) -> Unit,
    onDelete: (AppRule) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    PixelCard {
        SectionTitle(
            label,
            trailing = {
                TextButton(onClick = onAdd) {
                    Icon(Icons.Rounded.Add, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    ButtonLabel("Add rule")
                }
            },
        )
        rules.forEach { rule ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top,
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(rule.conditionLabel(), style = MaterialTheme.typography.bodyLarge)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (rule.randomColor) {
                                Caption("Random colour")
                            } else {
                                Box(
                                    Modifier
                                        .size(11.dp)
                                        .background(Color(rule.color), CircleShape)
                                )
                                Spacer(Modifier.width(6.dp))
                                Caption("Colour")
                            }
                            Spacer(Modifier.width(8.dp))
                            Caption(
                                rule.pattern.label + " · " +
                                    if (rule.trigger == Trigger.NOTIFICATION) formatDuration(rule.durationMs)
                                    else "until closed"
                            )
                        }
                    }
                    Switch(
                        checked = rule.enabled,
                        onCheckedChange = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            onToggle(rule, it)
                        },
                    )
                }
                LedStrip(
                    rule.pattern,
                    Ambient(
                        pattern = rule.pattern,
                        color = rule.color,
                        speedMs = rule.speedMs,
                        brightness = rule.brightness,
                    ),
                    active = rule.enabled,
                    heightDp = 34,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(onClick = { onEdit(rule) }, modifier = Modifier.weight(1f)) {
                        ButtonLabel("Edit")
                    }
                    TextButton(onClick = { onTest(rule) }, modifier = Modifier.weight(1f)) {
                        ButtonLabel("Test")
                    }
                    TextButton(onClick = { onDelete(rule) }, modifier = Modifier.weight(1f)) {
                        ButtonLabel("Delete")
                    }
                }
            }
        }
    }
}

fun AppRule.conditionLabel(): String = when (trigger) {
    Trigger.FOREGROUND -> "While open"
    Trigger.NOTIFICATION -> keyword.trim().takeIf { it.isNotEmpty() }
        ?.let { "Notification contains \u201c$it\u201d" }
        ?: "Any notification"
}

@Composable
private fun AppPickerDialog(onDismiss: () -> Unit, onPick: (InstalledApp) -> Unit) {
    val ctx = LocalContext.current
    var query by remember { mutableStateOf("") }
    val apps by produceState(initialValue = emptyList<InstalledApp>()) {
        value = withContext(Dispatchers.IO) {
            val pm = ctx.packageManager
            val launchable = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            pm.queryIntentActivities(launchable, 0)
                .mapNotNull { ri ->
                    val ai = ri.activityInfo?.applicationInfo ?: return@mapNotNull null
                    InstalledApp(ai.packageName, pm.getApplicationLabel(ai).toString(), ai)
                }
                .distinctBy { it.pkg }
                .sortedBy { it.label.lowercase() }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = MaterialTheme.shapes.extraLarge,
        confirmButton = { TextButton(onClick = onDismiss) { ButtonLabel("Cancel") } },
        title = { Text("Choose an app") },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search") },
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                )
                val shown = apps.filter { it.label.contains(query, ignoreCase = true) }
                LazyColumn(Modifier.heightIn(max = 380.dp)) {
                    // a rule that covers every app without one of its own
                    item(key = AppRule.ANY_APP) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onPick(InstalledApp(AppRule.ANY_APP, "Any app", null))
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
                                Icon(Icons.Rounded.Apps, contentDescription = null)
                            }
                            Column {
                                Text("Any app", style = MaterialTheme.typography.bodyLarge)
                                Caption("Catch-all for apps without their own rule")
                            }
                        }
                    }
                    items(shown, key = { it.pkg }) { app ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onPick(app) }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            AppIcon(app)
                            Text(app.label, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }
        },
    )
}

@Composable
private fun AppIcon(app: InstalledApp) {
    val ctx = LocalContext.current
    val bmp by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, app.pkg) {
        val info = app.info ?: return@produceState
        value = withContext(Dispatchers.IO) {
            runCatching {
                ctx.packageManager.getApplicationIcon(info).toBitmap(80, 80).asImageBitmap()
            }.getOrNull()
        }
    }
    Box(Modifier.size(32.dp)) {
        bmp?.let { Image(it, contentDescription = null, modifier = Modifier.size(32.dp)) }
    }
}

@Composable
private fun RuleEditorDialog(
    rule: AppRule,
    onDismiss: () -> Unit,
    onSave: (AppRule) -> Unit,
    onTest: (AppRule) -> Unit,
) {
    var r by remember { mutableStateOf(rule) }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = MaterialTheme.shapes.extraLarge,
        title = { Text(r.label) },
        confirmButton = { Button(onClick = { onSave(r) }) { ButtonLabel("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { ButtonLabel("Cancel") } },
        text = {
            Column(
                Modifier
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                LedStrip(
                    r.pattern,
                    Ambient(
                        pattern = r.pattern,
                        color = r.color,
                        speedMs = r.speedMs,
                        brightness = r.brightness,
                    ),
                    heightDp = 38,
                )

                SegmentedSelector(
                    options = listOf(Trigger.NOTIFICATION, Trigger.FOREGROUND),
                    selected = r.trigger,
                    label = { if (it == Trigger.NOTIFICATION) "On notification" else "While open" },
                    onSelect = { r = r.copy(trigger = it) },
                )
                Caption("Notification rules may be multiple; only one while-open rule is allowed.")

                PatternCarousel(
                    selected = r.pattern,
                    options = Pattern.entries.filter { it != Pattern.OFF && it != Pattern.CUSTOM },
                    onSelect = { r = r.copy(pattern = it) },
                )

                ToggleRow("Random colour each time", r.randomColor) { r = r.copy(randomColor = it) }
                if (!r.randomColor) {
                    ColorPicker(r.color, { r = r.copy(color = it) })
                }

                if (r.trigger == Trigger.NOTIFICATION) {
                    OutlinedTextField(
                        value = r.keyword,
                        onValueChange = { r = r.copy(keyword = it) },
                        label = { Text("Notification contains (optional)") },
                        supportingText = {
                            Caption(
                                if (r.keyword.isBlank()) {
                                    "Leave blank to match any notification from ${r.label}."
                                } else {
                                    "Matches ${r.label} notifications containing this text."
                                }
                            )
                        },
                        singleLine = true,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    GatedDurationSlider(
                        label = "Show for",
                        valueMs = r.durationMs,
                        minMs = 2_000,
                        safeMaxMs = Limits.WARN_ABOVE_MS,
                        extendedMaxMs = Limits.RULE_MAX_MS,
                        unlockLabel = "Allow up to 1 minute",
                        warnFirst = "Longer than 30 seconds?" to
                            "Every notification from this app would light the array for that long, " +
                                "which costs battery and is far beyond what stock HiLight does.",
                        warnSecond = "Are you sure?" to
                            "A busy app can fire often, so the LEDs may end up lit most of the time. " +
                                "You can turn this back down at any time.",
                        onChange = { r = r.copy(durationMs = it) },
                    )
                    ToggleRow("Only when the screen is off", r.onlyWhenScreenOff) {
                        r = r.copy(onlyWhenScreenOff = it)
                    }
                }
                if (r.pattern.usesSpeed) {
                    PixelSlider("Time per cycle", r.speedMs.toFloat(), 150f..5000f, {
                        r = r.copy(speedMs = it.toInt())
                    }) { formatDuration(it.toInt()) }
                    r.pattern.cycleMeaning?.let { Caption(it) }
                }
                PixelSlider("Brightness", r.brightness, 0.05f..1f, {
                    r = r.copy(brightness = it)
                }) { "${(it * 100).toInt()}%" }

                FilledTonalButton(onClick = { onTest(r) }, modifier = Modifier.fillMaxWidth()) {
                    ButtonLabel("Test on the LEDs")
                }
            }
        },
    )
}
