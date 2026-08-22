package com.hilight.studio

import org.json.JSONObject

/**
 * Per-conversation rules: "green when Sujay messages on WhatsApp".
 *
 * Everything here is pure Kotlin so it can be unit-tested without a device. The Android side — pulling
 * these fields out of a live notification — lives in NotificationPeek.kt.
 *
 * The design rule that matters: **the user never types a name.** A rule is always created from a
 * string the app has already seen in a real notification (or from the system contact picker, which is
 * then healed to a real one on first sighting). Anything typed by hand is a guess, and a guess that is
 * one emoji or one nickname away from the truth fails silently, which is the worst way for a
 * notification light to fail.
 */

/**
 * One messaging conversation the listener has seen.
 *
 * [key] is the notification's `shortcutId`: an opaque, stable per-chat id that survives the contact
 * being renamed. It is the preferred matcher, but it is not always there — apps that never adopted
 * conversation notifications don't set it — so [name] is kept as the fallback.
 */
data class ConversationRef(
    val pkg: String,
    val name: String,
    val key: String? = null,
    /** true when this was seen as a group chat rather than a one-to-one */
    val isGroup: Boolean = false,
    val lastSeenMs: Long = 0L,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("pkg", pkg)
        put("name", name)
        key?.let { put("key", it) }
        put("isGroup", isGroup)
        put("lastSeenMs", lastSeenMs)
    }

    companion object {
        fun fromJson(o: JSONObject) = ConversationRef(
            pkg = o.getString("pkg"),
            name = o.optString("name", ""),
            key = o.optString("key", "").takeIf { it.isNotEmpty() },
            isGroup = o.optBoolean("isGroup", false),
            lastSeenMs = o.optLong("lastSeenMs", 0L),
        )

        /** How many conversations are remembered before the oldest are dropped. */
        const val MAX_REMEMBERED = 300
    }
}

/**
 * The parts of a posted notification that decide which rule fires.
 *
 * Filled in by [NotificationPeek.read]. Kept as a plain data class so the matcher can be tested with
 * hand-written instances.
 */
data class MessageInfo(
    val pkg: String,
    /** notification key, used only to de-duplicate re-posts of the same conversation */
    val notifKey: String = "",
    /** `Notification.shortcutId` — stable per-chat id where the app sets one */
    val shortcutId: String? = null,
    /** who sent the newest message, from MessagingStyle. In a group this is the speaker, not the group */
    val sender: String? = null,
    /**
     * The group's name, and non-null **only** for a real group chat.
     *
     * Deliberately not "whatever MessagingStyle.conversationTitle returned". androidx also stores the
     * title in a hidden extra and restores from it when the visible one is absent, which is exactly
     * what happens for one-to-one chats — so the raw value is non-null for plenty of chats that are
     * not groups. [NotificationPeek] settles the question from EXTRA_IS_GROUP_CONVERSATION, or the
     * visible EXTRA_CONVERSATION_TITLE, and only then fills this in. Reading the raw style value here
     * would make "green when Sujay messages" fail silently, because a person match is refused inside
     * a group unless the rule opted into groups.
     */
    val conversationTitle: String? = null,
    val title: String? = null,
    val text: String? = null,
    /** the summary notification an app posts alongside the real ones; never fires a rule */
    val isGroupSummary: Boolean = false,
    /**
     * An ongoing notification — a call, a backup, a media session.
     *
     * These never fire a rule, and they must not reach the chat picker either: a messaging app's
     * "Backup in progress" carries a perfectly good title, so without this the picker offers it as a
     * chat the user could colour.
     */
    val isOngoing: Boolean = false,
    val isMessagingStyle: Boolean = false,
    /** timestamp of the newest message, so a re-post with nothing new can be ignored */
    val messageStampMs: Long = 0L,
    val postTimeMs: Long = 0L,
    /**
     * The read of this notification threw, so every field below the package is missing rather than
     * absent.
     *
     * Without this flag the two look identical in the inspector, and "this app never names the
     * sender, so write a rule for the whole app instead" would be the advice given for what is
     * actually a bug worth reporting.
     */
    val readFailed: Boolean = false,
) {
    /**
     * True when this looks like a chat the user could write a per-contact rule for.
     *
     * A recovered sender counts even without a recognised MessagingStyle template, because some apps
     * set the message extras without either "self" extra and androidx then declines to build a style
     * for them. Apps that carry no per-chat signal at all — Discord posts a plain big-text
     * notification with the sender packed into the title — never satisfy this, and cannot: the only
     * thing left is a title, which every notification on the phone has. Those chats are reachable
     * through "learn the next message" and match by title instead.
     */
    val isConversation: Boolean
        get() = !isGroupSummary && (isMessagingStyle || shortcutId != null || !sender.isNullOrBlank())

    val isGroup: Boolean get() = !conversationTitle.isNullOrBlank()

    /**
     * The name to offer the user for this chat: the group's name for a group, otherwise whoever sent it.
     *
     * Falls back to the notification title, which is what apps that never adopted MessagingStyle
     * (Slack and Discord, as far as we know) leave us with.
     */
    val displayName: String?
        get() = conversationTitle?.takeIf { it.isNotBlank() }
            ?: sender?.takeIf { it.isNotBlank() }
            ?: title?.takeIf { it.isNotBlank() }

    /** What the app should remember about this chat for the rule picker. */
    fun toRef(): ConversationRef? {
        val n = displayName ?: return null
        return ConversationRef(
            pkg = pkg,
            name = n,
            key = shortcutId,
            isGroup = isGroup,
            lastSeenMs = postTimeMs,
        )
    }

    /** Everything a "why didn't my rule fire?" answer needs, as readable text. */
    fun describe(): String = buildString {
        append(pkg)
        append('\n')
        append("shortcutId: ").append(shortcutId ?: "—").append('\n')
        append("sender: ").append(sender ?: "—").append('\n')
        append("conversation: ").append(conversationTitle ?: "—").append('\n')
        append("title: ").append(title ?: "—").append('\n')
        append("messagingStyle: ").append(isMessagingStyle)
        if (isGroupSummary) append("\ngroup summary (ignored)")
    }
}

