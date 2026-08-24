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
import androidx.compose.runtime.key
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A rule's name as it should read now, rather than as it was stored.
 *
 * The catch-all rule has no app to be named after, so its label is written when the rule is created —
 * which means a rule made while the phone was in Japanese would keep its Japanese name after a switch
 * back to English, and the other way round. Resolving it at display time costs nothing and makes the
 * stored label irrelevant for the one rule whose label was never really data.
 */
@Composable
fun ruleLabel(rule: AppRule): String =
    if (rule.isCatchAll) stringResource(R.string.rules_any_app) else rule.label

data class InstalledApp(val pkg: String, val label: String, val info: ApplicationInfo?)

/** "Show X for app Y" rules, plus the per-chat rules nested under the app they belong to. */
@Composable
fun AppRulesScreen(store: Store) {
    val rules by store.rules.collectAsStateWithLifecycle()
    val privacyRules by store.privacyRules.collectAsStateWithLifecycle()
    val conversations by store.conversations.collectAsStateWithLifecycle()
    val lastMatch by store.lastMatch.collectAsStateWithLifecycle()
    var picking by remember { mutableStateOf(false) }
    var scoping by remember { mutableStateOf<InstalledApp?>(null) }
    var pickingChatIn by remember { mutableStateOf<InstalledApp?>(null) }
    var editing by remember { mutableStateOf<AppRule?>(null) }
    var editingPrivacy by remember { mutableStateOf<PrivacyRule?>(null) }
    var choosingPrivacyActivity by remember { mutableStateOf(false) }
    var privacyPrefilledApp by remember { mutableStateOf<InstalledApp?>(null) }
    var pickingPrivacyAppFor by remember { mutableStateOf<PrivacyActivity?>(null) }

    PixelCard(tone = 2) {
        SectionTitle(stringResource(R.string.rules_section_title))
        Caption(stringResource(R.string.rules_intro_apps))
        Caption(stringResource(R.string.rules_intro_messaging))
        Button(onClick = { picking = true }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Rounded.Add, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            ButtonLabel(stringResource(R.string.rules_add))
        }
    }

    // An app's own rule and the per-chat rules under it have to sit together, or a colour for one
    // contact reads as an unrelated app halfway down the list. Grouping by package keeps the apps in
    // the order they were added — groupBy preserves that — and the plain rule leads its own group.
    val ordered = remember(rules) {
        rules.groupBy { it.pkg }.values.flatMap { group ->
            group.sortedWith(
                compareBy<AppRule>({ it.isConversationRule }, { it.conversationName ?: "" })
            )
        }
    }

    ordered.forEachIndexed { index, rule ->
        key(rule.id) {
            // cards ease in rather than appearing, staggered down the list
            AnimatedVisibility(
                visible = true,
                enter = fadeIn(tween(220, delayMillis = index * 40)) +
                    slideInVertically(spring(dampingRatio = Spring.DampingRatioLowBouncy)) { it / 6 } +
                    scaleIn(tween(240), initialScale = 0.97f),
            ) {
                RuleCard(
                    rule = rule,
                    chat = knownConversation(rule, conversations),
                    lastMatchedMs = lastMatch[rule.id],
                    onToggle = { store.upsertRule(rule.copy(enabled = it)) },
                    onEdit = { editing = rule },
                    onTest = {
                        // test what the rule will actually do, including how long it stays lit
                        store.preview(
                            rule.pattern, rule.color, rule.speedMs, rule.brightness, rule.durationMs,
                        )
                    },
                    onDelete = { store.removeRule(rule) },
                )
            }
        }
    }

    PrivacyRulesSection(
        rules = privacyRules,
        onAdd = {
            privacyPrefilledApp = null
            choosingPrivacyActivity = true
        },
        onToggle = { store.upsertPrivacyRule(it.copy(enabled = !it.enabled), replacing = it) },
        onEdit = { editingPrivacy = it },
        onTest = { store.preview(it.pattern, it.color, it.speedMs, it.brightness, it.lightMs) },
        onDelete = store::removePrivacyRule,
    )

    if (picking) {
        AppPickerDialog(
            alsoOffer = conversations.mapTo(mutableSetOf()) { it.pkg },
            onDismiss = { picking = false },
            onPick = { app ->
                picking = false
                // The scope step only appears where a per-chat rule could actually fire, so the
                // ordinary "flash for this app" rule still costs one tap for everything else.
                if (offersConversations(store, app)) scoping = app
                else editing = AppRule(pkg = app.pkg, label = app.label)
            },
        )
    }

    scoping?.let { app ->
        RuleScopeDialog(
            appLabel = app.label,
            onDismiss = { scoping = null },
            onPick = { scope ->
                scoping = null
                when (scope) {
                    RuleScope.WHOLE_APP -> editing = AppRule(pkg = app.pkg, label = app.label)
                    RuleScope.ONE_CHAT -> pickingChatIn = app
                }
            },
        )
    }

    pickingChatIn?.let { app ->
        ConversationPickerDialog(
            store = store,
            pkg = app.pkg,
            appLabel = app.label,
            onDismiss = { pickingChatIn = null },
            onPicked = { ref ->
                pickingChatIn = null
                // The label stays the app's own and the chat travels beside it: the card shows the
                // app as an overline above the chat, and the matcher needs the two kept apart.
                val fresh = AppRule(
                    pkg = app.pkg,
                    label = app.label,
                    conversationKey = ref.key,
                    conversationName = ref.name,
                    conversationIsGroup = ref.isGroup,
                )
                // A chat that already has a rule opens that rule instead of a blank one. Both share
                // an id, so saving the blank one would overwrite the colour already chosen for them.
                editing = rules.firstOrNull { it.id == fresh.id } ?: fresh
            },
        )
    }

    editing?.let { rule ->
        RuleEditorDialog(
            rule = rule,
            // The whole rule set travels into the editor because rule identity is derived from
            // fields the editor can change, so only the list can say whether the rule being saved
            // is about to land on top of a different one.
            existing = rules,
            chatIsGroup = rule.conversationIsGroup ||
                knownConversation(rule, conversations)?.isGroup == true,
            onDismiss = { editing = null },
            onSave = {
                // The rule being edited is handed over as well: changing the trigger moves it to a
                // different id, and without the old one the edit would leave a duplicate behind.
                store.upsertRule(it, replacing = rule)
                editing = null
            },
            onTest = { store.preview(it.pattern, it.color, it.speedMs, it.brightness, it.durationMs) },
            onAddPrivacy = if (rule.isConversationRule || rule.isCatchAll) null else ({
                editing = null
                privacyPrefilledApp = InstalledApp(rule.pkg, rule.label, null)
                choosingPrivacyActivity = true
            }),
        )
    }

    if (choosingPrivacyActivity) {
        PrivacyActivityPickerDialog(
            onDismiss = {
                choosingPrivacyActivity = false
                privacyPrefilledApp = null
            },
            onPick = { activity ->
                choosingPrivacyActivity = false
                val app = privacyPrefilledApp
                privacyPrefilledApp = null
                if (app == null) {
                    pickingPrivacyAppFor = activity
                } else {
                    val fresh = PrivacyRule.default(activity, app.pkg, app.label)
                    editingPrivacy = privacyRules.firstOrNull { it.id == fresh.id } ?: fresh
                }
            },
        )
    }

    pickingPrivacyAppFor?.let { activity ->
        AppPickerDialog(
            onDismiss = { pickingPrivacyAppFor = null },
            onPick = { app ->
                pickingPrivacyAppFor = null
                val fresh = PrivacyRule.default(activity, app.pkg, app.label)
                editingPrivacy = privacyRules.firstOrNull { it.id == fresh.id } ?: fresh
            },
        )
    }

    editingPrivacy?.let { rule ->
        PrivacyRuleEditorDialog(
            rule = rule,
            existing = privacyRules,
            onDismiss = { editingPrivacy = null },
            onSave = {
                store.upsertPrivacyRule(it, replacing = rule)
                editingPrivacy = null
            },
            onTest = { store.preview(it.pattern, it.color, it.speedMs, it.brightness, it.lightMs) },
        )
    }
}

