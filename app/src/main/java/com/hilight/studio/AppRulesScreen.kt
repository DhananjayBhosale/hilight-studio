package com.hilight.studio

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.automirrored.rounded.Launch
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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

private data class InstalledApp(val pkg: String, val label: String)
private data class EditorRequest(val rule: AppRule, val textRequired: Boolean = false)
private data class ChatPickRequest(val app: InstalledApp, val template: AppRule? = null)

/** One grouped card per app, with compact child rows for every rule inside it. */
@Composable
fun AppRulesScreen(store: Store, snackbarHostState: SnackbarHostState) {
    val rules by store.rules.collectAsStateWithLifecycle()
    val conversations by store.conversations.collectAsStateWithLifecycle()
    val lastMatch by store.lastMatch.collectAsStateWithLifecycle()
    var pickingApp by remember { mutableStateOf(false) }
    var scoping by remember { mutableStateOf<InstalledApp?>(null) }
    var pickingChat by remember { mutableStateOf<ChatPickRequest?>(null) }
    var editing by remember { mutableStateOf<EditorRequest?>(null) }
    var collapsed by remember { mutableStateOf(emptySet<String>()) }
    val scope = rememberCoroutineScope()
    val deletedMessage = stringResource(R.string.rules_deleted)
    val undoLabel = stringResource(R.string.rules_undo)

    val addRuleFor: (InstalledApp) -> Unit = { app ->
        if (app.pkg == AppRule.ANY_APP) {
            val existing = rules.firstOrNull {
                it.isCatchAll && it.trigger == Trigger.NOTIFICATION &&
                    !it.isConversationRule && it.keyword.isBlank()
            }
            editing = EditorRequest(existing ?: AppRule(app.pkg, app.label))
        } else {
            scoping = app
        }
    }

    PixelCard(tone = 2) {
        SectionTitle(stringResource(R.string.rules_section_title))
        Caption(stringResource(R.string.rules_intro_apps))
        Caption(stringResource(R.string.rules_intro_messaging))
        Button(onClick = { pickingApp = true }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Rounded.Add, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            ButtonLabel(stringResource(R.string.rules_add_app))
        }
    }

    val groups = remember(rules) { rules.groupBy { it.pkg }.values.toList() }
    groups.forEach { group ->
        val pkg = group.first().pkg
        RuleGroupCard(
            rules = group,
            conversations = conversations,
            lastMatch = lastMatch,
            expanded = pkg !in collapsed,
            onToggleExpanded = {
                collapsed = if (pkg in collapsed) collapsed - pkg else collapsed + pkg
            },
            onAddRule = addRuleFor,
            onToggle = { rule, enabled -> store.upsertRule(rule.copy(enabled = enabled)) },
            onEdit = { rule ->
                editing = EditorRequest(
                    rule,
                    textRequired = rule.trigger == Trigger.NOTIFICATION &&
                        !rule.isConversationRule && rule.keyword.isNotBlank(),
                )
            },
            onTest = { rule ->
                store.preview(
                    rule.pattern, rule.color, rule.speedMs, rule.brightness, rule.durationMs,
                )
            },
            onDuplicate = { rule ->
                val copy = rule.copyAsNew()
                if (rule.isConversationRule) {
                    pickingChat = ChatPickRequest(
                        app = InstalledApp(rule.pkg, rule.label),
                        template = copy.copy(
                            conversationKey = null,
                            conversationName = null,
                            conversationIsGroup = false,
                        ),
                    )
                } else {
                    editing = EditorRequest(copy, textRequired = rule.keyword.isNotBlank())
                }
            },
            onMoveText = store::moveTextRule,
            onDelete = { rule ->
                val index = rules.indexOfFirst { it.id == rule.id }
                store.removeRule(rule)
                scope.launch {
                    if (
                        snackbarHostState.showSnackbar(
                            message = deletedMessage,
                            actionLabel = undoLabel,
                            withDismissAction = true,
                        ) == SnackbarResult.ActionPerformed && index >= 0
                    ) {
                        store.restoreRule(rule, index)
                    }
                }
            },
        )
    }

    if (pickingApp) {
        AppPickerDialog(
            onDismiss = { pickingApp = false },
            onPick = { app ->
                pickingApp = false
                addRuleFor(app)
            },
        )
    }

    scoping?.let { app ->
        RuleScopeDialog(
            appLabel = app.label,
            allowChat = offersConversations(store, app),
            allowText = app.pkg != AppRule.ANY_APP,
            allowForeground = app.pkg != AppRule.ANY_APP,
            onDismiss = { scoping = null },
            onPick = { scope ->
                scoping = null
                when (scope) {
                    RuleScope.WHOLE_APP -> {
                        val existing = rules.firstOrNull {
                            it.pkg == app.pkg && it.trigger == Trigger.NOTIFICATION &&
                                !it.isConversationRule && it.keyword.isBlank()
                        }
                        editing = EditorRequest(existing ?: AppRule(app.pkg, app.label))
                    }
                    RuleScope.ONE_CHAT -> pickingChat = ChatPickRequest(app)
                    RuleScope.TEXT -> editing = EditorRequest(
                        AppRule(pkg = app.pkg, label = app.label),
                        textRequired = true,
                    )
                    RuleScope.FOREGROUND -> {
                        val existing = rules.firstOrNull {
                            it.pkg == app.pkg && it.trigger == Trigger.FOREGROUND &&
                                !it.isConversationRule
                        }
                        editing = EditorRequest(
                            existing ?: AppRule(
                                pkg = app.pkg,
                                label = app.label,
                                trigger = Trigger.FOREGROUND,
                            )
                        )
                    }
                }
            },
        )
    }

    pickingChat?.let { request ->
        ConversationPickerDialog(
            store = store,
            pkg = request.app.pkg,
            appLabel = request.app.label,
            onDismiss = { pickingChat = null },
            onPicked = { ref ->
                pickingChat = null
                val base = request.template ?: AppRule(
                    pkg = request.app.pkg,
                    label = request.app.label,
                )
                val fresh = base.copy(
                    trigger = Trigger.NOTIFICATION,
                    conversationKey = ref.key,
                    conversationName = ref.name,
                    conversationIsGroup = ref.isGroup,
                )
                val existing = rules.firstOrNull {
                    it.id != fresh.id && ConversationMatch.sameTarget(it, fresh)
                }
                editing = EditorRequest(existing ?: fresh)
            },
        )
    }

    editing?.let { request ->
        val recentPeeks by store.recentPeeks.collectAsStateWithLifecycle()
        val rule = request.rule
        RuleEditorDialog(
            rule = rule,
            existing = rules,
            textRequired = request.textRequired,
            recentPeeks = recentPeeks,
            chatIsGroup = rule.conversationIsGroup ||
                knownConversation(rule, conversations)?.isGroup == true,
            onDismiss = { editing = null },
            onSave = { saved ->
                val isNew = rules.none { it.id == rule.id }
                store.upsertRule(saved.copy(keyword = saved.keyword.trim()), replacing = rule)
                if (isNew) collapsed = collapsed - saved.pkg
                editing = null
            },
            onTest = { store.preview(it.pattern, it.color, it.speedMs, it.brightness, it.durationMs) },
        )
    }
}