/** How a rule matched, best first. Also the tie-breaker when several rules could fire. */
enum class MatchStrength(val score: Int) {
    /** the rule's stored shortcutId equalled the notification's */
    KEY(5),
    /** normalised names were equal */
    NAME(4),
    /** the notification's title contained the rule's name — Discord's "Sujay (#general, Server)" */
    CONTAINS(3),
    /** an app-wide notification-text rule */
    TEXT(2),
    /** a plain per-app rule, no conversation involved */
    APP(1),
    /** the catch-all rule */
    CATCH_ALL(0),
}

object ConversationMatch {

    /**
     * Names are compared with punctuation, emoji and case removed.
     *
     * WhatsApp shows exactly what is in the address book, so a contact saved as "Sujay 🇮🇳" or
     * "Sujay (work)" would otherwise never match a rule created from the contact picker's "Sujay".
     * Both sides go through this, so the transform only has to be consistent, not clever.
     */
    fun normalise(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        return raw
            .replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")   // strips emoji, brackets, +, dashes
            .replace(Regex("\\s+"), " ")
            .trim()
            .lowercase()
    }

    /** Contains-matching on short names hits far too much, so it needs a floor. */
    private const val MIN_CONTAINS_CHARS = 3

    /**
     * How well [rule] matches [info], or null for no match.
     *
     * The ladder, strongest first:
     *  1. stored `shortcutId` equals the notification's — immune to renames
     *  2. normalised name equals the sender, the group title, or the notification title
     *  3. the notification title *contains* the rule's name, for apps that pack extra text into it
     */
    fun strength(rule: AppRule, info: MessageInfo): MatchStrength? {
        if (!rule.isConversationRule) return null

        // A key on both sides is the whole answer: equal means yes, different means this is a
        // different chat and no amount of name similarity should override that.
        val ruleKey = rule.conversationKey
        val infoKey = info.shortcutId
        if (!ruleKey.isNullOrBlank() && !infoKey.isNullOrBlank()) {
            return if (ruleKey == infoKey) MatchStrength.KEY else null
        }

        val want = normalise(rule.conversationName)
        if (want.isEmpty()) return null

        val group = normalise(info.conversationTitle)
        val sender = normalise(info.sender)
        val title = normalise(info.title)

        // A rule naming the group itself fires for anything said in it.
        if (group.isNotEmpty() && group == want) return MatchStrength.NAME

        // A rule naming a person fires for their one-to-one chat, and inside groups only when the
        // user asked for that — otherwise "green for Sujay" lights up for every group he is in.
        val personMatch = sender.isNotEmpty() && sender == want
        if (personMatch && (!info.isGroup || rule.includeGroups)) return MatchStrength.NAME

        // Apps without MessagingStyle leave only the title. Treat it as the chat name.
        if (title.isNotEmpty() && title == want && !info.isGroup) return MatchStrength.NAME

        // Containment needs the same group guard as the sender branch above. Without it a rule for
        // "Sujay" fires on an unnamed group whose title is the member list — "Sujay, Amit, Priya" —
        // which is precisely the leak the includeGroups switch exists to stop.
        if (want.length >= MIN_CONTAINS_CHARS && title.isNotEmpty() && title.contains(want) &&
            (!info.isGroup || rule.includeGroups)
        ) {
            return MatchStrength.CONTAINS
        }
        return null
    }

    /**
     * Can this rule ever fire?
     *
     * A conversation rule with neither a key nor a name that survives [normalise] is dead on arrival:
     * a chat named only with an emoji normalises to nothing, and a key-only rule stops matching the
     * moment its app omits the shortcutId, which apps do intermittently. Rule creation should always
     * store a name alongside the key, and refuse anything this rejects.
     */
    fun isMatchable(rule: AppRule): Boolean {
        if (!rule.isConversationRule) return true
        return !rule.conversationKey.isNullOrBlank() || normalise(rule.conversationName).isNotEmpty()
    }

