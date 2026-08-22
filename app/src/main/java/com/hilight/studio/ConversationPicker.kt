package com.hilight.studio

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Contacts
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Launch
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Choosing the one chat a per-conversation rule is about — "green when Sujay messages on WhatsApp".
 *
 * There are three ways in, and the order they appear in is the whole point. The list of chats HiLight
 * has already seen comes first because those strings are the ones the matcher will later compare
 * against, so picking from it cannot be spelled wrong. Learning the next message is the same guarantee
 * arrived at from the other direction, for a chat that has not come in yet. The system contact picker
 * is last: it is the only path where the name can differ from what the messaging app puts in its
 * notifications, which is exactly how a per-contact rule ends up never firing.
 */

/** What a new app rule should react to, chosen before the editor opens. */
enum class RuleScope { WHOLE_APP, ONE_CHAT, TEXT, FOREGROUND }

/**
 * Apps worth offering a per-chat rule for before any message from them has been seen.
 *
 * This is only a hint for the first run. Once a message has arrived the learned list answers the same
 * question far better, so an app missing from here loses nothing permanently — it simply does not get
 * the extra step until HiLight has watched it notify once. The list exists so that a brand new install
 * can still offer "one contact or chat" for WhatsApp, which is what most people came for.
 */
object MessagingApps {

    private val KNOWN = setOf(
        "com.whatsapp",
        "com.whatsapp.w4b",
        "org.telegram.messenger",
        "org.telegram.messenger.web",
        "org.thunderdog.challegram",            // Telegram X
        "org.thoughtcrime.securesms",           // Signal
        "com.google.android.apps.messaging",    // Google Messages
        "com.google.android.gm",                // Gmail: senders read as conversations too
        "com.facebook.orca",                    // Messenger
        "com.instagram.android",
        "com.discord",
        "com.Slack",
        "com.microsoft.teams",
        "com.snapchat.android",
        "com.viber.voip",
        "jp.naver.line.android",
        "com.tencent.mm",                       // WeChat
        "com.skype.raider",
        "im.vector.app",                        // Element
    )

    fun looksLikeMessaging(pkg: String): Boolean = pkg in KNOWN
}

/**
 * The chat a rule already names, as HiLight last saw it, or null if it has never been seen.
 *
 * Used to decide whether a rule points at a group, which the rule itself does not record: the stored
 * fields are only what the matcher needs. Matching goes by stable id first and normalised name second,
 * the same ladder [ConversationMatch] uses, so a renamed contact still resolves.
 */
fun knownConversation(rule: AppRule, known: List<ConversationRef>): ConversationRef? {
    if (!rule.isConversationRule) return null
    val byKey = rule.conversationKey?.takeIf { it.isNotBlank() }?.let { key ->
        known.firstOrNull { it.pkg == rule.pkg && it.key == key }
    }
    if (byKey != null) return byKey
    val want = ConversationMatch.normalise(rule.conversationName)
    if (want.isEmpty()) return null
    return known.firstOrNull { it.pkg == rule.pkg && ConversationMatch.normalise(it.name) == want }
}

/**
 * Wall-clock stamp as "5 min ago".
 *
 * Deliberately coarse. The only question these lines answer is whether a rule is alive or quietly
 * broken, and to the minute is plenty for that — while an exact timestamp invites the reader to work
 * out arithmetic they do not care about.
 *
 * Composable because the wording is now resources, and the counted forms are plurals: English
 * inflects the unit where Japanese does not, and no amount of concatenation gets both right. Both
 * call sites are already composables, so this costs them nothing.
 *
 * The counts are worked out before the branch that uses them, which is safe because each is only
 * read where the branch above it has already bounded it — and a missing stamp is caught first, so
 * the nonsense difference it would produce is never measured.
 */