/** Whether this app should offer the existing contact/chat picker. */
private fun offersConversations(store: Store, app: InstalledApp): Boolean =
    app.pkg != AppRule.ANY_APP &&
        (store.conversationsFor(app.pkg).isNotEmpty() || MessagingApps.looksLikeMessaging(app.pkg))

@Composable
private fun RuleGroupCard(
    rules: List<AppRule>,
    conversations: List<ConversationRef>,
    lastMatch: Map<String, Long>,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onAddRule: (InstalledApp) -> Unit,
    onToggle: (AppRule, Boolean) -> Unit,
    onEdit: (AppRule) -> Unit,
    onTest: (AppRule) -> Unit,
    onDuplicate: (AppRule) -> Unit,
    onMoveText: (AppRule, Int) -> Unit,
    onDelete: (AppRule) -> Unit,
) {
    val first = rules.first()
    val appLabel = ruleLabel(first)
    val app = InstalledApp(first.pkg, first.label)
    val notificationRules = rules.filter { it.trigger == Trigger.NOTIFICATION }
    val chats = notificationRules
        .filter { it.isConversationRule }
        .sortedWith(
            compareBy<AppRule>(
                { it.conversationName?.lowercase().orEmpty() },
                { it.keyword.isBlank() },
            )
        )
    val textRules = notificationRules.filter { !it.isConversationRule && it.keyword.isNotBlank() }
    val fallback = notificationRules.firstOrNull { !it.isConversationRule && it.keyword.isBlank() }
    val foreground = rules.firstOrNull { it.trigger == Trigger.FOREGROUND && !it.isConversationRule }
    val hasSpecificNotificationRules = chats.isNotEmpty() || textRules.isNotEmpty()
    val enabledCount = rules.count { it.enabled }

    PixelCard(tone = 1) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            AppIcon(app)
            Column(
                Modifier
                    .weight(1f)
                    .clickable(onClick = onToggleExpanded),
            ) {
                Text(
                    appLabel,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Caption(
                    pluralStringResource(
                        R.plurals.rules_group_status,
                        rules.size,
                        rules.size,
                        enabledCount,
                    )
                )
            }
            TextButton(onClick = { onAddRule(InstalledApp(first.pkg, appLabel)) }) {
                Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                ButtonLabel(stringResource(R.string.rules_add_short))
            }
            IconButton(onClick = onToggleExpanded) {
                Icon(
                    if (expanded) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown,
                    contentDescription = stringResource(
                        if (expanded) R.string.rules_collapse else R.string.rules_expand,
                    ),
                )
            }
        }

        AnimatedVisibility(visible = expanded, enter = fadeIn(), exit = fadeOut()) {
            Column {
                var shown = false
                chats.forEach { rule ->
                    if (shown) RuleDivider()
                    CompactRuleRow(
                        rule = rule,
                        title = conversationRuleTitle(rule),
                        icon = Icons.Rounded.Person,
                        chat = knownConversation(rule, conversations),
                        lastMatchedMs = lastMatch[rule.id],
                        onToggle = { onToggle(rule, it) },
                        onEdit = { onEdit(rule) },
                        onTest = { onTest(rule) },
                        onDuplicate = { onDuplicate(rule) },
                        onDelete = { onDelete(rule) },
                    )
                    shown = true
                }

                if (textRules.isNotEmpty()) {
                    if (shown) RuleDivider()
                    if (textRules.size > 1) {
                        Caption(stringResource(R.string.rules_text_priority_hint))
                    }
                    textRules.forEachIndexed { index, rule ->
                        if (index > 0) RuleDivider()
                        CompactRuleRow(
                            rule = rule,
                            title = stringResource(R.string.rules_match_contains, rule.keyword.trim()),
                            icon = Icons.Rounded.TextFields,
                            lastMatchedMs = lastMatch[rule.id],
                            canMoveEarlier = index > 0,
                            canMoveLater = index < textRules.lastIndex,
                            onToggle = { onToggle(rule, it) },
                            onEdit = { onEdit(rule) },
                            onTest = { onTest(rule) },
                            onDuplicate = { onDuplicate(rule) },
                            onMoveEarlier = { onMoveText(rule, -1) },
                            onMoveLater = { onMoveText(rule, 1) },
                            onDelete = { onDelete(rule) },
                        )
                    }
                    shown = true
                }

                fallback?.let { rule ->
                    if (shown) FallbackDivider()
                    CompactRuleRow(
                        rule = rule,
                        title = when {
                            rule.isCatchAll -> stringResource(R.string.rules_match_any_app)
                            hasSpecificNotificationRules -> stringResource(R.string.rules_default)
                            else -> stringResource(R.string.rules_match_all_notifications)
                        },
                        icon = Icons.Rounded.Notifications,
                        lastMatchedMs = lastMatch[rule.id],
                        onToggle = { onToggle(rule, it) },
                        onEdit = { onEdit(rule) },
                        onTest = { onTest(rule) },
                        onDelete = { onDelete(rule) },
                    )
                    shown = true
                }

                foreground?.let { rule ->
                    if (shown) RuleDivider()
                    CompactRuleRow(
                        rule = rule,
                        title = stringResource(R.string.rules_match_while_open, appLabel),
                        icon = Icons.AutoMirrored.Rounded.Launch,
                        lastMatchedMs = null,
                        onToggle = { onToggle(rule, it) },
                        onEdit = { onEdit(rule) },
                        onTest = { onTest(rule) },
                        onDelete = { onDelete(rule) },
                    )
                }
            }
        }
    }
}