    /** Whether two saved rules describe the same trigger target, independent of their light look. */
    fun sameTarget(a: AppRule, b: AppRule): Boolean {
        if (a.pkg != b.pkg || a.trigger != b.trigger) return false
        if (a.isConversationRule != b.isConversationRule) return false
        if (a.trigger == Trigger.FOREGROUND) return !a.isConversationRule

        if (a.isConversationRule) {
            val aKey = a.conversationKey?.takeIf { it.isNotBlank() }
            val bKey = b.conversationKey?.takeIf { it.isNotBlank() }
            val sameChat = if (aKey != null && bKey != null) {
                aKey == bKey
            } else {
                val aName = normalise(a.conversationName)
                aName.isNotEmpty() && aName == normalise(b.conversationName)
            }
            if (!sameChat) return false
        }
        return a.keyword.trim().equals(b.keyword.trim(), ignoreCase = true)
    }

    /**
     * The rule that should fire for [info], most specific first.
     *
     * Order: a conversation rule, an app-wide text rule, the app fallback, then the catch-all.
     * Text rules are checked in saved order, so overlapping phrases have a visible deterministic
     * priority while the conversation matcher keeps its existing specificity ladder.
     */
    fun resolve(rules: List<AppRule>, info: MessageInfo): AppRule? = resolveWith(rules, info)?.first

    /**
     * As [resolve], but also says *how* the rule was found.
     *
     * The strength is what makes "why didn't my rule fire?" answerable from a log line, and it is the
     * reason [MatchStrength] carries entries for the two non-conversation outcomes as well.
     */
    fun resolveWith(rules: List<AppRule>, info: MessageInfo): Pair<AppRule, MatchStrength>? {
        if (info.isGroupSummary) return null
        val candidates = rules.filter { it.enabled && it.trigger == Trigger.NOTIFICATION }

        // A conversation rule on the catch-all package means "this person, in whichever app they
        // reach me" — worth having, since the same person turns up on WhatsApp and on SMS. A rule
        // naming the app beats it when both match, hence the package-exact tie-breaker.
        val best = candidates
            .filter { it.isConversationRule && (it.pkg == info.pkg || it.isCatchAll) }
            .mapNotNull { rule ->
                if (!matchesText(info, rule.keyword)) return@mapNotNull null
                strength(rule, info)?.let { s -> rule to s }
            }
            .maxWithOrNull(
                compareBy<Pair<AppRule, MatchStrength>>(
                    { it.second.score },
                    { if (it.first.pkg == info.pkg) 1 else 0 },
                    { if (it.first.keyword.isNotBlank()) 1 else 0 },
                )
            )
        if (best != null) return best.first to best.second

        val appRules = candidates.filter { it.pkg == info.pkg && !it.isConversationRule }
        appRules.firstOrNull { it.keyword.isNotBlank() && matchesText(info, it.keyword) }
            ?.let { return it to MatchStrength.TEXT }
        appRules.firstOrNull { it.keyword.isBlank() }
            ?.let { return it to MatchStrength.APP }
        // An enabled app-wide rule means this app owns its notification behavior. If all of its text
        // rules miss and it has no local fallback, preserve upstream's behavior and do nothing rather
        // than unexpectedly falling through to the global Any app rule.
        if (appRules.isNotEmpty()) return null

        // Keep the existing catch-all semantics: it is considered after this app's own rules. Older
        // installs could already have a keyword on Any app, so continue honoring that even though the
        // new creation UI intentionally keeps global rules simple.
        val catchAll = candidates.filter { it.isCatchAll && !it.isConversationRule }
        catchAll.firstOrNull { it.keyword.isNotBlank() && matchesText(info, it.keyword) }
            ?.let { return it to MatchStrength.TEXT }
        return catchAll.firstOrNull { it.keyword.isBlank() }
            ?.let { it to MatchStrength.CATCH_ALL }
    }

    fun matchesText(info: MessageInfo, rawKeyword: String): Boolean {
        val keyword = rawKeyword.trim()
        if (keyword.isEmpty()) return true
        val haystack = buildString {
            append(info.title.orEmpty())
            append(' ')
            append(info.text.orEmpty())
            append(' ')
            append(info.sender.orEmpty())
            append(' ')
            append(info.conversationTitle.orEmpty())
        }
        return haystack.contains(keyword, ignoreCase = true)
    }

    /**
     * Does this notification carry a newer message than the last one handled for the same chat?
     *
     * Messaging apps re-post the *same* notification every time the conversation changes — a read
     * receipt, a typing indicator, another message in a different chat inside a bundle. Without this
     * check a single chat re-fires its colour on every one of those updates.
     */
    fun isNewer(info: MessageInfo, lastStampMs: Long?): Boolean {
        if (lastStampMs == null) return true
        val stamp = if (info.messageStampMs > 0) info.messageStampMs else info.postTimeMs
        return stamp > lastStampMs
    }

    /** The stamp to remember for [info] once it has been handled. */
    fun stampOf(info: MessageInfo): Long =
        if (info.messageStampMs > 0) info.messageStampMs else info.postTimeMs
}