@Composable
fun relativeAgo(stampMs: Long, nowMs: Long = System.currentTimeMillis()): String {
    val diff = nowMs - stampMs
    val minutes = (diff / 60_000L).toInt()
    val hours = (diff / 3_600_000L).toInt()
    val days = (diff / 86_400_000L).toInt()
    return when {
        stampMs <= 0L -> stringResource(R.string.chat_ago_unknown)
        // also covers a clock that has moved backwards
        diff < 60_000L -> stringResource(R.string.chat_ago_just_now)
        diff < 3_600_000L -> pluralStringResource(R.plurals.chat_ago_minutes, minutes, minutes)
        hours < 24 -> pluralStringResource(R.plurals.chat_ago_hours, hours, hours)
        // One day is a word rather than a count, and not every language can express that as the
        // "one" form of a plural, so it stays a branch here and a string of its own.
        days == 1 -> stringResource(R.string.chat_ago_yesterday)
        days < 7 -> pluralStringResource(R.plurals.chat_ago_days, days, days)
        else -> stringResource(R.string.chat_ago_over_a_week)
    }
}

/**
 * Small tonal pill, for marking a chat as a group.
 *
 * Same recipe as the value badge on [PixelSlider] — secondary container, circle, label type — so it
 * reads as part of the same family rather than as a new kind of chip.
 */
@Composable
fun ConversationBadge(text: String) {
    Box(
        Modifier
            .background(MaterialTheme.colorScheme.secondaryContainer, CircleShape)
            .padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

/**
 * The step between picking an app and the editor. Conversation and text choices are hidden where
 * they cannot sensibly apply, but the shape stays the same whether this came from "Add app" or the
 * + Rule button inside an existing app group.
 */
@Composable
fun RuleScopeDialog(
    appLabel: String,
    allowChat: Boolean,
    allowText: Boolean,
    allowForeground: Boolean,
    onDismiss: () -> Unit,
    onPick: (RuleScope) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = MaterialTheme.shapes.extraLarge,
        confirmButton = {
            TextButton(onClick = onDismiss) { ButtonLabel(stringResource(R.string.common_cancel)) }
        },
        title = { Text(stringResource(R.string.chat_scope_title, appLabel)) },
        text = {
            Column {
                ScopeRow(
                    icon = Icons.Rounded.Notifications,
                    title = stringResource(R.string.chat_scope_whole_app),
                    subtitle = stringResource(R.string.chat_scope_whole_app_hint),
                    onClick = { onPick(RuleScope.WHOLE_APP) },
                )
                if (allowChat) {
                    ScopeRow(
                        icon = Icons.Rounded.Person,
                        title = stringResource(R.string.chat_scope_one_chat),
                        subtitle = stringResource(R.string.chat_scope_one_chat_hint),
                        onClick = { onPick(RuleScope.ONE_CHAT) },
                    )
                }
                if (allowText) {
                    ScopeRow(
                        icon = Icons.Rounded.TextFields,
                        title = stringResource(R.string.rules_scope_text),
                        subtitle = stringResource(R.string.rules_scope_text_hint),
                        onClick = { onPick(RuleScope.TEXT) },
                    )
                }
                if (allowForeground) {
                    ScopeRow(
                        icon = Icons.Rounded.Launch,
                        title = stringResource(R.string.rules_scope_foreground, appLabel),
                        subtitle = stringResource(R.string.rules_scope_foreground_hint),
                        onClick = { onPick(RuleScope.FOREGROUND) },
                    )
                }
            }
        },
    )
}

@Composable
private fun ScopeRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null)
        }
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Caption(subtitle)
        }
    }
}

/**
 * Picks the chat for a per-conversation rule, by list, by contact, or by waiting for a message.
 *
 * [onPicked] hands back a [ConversationRef] rather than a name so that the caller can keep the stable
 * chat id where there is one; a rule built with that id survives the contact being renamed.
 */