/**
 * Should picking [app] offer the extra "one contact or chat" step?
 *
 * Never for the catch-all: a conversation rule is only ever resolved within one package, so one
 * attached to the "any app" sentinel could not match anything and would look broken instead.
 */
private fun offersConversations(store: Store, app: InstalledApp): Boolean =
    app.pkg != AppRule.ANY_APP &&
        (store.conversationsFor(app.pkg).isNotEmpty() || MessagingApps.looksLikeMessaging(app.pkg))

/**
 * One rule.
 *
 * [chat] is the conversation this rule names as HiLight last saw it, which is where the group badge
 * comes from — the rule itself stores only what the matcher needs. [lastMatchedMs] is null when the
 * rule has never fired, and saying so plainly matters: a per-contact rule that silently matches
 * nothing looks identical to one that works until the day you need it.
 */
@Composable
private fun RuleCard(
    rule: AppRule,
    chat: ConversationRef?,
    lastMatchedMs: Long?,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onTest: () -> Unit,
    onDelete: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val perChat = rule.isConversationRule
    // A per-chat rule is inset and a shade darker than the cards around it, so it reads as hanging
    // off the app above rather than as another app of its own.
    PixelCard(
        modifier = if (perChat) Modifier.padding(start = 14.dp) else Modifier,
        tone = if (perChat) 0 else 1,
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier.fillMaxWidth(0.72f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (!rule.randomColor) {
                    Box(
                        Modifier
                            .size(14.dp)
                            .background(Color(rule.color), CircleShape)
                    )
                }
                Column {
                    if (perChat) {
                        Caption(ruleLabel(rule))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                rule.conversationName ?: ruleLabel(rule),
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            // The rule's own field is consulted alongside the learned chat because
                            // the learned list is not a reliable source for this: it is capped at
                            // ConversationRef.MAX_REMEMBERED, the user can clear it from the setup
                            // screen, and a rule made through the contact picker was never in it at
                            // all. Reading only the list made the badge vanish in all three cases,
                            // which is exactly what conversationIsGroup was added to prevent.
                            when {
                                chat?.isGroup == true || rule.conversationIsGroup ->
                                    ConversationBadge(stringResource(R.string.chat_badge_group))

                                rule.includeGroups ->
                                    ConversationBadge(stringResource(R.string.rules_badge_groups_too))
                            }
                        }
                    } else {
                        Text(ruleLabel(rule), style = MaterialTheme.typography.titleMedium)
                    }
                    // One format string rather than three fragments joined with a separator: the
                    // order of "what it looks like" and "when it fires" is not the same in every
                    // language, and neither is the punctuation between them.
                    Caption(
                        stringResource(
                            R.string.rules_card_summary,
                            if (rule.randomColor) stringResource(R.string.rules_random_colour)
                            else stringResource(rule.pattern.labelRes),
                            if (rule.trigger == Trigger.NOTIFICATION)
                                stringResource(R.string.rules_trigger_notification_short)
                            else stringResource(R.string.rules_trigger_foreground_short),
                        )
                    )
                    if (rule.trigger == Trigger.NOTIFICATION) {
                        // "Matched", not "fired": the match is recorded even when a guard — quiet
                        // hours, the battery floor, the master switch — swallowed the flash, and
                        // "your rule matched but quiet hours ate it" is the more useful answer.
                        Caption(
                            if (lastMatchedMs != null) {
                                stringResource(
                                    R.string.rules_last_matched, relativeAgo(lastMatchedMs),
                                )
                            } else {
                                stringResource(R.string.rules_not_matched_yet)
                            }
                        )
                    }
                }
            }
            Switch(
                checked = rule.enabled,
                onCheckedChange = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onToggle(it)
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
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FilledTonalButton(onClick = onEdit, modifier = Modifier.weight(1f)) {
                ButtonLabel(stringResource(R.string.common_edit))
            }
            FilledTonalButton(onClick = onTest, modifier = Modifier.weight(1f)) {
                ButtonLabel(stringResource(R.string.common_test))
            }
            TextButton(onClick = onDelete, modifier = Modifier.weight(1f)) {
                ButtonLabel(stringResource(R.string.common_delete))
            }
        }
    }
}