@Composable
private fun conversationRuleTitle(rule: AppRule): String {
    val name = rule.conversationName ?: ruleLabel(rule)
    return if (rule.keyword.isBlank()) name
    else stringResource(R.string.rules_match_contact_text, name, rule.keyword.trim())
}

@Composable
private fun RuleDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(vertical = 2.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
    )
}

@Composable
private fun FallbackDivider() {
    Row(
        Modifier.padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        HorizontalDivider(Modifier.weight(1f))
        Text(
            stringResource(R.string.rules_all_other_notifications),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        HorizontalDivider(Modifier.weight(1f))
    }
}

@Composable
private fun CompactRuleRow(
    rule: AppRule,
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    chat: ConversationRef? = null,
    lastMatchedMs: Long?,
    canMoveEarlier: Boolean = false,
    canMoveLater: Boolean = false,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onTest: () -> Unit,
    onDuplicate: (() -> Unit)? = null,
    onMoveEarlier: (() -> Unit)? = null,
    onMoveLater: (() -> Unit)? = null,
    onDelete: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    var menuOpen by remember { mutableStateOf(false) }
    val dim = if (rule.enabled) 1f else 0.58f

    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(20.dp).alpha(dim),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                Modifier
                    .weight(1f)
                    .alpha(dim)
                    .clip(MaterialTheme.shapes.small)
                    .clickable(onClick = onEdit)
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                when {
                    chat?.isGroup == true || rule.conversationIsGroup ->
                        ConversationBadge(stringResource(R.string.chat_badge_group))
                    rule.includeGroups ->
                        ConversationBadge(stringResource(R.string.rules_badge_groups_too))
                }
            }
            Switch(
                checked = rule.enabled,
                onCheckedChange = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onToggle(it)
                },
            )
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.rules_more))
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.common_edit)) },
                        onClick = {
                            menuOpen = false
                            onEdit()
                        },
                    )
                    onDuplicate?.let { duplicate ->
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.rules_duplicate)) },
                            onClick = {
                                menuOpen = false
                                duplicate()
                            },
                        )
                    }
                    if (canMoveEarlier && onMoveEarlier != null) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.rules_move_earlier)) },
                            leadingIcon = { Icon(Icons.Rounded.ArrowUpward, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                onMoveEarlier()
                            },
                        )
                    }
                    if (canMoveLater && onMoveLater != null) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.rules_move_later)) },
                            leadingIcon = { Icon(Icons.Rounded.ArrowDownward, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                onMoveLater()
                            },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.common_delete)) },
                        onClick = {
                            menuOpen = false
                            onDelete()
                        },
                    )
                }
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .alpha(dim),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            MiniRulePreview(rule = rule, onTest = onTest)
            Column(
                Modifier
                    .weight(1f)
                    .clickable(onClick = onEdit),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                val pattern = stringResource(rule.pattern.labelRes)
                Caption(
                    if (rule.randomColor) {
                        stringResource(
                            R.string.rules_visual_summary_random,
                            pattern,
                            formatDuration(rule.durationMs),
                            (rule.brightness * 100).toInt(),
                        )
                    } else {
                        stringResource(
                            R.string.rules_visual_summary,
                            pattern,
                            formatDuration(rule.durationMs),
                            (rule.brightness * 100).toInt(),
                        )
                    }
                )
                if (rule.trigger == Trigger.NOTIFICATION) {
                    Caption(
                        if (lastMatchedMs != null) {
                            stringResource(R.string.rules_last_matched, relativeAgo(lastMatchedMs))
                        } else {
                            stringResource(R.string.rules_not_matched_yet)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun MiniRulePreview(rule: AppRule, onTest: () -> Unit) {
    Row(
        Modifier
            .width(124.dp)
            .heightIn(min = 48.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .clickable(onClick = onTest)
            .padding(horizontal = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        LedStrip(
            rule.pattern,
            Ambient(
                pattern = rule.pattern,
                color = rule.color,
                speedMs = rule.speedMs,
                brightness = rule.brightness,
            ),
            modifier = Modifier.width(88.dp),
            active = true,
            animate = rule.enabled,
            heightDp = 24,
        )
        Icon(
            Icons.Rounded.PlayArrow,
            contentDescription = stringResource(R.string.rules_test_preview),
            modifier = Modifier.size(17.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
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
                    InstalledApp(ai.packageName, pm.getApplicationLabel(ai).toString())
                }
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
                                    onPick(InstalledApp(AppRule.ANY_APP, anyAppLabel))
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
    if (app.pkg == AppRule.ANY_APP) {
        Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Apps, contentDescription = null)
        }
        return
    }
    val ctx = LocalContext.current
    val bmp by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, app.pkg) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                ctx.packageManager.getApplicationIcon(app.pkg).toBitmap(80, 80).asImageBitmap()
            }.getOrNull()
        }
    }
    Box(Modifier.size(32.dp)) {
        bmp?.let { Image(it, contentDescription = null, modifier = Modifier.size(32.dp)) }
    }
}

/** The existing rule editor, with matching scope fixed by the Add Rule flow above it. */
@Composable
private fun RuleEditorDialog(
    rule: AppRule,
    existing: List<AppRule>,
    textRequired: Boolean,
    recentPeeks: List<MessageInfo>,
    chatIsGroup: Boolean,
    onDismiss: () -> Unit,
    onSave: (AppRule) -> Unit,
    onTest: (AppRule) -> Unit,
) {
    var r by remember { mutableStateOf(rule) }
    val cleaned = r.copy(keyword = r.keyword.trim())
    val duplicateTarget = existing.any {
        it.id != rule.id && ConversationMatch.sameTarget(it, cleaned)
    }
    val keywordMissing = textRequired && r.keyword.isBlank()
    val canSave = !keywordMissing && !duplicateTarget

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = MaterialTheme.shapes.extraLarge,
        title = {
            Text(
                when {
                    r.isConversationRule -> stringResource(
                        R.string.rules_editor_title_chat,
                        ruleLabel(r),
                        r.conversationName.orEmpty(),
                    )
                    textRequired -> stringResource(R.string.rules_editor_title_text, ruleLabel(r))
                    else -> ruleLabel(r)
                }
            )
        },
        confirmButton = {
            Button(onClick = { onSave(cleaned) }, enabled = canSave) {
                ButtonLabel(stringResource(R.string.common_save))
            }
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

                Caption(stringResource(R.string.rules_match_section))
                when {
                    r.isConversationRule -> {
                        Text(
                            r.conversationName.orEmpty(),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        ConversationMatchNote(
                            edited = r,
                            stored = rule,
                            onForgetKey = { r = r.copy(conversationKey = null) },
                        )
                        OutlinedTextField(
                            value = r.keyword,
                            onValueChange = { r = r.copy(keyword = it) },
                            label = { Text(stringResource(R.string.rules_keyword_label)) },
                            singleLine = true,
                            shape = MaterialTheme.shapes.medium,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    textRequired -> {
                        OutlinedTextField(
                            value = r.keyword,
                            onValueChange = { r = r.copy(keyword = it) },
                            label = { Text(stringResource(R.string.rules_text_condition_label)) },
                            supportingText = {
                                Text(stringResource(R.string.rules_text_condition_hint))
                            },
                            isError = keywordMissing,
                            singleLine = true,
                            shape = MaterialTheme.shapes.medium,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if (r.keyword.isNotBlank()) {
                            val appPeeks = recentPeeks.filter {
                                it.pkg == r.pkg && !it.isGroupSummary && !it.isOngoing
                            }
                            val searchable = appPeeks.filter { info ->
                                listOf(info.title, info.text, info.sender, info.conversationTitle)
                                    .any { !it.isNullOrBlank() }
                            }
                            val matches = searchable.count {
                                ConversationMatch.matchesText(it, r.keyword)
                            }
                            Caption(
                                when {
                                    appPeeks.isEmpty() ->
                                        stringResource(R.string.rules_text_check_no_recent)
                                    searchable.isEmpty() ->
                                        stringResource(R.string.rules_text_check_private)
                                    matches > 0 ->
                                        pluralStringResource(R.plurals.rules_text_check_matches, matches, matches)
                                    else -> stringResource(R.string.rules_text_check_none)
                                }
                            )
                        }
                    }
                    r.trigger == Trigger.FOREGROUND ->
                        Caption(stringResource(R.string.rules_editor_foreground_match, ruleLabel(r)))
                    r.isCatchAll -> Caption(stringResource(R.string.rules_editor_any_app_match))
                    else ->
                        Caption(stringResource(R.string.rules_editor_all_notifications_match, ruleLabel(r)))
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
                            ) { r = r.copy(includeGroups = it) }
                            Caption(stringResource(R.string.rules_include_groups_hint))
                        }
                    }
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
                    ) { r = r.copy(onlyWhenScreenOff = it) }
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

                if (duplicateTarget) {
                    Caption(stringResource(R.string.rules_duplicate_warning))
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