@Composable
fun ConversationPickerDialog(
    store: Store,
    pkg: String,
    appLabel: String,
    onDismiss: () -> Unit,
    onPicked: (ConversationRef) -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val allConversations by store.conversations.collectAsStateWithLifecycle()
    val learnTarget by store.learnTarget.collectAsStateWithLifecycle()
    val learned by store.learned.collectAsStateWithLifecycle()

    // conversationsFor() is the accessor that knows the ordering, but the flow is what makes this
    // list live: a chat learned while the dialog is open should appear without reopening it.
    val chats = remember(allConversations, pkg) { store.conversationsFor(pkg) }

    // Both of these are single global slots in the store, so anything they hold for another app
    // belongs to some other screen and must not be shown here.
    val captured = learned?.takeIf { it.pkg == pkg }
    val waiting = learnTarget == pkg && captured == null

    // The card has to go on rendering the capture while it animates away, and the flow behind it has
    // already gone empty by then — consuming a capture clears it — so the last one is held locally.
    var lastCaptured by remember { mutableStateOf<ConversationRef?>(null) }
    var wasCapture by remember { mutableStateOf(false) }
    LaunchedEffect(captured, waiting) {
        if (captured != null) {
            lastCaptured = captured
            wasCapture = true
        } else if (waiting) {
            wasCapture = false
        }
    }
    val showCapture = captured != null || (wasCapture && !waiting)

    var contactFailed by remember { mutableStateOf(false) }
    var unusable by remember { mutableStateOf(false) }

    /*
     * Learn mode is a wait on the notification listener, so it can only ever finish while HiLight
     * holds notification access — and that access is not a given even after the user has granted it
     * once, because Android's unused-app auto-reset takes it back on its own. Claiming to be
     * listening in that state leaves the user waiting for something that cannot arrive, so the state
     * is read here and re-read on every resume: granting access from the button below leaves and
     * re-enters this screen, which is exactly when the warning should clear.
     */
    var notifAccess by remember { mutableStateOf(hasListenerAccess(ctx)) }
    LifecycleResumeEffect(Unit) {
        notifAccess = hasListenerAccess(ctx)
        onPauseOrDispose { }
    }

    // Every way into learn mode goes through here, so the access check cannot be forgotten at one of
    // them. It reads the setting again rather than trusting the value from the last resume, because
    // access can be withdrawn while this dialog sits open, and clearing the capture card on a refused
    // arm is what lets the warning below be seen at all.
    val armLearning: () -> Unit = {
        notifAccess = hasListenerAccess(ctx)
        if (notifAccess) {
            store.startLearning(pkg)
        } else {
            wasCapture = false
        }
    }

    /**
     * The single way a chat leaves this dialog.
     *
     * A chat can be perfectly readable and still be impossible to write a rule about: a name that is
     * only emoji or punctuation normalises to nothing, and with no stable chat id beside it there is
     * nothing left for the matcher to compare. Guarding here rather than at each of the three paths is
     * what stops the learned list and the captured chat from quietly creating a rule that can never
     * fire — the contact picker used to be the only path that checked.
     */
    fun accept(ref: ConversationRef) {
        val asRule = AppRule(
            pkg = pkg,
            label = pkg,
            conversationKey = ref.key,
            conversationName = ref.name,
        )
        if (ConversationMatch.isMatchable(asRule)) {
            unusable = false
            onPicked(ref)
        } else {
            unusable = true
        }
    }

    /*
     * PickContact hands back a one-shot read grant on the single contact the user tapped, which is
     * why this path needs no READ_CONTACTS permission and no manifest entry: HiLight never gains the
     * ability to read the address book, only the row it was handed. Asking for the permission would
     * be both unnecessary and a much larger thing to ask for.
     */
    val pickContact = rememberLauncherForActivityResult(ActivityResultContracts.PickContact()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        contactFailed = false
        scope.launch {
            // a content provider query is disk and IPC, so it never runs on the main thread
            val name = withContext(Dispatchers.IO) { contactDisplayName(ctx, uri) }
            // A name that survives neither the read nor normalisation is no name at all: a contact
            // saved as nothing but an emoji normalises to an empty string, and a rule built on that
            // could never match anything. Better to say so here than to save a rule that stays dark.
            val ref = name?.let { ConversationRef(pkg = pkg, name = it) }
            if (ref == null || !ConversationMatch.isMatchable(
                    AppRule(pkg = pkg, label = pkg, conversationName = ref.name)
                )
            ) {
                contactFailed = true
            } else {
                accept(ref)
            }
        }
    }

    DisposableEffect(pkg) {
        onDispose {
            // Learning is a background wait on the notification listener. Leaving it armed after this
            // dialog closes would capture a chat nobody is looking at any more, so it stands down
            // with the dialog however the dialog was left.
            store.stopLearning()
            store.clearLearned()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = MaterialTheme.shapes.extraLarge,
        confirmButton = {
            TextButton(onClick = onDismiss) { ButtonLabel(stringResource(R.string.common_cancel)) }
        },
        title = { Text(stringResource(R.string.chat_picker_title)) },
        text = {
            // One LazyColumn for everything: it is the only scroller here, which keeps the sections
            // and the list of chats out of the nested-scrolling trap.
            LazyColumn(
                Modifier.heightIn(max = 460.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Caption(stringResource(R.string.chat_picker_intro, appLabel))
                }

                item {
                    AnimatedVisibility(
                        // The missing-access warning shares this slot, and it shows without anything
                        // having been armed: no rule of any kind fires without notification access,
                        // so a picker that quietly offers to build one is misleading from the start.
                        visible = captured != null || waiting || !notifAccess,
                        enter = fadeIn(tween(200)) +
                            slideInVertically(spring(dampingRatio = Spring.DampingRatioLowBouncy)) { it / 4 } +
                            scaleIn(tween(220), initialScale = 0.97f),
                        exit = fadeOut(tween(140)),
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .background(
                                    MaterialTheme.colorScheme.surfaceContainerHighest,
                                    MaterialTheme.shapes.medium,
                                )
                                .padding(14.dp)
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                // A capture leads even when access has since gone: it was read from
                                // a real notification, so it is still the right chat to offer.
                                if (showCapture) {
                                    CapturedBlock(
                                        ref = lastCaptured,
                                        onUse = {
                                            val ref = lastCaptured
                                            store.clearLearned()
                                            if (ref != null) accept(ref)
                                        },
                                        onAgain = {
                                            store.clearLearned()
                                            armLearning()
                                        },
                                    )
                                } else if (!notifAccess) {
                                    NoAccessBlock(
                                        onOpenSettings = {
                                            ctx.startActivity(
                                                Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                                            )
                                        },
                                        // An armed wait is left armed on purpose. It costs nothing
                                        // while access is missing, and granting access makes it work
                                        // straight away rather than asking for another tap — so the
                                        // only reason to stand it down is that the user says so.
                                        onStop = if (waiting) ({ store.stopLearning() }) else null,
                                    )
                                } else {
                                    WaitingBlock(
                                        appLabel = appLabel,
                                        onStop = { store.stopLearning() },
                                    )
                                }
                            }
                        }
                    }
                }

                // With nothing learned yet the list would be an empty space where the main path
                // should be, so the two paths that work from cold lead instead.
                if (chats.isEmpty()) {
                    item {
                        PickerActions(
                            waiting = waiting,
                            onLearn = armLearning,
                            onContacts = { pickContact.launch(null) },
                        )
                    }
                    item {
                        Caption(stringResource(R.string.chat_no_chats_yet, appLabel))
                    }
                } else {
                    item { Caption(stringResource(R.string.chat_seen_list_header)) }
                    // No item keys: two chats can share a name with no id between them, and a
                    // duplicate key is a crash rather than a cosmetic problem.
                    items(chats) { chat ->
                        ConversationRow(chat) { accept(chat) }
                    }
                    item {
                        PickerActions(
                            waiting = waiting,
                            onLearn = armLearning,
                            onContacts = { pickContact.launch(null) },
                        )
                    }
                }

                if (contactFailed) {
                    item {
                        Caption(stringResource(R.string.chat_contact_no_name))
                    }
                }

                if (unusable) {
                    item {
                        Caption(stringResource(R.string.chat_unusable))
                    }
                }
            }
        },
    )
}