@Composable
fun AppPickerDialog(
    onDismiss: () -> Unit,
    onPick: (InstalledApp) -> Unit,
    /**
     * Packages to offer beyond the ones carrying a launcher activity.
     *
     * A launcher query answers "what can this person open", which is not the question this screen
     * asks. `android` posts the permission prompts, and an app whose launcher has been hidden still
     * reaches the shade — both are already remembered as chats, and neither can be picked here, so
     * the screen refuses a rule for a notification HiLight has demonstrably seen.
     */
    alsoOffer: Set<String> = emptySet(),
) {
    val ctx = LocalContext.current
    var query by remember { mutableStateOf("") }
    val apps by produceState(initialValue = emptyList<InstalledApp>(), alsoOffer) {
        value = withContext(Dispatchers.IO) {
            val pm = ctx.packageManager
            val launchable = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val launcherApps = pm.queryIntentActivities(launchable, 0)
                .mapNotNull { ri ->
                    val ai = ri.activityInfo?.applicationInfo ?: return@mapNotNull null
                    InstalledApp(ai.packageName, pm.getApplicationLabel(ai).toString(), ai)
                }
            val launcherPkgs = launcherApps.mapTo(mutableSetOf()) { it.pkg }
            val seenOnly = (alsoOffer - launcherPkgs).mapNotNull { pkg ->
                runCatching {
                    val ai = pm.getApplicationInfo(pkg, 0)
                    InstalledApp(pkg, pm.getApplicationLabel(ai).toString(), ai)
                }.getOrNull()
            }
            (launcherApps + seenOnly)
                .distinctBy { it.pkg }
                .sortedBy { it.label.lowercase() }
        }
    }

    // The catch-all is not an installed app, so its name is HiLight's own word for it rather than
    // something the package manager can be asked for — and it travels into the rule as the label.
    val anyAppLabel = stringResource(R.string.rules_any_app)

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = MaterialTheme.shapes.extraLarge,
        confirmButton = {
            TextButton(onClick = onDismiss) { ButtonLabel(stringResource(R.string.common_cancel)) }
        },
        title = { Text(stringResource(R.string.rules_picker_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(stringResource(R.string.rules_picker_search)) },
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
                                    onPick(InstalledApp(AppRule.ANY_APP, anyAppLabel, null))
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
                                Icon(Icons.Rounded.Apps, contentDescription = null)
                            }
                            Column {
                                Text(anyAppLabel, style = MaterialTheme.typography.bodyLarge)
                                Caption(stringResource(R.string.rules_any_app_caption))
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

/**
 * The rule editor.
 *
 * [chatIsGroup] says whether the conversation this rule names is itself a group, which decides
 * whether the "also in groups" toggle means anything: a rule naming a group already fires for
 * everything said in it, so the toggle would be a control that does nothing.
 *
 * [existing] is every saved rule, needed because [AppRule.id] is built out of the package, the
 * trigger and the conversation — two of which this dialog can change. Saving is id-keyed, so an edit
 * that walks onto another rule's id replaces it, and only the full list can see that coming.
 */
@Composable
private fun RuleEditorDialog(
    rule: AppRule,
    existing: List<AppRule>,
    chatIsGroup: Boolean,
    onDismiss: () -> Unit,
    onSave: (AppRule) -> Unit,
    onTest: (AppRule) -> Unit,
    onAddPrivacy: (() -> Unit)?,
) {
    var r by remember { mutableStateOf(rule) }

    /*
     * Whether saving would land on a rule other than the one being edited.
     *
     * Compared against [rule] by value rather than by id: the id is precisely what is moving, so an
     * id test cannot tell "this is still me" from "this is somebody else". Anything in the list that
     * shares the destination id and is not the rule this dialog opened on is a rule about to be
     * overwritten — the trigger having been switched to one the app already has, a cleared chat id
     * colliding with a name-matched rule, or a blank rule opened for an app that already has one.
     */
    val replacesAnother = remember(r.id, rule, existing) {
        existing.any { it.id == r.id && it != rule }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = MaterialTheme.shapes.extraLarge,
        title = {
            Text(
                if (r.isConversationRule) {
                    stringResource(
                        R.string.rules_editor_title_chat,
                        ruleLabel(r),
                        r.conversationName.orEmpty(),
                    )
                } else {
                    ruleLabel(r)
                }
            )
        },
        confirmButton = {
            Button(onClick = { onSave(r) }) { ButtonLabel(stringResource(R.string.common_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { ButtonLabel(stringResource(R.string.common_cancel)) }
        },
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

                if (r.isConversationRule) {
                    // A per-chat rule is resolved from a posted notification, so "while open" has
                    // nothing to read a sender out of. Offering it here would only let the user
                    // build a rule that can never match.
                    Caption(stringResource(R.string.rules_per_chat_notifications_only))
                    ConversationMatchNote(
                        edited = r,
                        stored = rule,
                        onForgetKey = { r = r.copy(conversationKey = null) },
                    )
                } else {
                    // Both labels are read before the selector rather than inside its label lambda,
                    // which is a plain function and so cannot reach a resource itself.
                    val onNotification = stringResource(R.string.rules_trigger_notification)
                    val whileOpen = stringResource(R.string.rules_trigger_foreground)
                    SegmentedSelector(
                        options = listOf(Trigger.NOTIFICATION, Trigger.FOREGROUND),
                        selected = r.trigger,
                        label = { if (it == Trigger.NOTIFICATION) onNotification else whileOpen },
                        onSelect = { r = r.copy(trigger = it) },
                    )
                }

                PatternCarousel(
                    selected = r.pattern,
                    options = Pattern.entries.filter { it != Pattern.OFF && it != Pattern.CUSTOM },
                    onSelect = { r = r.copy(pattern = it) },
                )

                ToggleRow(
                    stringResource(R.string.rules_random_colour_each_time), r.randomColor,
                ) { r = r.copy(randomColor = it) }
                if (!r.randomColor) {
                    ColorPicker(r.color, { r = r.copy(color = it) })
                }

                if (r.trigger == Trigger.NOTIFICATION) {
                    if (r.isConversationRule) {
                        if (chatIsGroup) {
                            Caption(stringResource(R.string.rules_chat_is_group))
                        } else {
                            ToggleRow(
                                stringResource(R.string.rules_include_groups), r.includeGroups,
                            ) {
                                r = r.copy(includeGroups = it)
                            }
                            Caption(stringResource(R.string.rules_include_groups_hint))
                        }
                    }
                    OutlinedTextField(
                        value = r.keyword,
                        onValueChange = { r = r.copy(keyword = it) },
                        label = { Text(stringResource(R.string.rules_keyword_label)) },
                        singleLine = true,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    GatedDurationSlider(
                        label = stringResource(R.string.rules_show_for),
                        valueMs = r.durationMs,
                        minMs = 2_000,
                        safeMaxMs = Limits.WARN_ABOVE_MS,
                        extendedMaxMs = Limits.RULE_MAX_MS,
                        unlockLabel = stringResource(R.string.rules_allow_one_minute),
                        warnFirst = stringResource(R.string.rules_duration_warn_first_title) to
                            stringResource(R.string.rules_duration_warn_first_body),
                        warnSecond = stringResource(R.string.rules_duration_warn_second_title) to
                            stringResource(R.string.rules_duration_warn_second_body),
                        onChange = { r = r.copy(durationMs = it) },
                    )
                    ToggleRow(
                        stringResource(R.string.rules_only_screen_off), r.onlyWhenScreenOff,
                    ) {
                        r = r.copy(onlyWhenScreenOff = it)
                    }
                }
                if (r.pattern.usesSpeed) {
                    PixelSlider(
                        stringResource(R.string.rules_time_per_cycle),
                        r.speedMs.toFloat(),
                        150f..5000f,
                        { r = r.copy(speedMs = it.toInt()) },
                    ) { formatDuration(it.toInt()) }
                    r.pattern.cycleMeaningRes?.let { Caption(stringResource(it)) }
                }
                PixelSlider(
                    stringResource(R.string.rules_brightness), r.brightness, 0.05f..1f,
                    { r = r.copy(brightness = it) },
                ) { stringResource(R.string.common_percent, (it * 100).toInt()) }

                FilledTonalButton(onClick = { onTest(r) }, modifier = Modifier.fillMaxWidth()) {
                    ButtonLabel(stringResource(R.string.rules_test_on_leds))
                }

                if (onAddPrivacy != null) {
                    TextButton(onClick = onAddPrivacy, modifier = Modifier.fillMaxWidth()) {
                        ButtonLabel(stringResource(R.string.privacy_add_for_app))
                    }
                }

                // Last in the column, so it is the final thing read before Save. The save is not
                // blocked: replacing a rule is sometimes exactly what the user means, and there is
                // no way to keep both while they share an id. Only the silence was the problem.
                if (replacesAnother) {
                    Caption(stringResource(R.string.rules_replace_warning))
                }
            }
        },
    )
}

/**
 * What a per-chat rule matches on, and the way out of a chat id that has gone stale.
 *
 * A stored chat id is the better matcher — it survives the contact being renamed — but it is not
 * permanent. Reinstalling the app, restoring a backup, or the OS regenerating a dynamic shortcut all
 * hand the same chat a new id, and the matcher then refuses the notification outright: a key on both
 * sides that differs means a genuinely different chat, which is the right call everywhere except
 * here. The rule goes on looking correct and never fires again, so there has to be a way to say
 * "learn it afresh" without deleting the rule and rebuilding its colour from scratch.
 *
 * [edited] is the rule as this dialog currently has it and [stored] the rule as saved, which is how
 * a cleared key can be reported as pending rather than as a rule that never had one.
 */
@Composable
private fun ConversationMatchNote(edited: AppRule, stored: AppRule, onForgetKey: () -> Unit) {
    val hasKey = !edited.conversationKey.isNullOrBlank()
    val keyDropped = !hasKey && !stored.conversationKey.isNullOrBlank()

    Caption(
        when {
            hasKey -> stringResource(R.string.rules_match_by_id)
            keyDropped -> stringResource(R.string.rules_match_id_dropped)
            else -> stringResource(R.string.rules_match_by_name, edited.label)
        }
    )

    /*
     * The key may only be dropped when a usable name is left behind.
     *
     * ConversationMatch.isMatchable on its own is not enough of a guard: it answers true for a rule
     * with neither key nor name, because such a rule is no longer a conversation rule at all. Saving
     * that would silently widen a colour meant for one person into one for every notification the app
     * posts, which is a far worse outcome than a stale id. So the rule must still be about a chat
     * after the key goes, and that chat's name must still survive normalisation.
     */
    if (hasKey) {
        val withoutKey = edited.copy(conversationKey = null)
        if (withoutKey.isConversationRule && ConversationMatch.isMatchable(withoutKey)) {
            TextButton(onClick = onForgetKey) {
                ButtonLabel(stringResource(R.string.rules_relearn_chat))
            }
            Caption(stringResource(R.string.rules_relearn_hint))
        }
    }
}