/** The two paths that do not need the learned list: wait for a message, or pick a contact. */
@Composable
private fun PickerActions(waiting: Boolean, onLearn: () -> Unit, onContacts: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onLearn, enabled = !waiting, modifier = Modifier.fillMaxWidth()) {
            ButtonLabel(stringResource(R.string.chat_learn_next))
        }
        Caption(stringResource(R.string.chat_learn_next_hint))
        FilledTonalButton(onClick = onContacts, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Rounded.Contacts, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            ButtonLabel(stringResource(R.string.chat_pick_contact))
        }
        Caption(stringResource(R.string.chat_pick_contact_hint))
    }
}

/**
 * Shown in place of [WaitingBlock] when HiLight does not hold notification access.
 *
 * The pill is deliberately the not-ok one. "Listening" over a listener the system has switched off is
 * the single most misleading thing this dialog could say: the user would sit and message the chat they
 * want, and nothing would ever arrive, with the green pill insisting the wait was working.
 *
 * [onStop] is null unless a wait is already armed, since there is nothing to stop otherwise.
 */
@Composable
private fun NoAccessBlock(onOpenSettings: () -> Unit, onStop: (() -> Unit)?) {
    LivePill(stringResource(R.string.chat_not_listening), ok = false)
    Text(
        stringResource(R.string.chat_no_access_title),
        style = MaterialTheme.typography.bodyLarge,
    )
    Caption(stringResource(R.string.chat_no_access_body))
    Button(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
        ButtonLabel(stringResource(R.string.chat_open_notification_access))
    }
    if (onStop != null) {
        TextButton(onClick = onStop) { ButtonLabel(stringResource(R.string.chat_stop_waiting)) }
    }
}

/** Shown while [Store.startLearning] is armed and no message has arrived yet. */
@Composable
private fun WaitingBlock(appLabel: String, onStop: () -> Unit) {
    LivePill(stringResource(R.string.chat_listening), ok = true)
    Text(
        stringResource(R.string.chat_waiting, appLabel),
        style = MaterialTheme.typography.bodyLarge,
    )
    Caption(stringResource(R.string.chat_waiting_hint))
    TextButton(onClick = onStop) { ButtonLabel(stringResource(R.string.chat_stop_waiting)) }
}

/** Shown once [Store.learned] has a chat, so the user can confirm it is the right one. */
@Composable
private fun CapturedBlock(ref: ConversationRef?, onUse: () -> Unit, onAgain: () -> Unit) {
    val name = ref?.name.orEmpty()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            stringResource(R.string.chat_captured, name),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (ref?.isGroup == true) ConversationBadge(stringResource(R.string.chat_badge_group))
    }
    Caption(
        if (ref?.key != null) stringResource(R.string.chat_captured_has_id)
        else stringResource(R.string.chat_captured_no_id)
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(onClick = onUse, modifier = Modifier.weight(1f)) {
            ButtonLabel(stringResource(R.string.chat_use_this))
        }
        TextButton(onClick = onAgain, modifier = Modifier.weight(1f)) {
            ButtonLabel(stringResource(R.string.chat_wait_another))
        }
    }
}

/** One row in the learned list: who it was, whether it is a group, and how long ago it spoke. */
@Composable
private fun ConversationRow(ref: ConversationRef, onPick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onPick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
            Icon(
                if (ref.isGroup) Icons.Rounded.Groups else Icons.Rounded.Person,
                contentDescription = null,
            )
        }
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    ref.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (ref.isGroup) ConversationBadge(stringResource(R.string.chat_badge_group))
            }
            Caption(stringResource(R.string.chat_last_message, relativeAgo(ref.lastSeenMs)))
        }
    }
}

/**
 * Whether HiLight is currently an enabled notification listener.
 *
 * Deliberately a copy of the identical check in SetupScreen.kt rather than a shared helper: that one
 * is private to its own file, and widening it would mean editing a file this change does not own. The
 * check is two lines of a documented Settings.Secure key, so a second copy is cheaper than the wrong
 * kind of coupling — but if either ever gains a subtlety, both must gain it.
 */
private fun hasListenerAccess(ctx: Context): Boolean {
    val flat = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners")
        ?: return false
    return flat.contains(ctx.packageName)
}

/**
 * The display name behind a contact URI handed back by the system picker.
 *
 * Must be called off the main thread: this is a content provider query, so it is disk and IPC. The
 * URI carries its own one-shot read grant, which is why no permission is involved.
 */
private fun contactDisplayName(ctx: Context, uri: Uri): String? = runCatching {
    // Logged on failure because the three ways this returns null are told to the user as one
    // sentence: the query threw, the contact genuinely has no name, or the name it has normalises to
    // nothing. Only the first is a fault, and only the log can tell them apart afterwards.
    ctx.contentResolver.query(
        uri,
        arrayOf(ContactsContract.Contacts.DISPLAY_NAME),
        null,
        null,
        null,
    )?.use { cursor ->
        val column = cursor.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME)
        if (column >= 0 && cursor.moveToFirst()) {
            cursor.getString(column)?.trim()?.takeIf { it.isNotEmpty() }
        } else {
            null
        }
    }
}
    .onFailure { Log.w("HiLightPicker", "contact lookup failed", it) }
    .getOrNull()
