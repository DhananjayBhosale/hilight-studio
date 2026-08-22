package com.hilight.studio

import android.os.PowerManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

/**
 * Turns notifications from chosen apps into HiLight alerts.
 *
 * Everything a rule could match on is pulled out of the notification once, by [NotificationPeek], and
 * then handed to [Store]: the chat is remembered so the rule picker can offer it later, the peek is
 * kept for the inspector, and only after that is a rule looked for. The noting deliberately happens
 * before every reason to stop below, because an app with no rule yet is exactly the one the user is
 * about to write a rule for.
 */
class NotificationTrigger : NotificationListenerService() {

    private val store by lazy { Store.get(this) }

    /**
     * The newest message stamp already acted on, per notification key.
     *
     * Messaging apps re-post the same notification for anything that changes the chat — a read
     * receipt, a typing indicator, another message in a different chat inside the same bundle — and
     * every re-post arrives here as a fresh callback. Without this the same sentence flashes the array
     * several times over.
     *
     * Access-ordered and self-trimming, so when it fills the entries it drops are the chats that have
     * been quiet the longest. Keys for notifications the user dismisses are removed in
     * [onNotificationRemoved]; the bound is there for the ones that went away while the listener was
     * not bound to see it.
     */
    private val handled = object : LinkedHashMap<String, Long>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>): Boolean =
            size > MAX_TRACKED
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        // This is a system callback, and a listener that throws is a listener the framework may stop
        // trusting — which would take every rule with it. One catch around the whole path is worth
        // more than hoping each step in it behaves.
        runCatching { handlePosted(sbn) }
            .onFailure { Log.w(TAG, "could not handle notification", it) }
    }

    private fun handlePosted(sbn: StatusBarNotification) {
        Log.d(TAG, "posted by ${sbn.packageName}")
        // HiLight's own foreground-watcher notification, which would otherwise light the array through
        // the catch-all rule every time the watcher restarted.
        if (sbn.packageName == packageName && sbn.notification.channelId == "fg_watch") return

        val info = NotificationPeek.read(sbn)
        store.notePeek(info)
        store.noteConversation(info)

        if (info.isGroupSummary) return                 // the app's own "3 new messages" wrapper
        if (info.isOngoing) return                      // media/progress notifications repeat a lot
        if (!isNewMessage(info)) {
            // The stamps go in the line because they are the only way to tell a genuine duplicate
            // (identical stamp) from a message that arrived carrying an older one — a resend, or a
            // sender whose clock runs behind, both of which look like a re-post from here.
            Log.i(
                TAG,
                "re-post from ${info.pkg}: stamp=${ConversationMatch.stampOf(info)} " +
                    "not newer than ${lastStampFor(info.notifKey)}",
            )
            return
        }

        val rule = store.ruleForMessage(info) ?: return

        // These two guards silence the flash, but the rule did match, and the rules screen shows
        // exactly that: "last matched". Returning before recording it would leave a working rule
        // reading "not matched yet" for anyone who keeps Do Not Disturb on or asked for screen-off
        // flashes only — indistinguishable from a rule that has never matched anything. The guards
        // inside fireAlert record the match for the same reason.
        if (rule.onlyWhenScreenOff && screenOn()) {
            Log.i(TAG, "matched ${info.pkg} but the rule only flashes with the screen off")
            store.noteRuleFired(rule, info)
            return
        }
        if (store.respectDnd.value && inDoNotDisturb()) {
            Log.i(TAG, "matched ${info.pkg} but suppressed by Do Not Disturb")
            store.noteRuleFired(rule, info)
            return
        }

        // How it matched, never what the message said and never who sent it. A conversation rule's
        // label is a contact's name, and CONTRIBUTING asks users to scrub personal data out of logs
        // they share — so the line says whether a chat rule or an app rule won, which is enough to
        // answer "why did that one fire and not my other one" without naming anybody.
        val how = ConversationMatch.strength(rule, info) ?: when {
            rule.keyword.isNotBlank() -> MatchStrength.TEXT
            rule.isCatchAll -> MatchStrength.CATCH_ALL
            else -> MatchStrength.APP
        }
        val scope = when {
            rule.isConversationRule -> "chat"
            rule.keyword.isNotBlank() -> "text"
            else -> "app"
        }
        Log.i(TAG, "alert for ${info.pkg} rule=$scope match=$how pattern=${rule.pattern.key}")
        store.fireAlert(rule)
        store.noteRuleFired(rule, info)
    }

    /**
     * Forgets a dismissed notification's stamp.
     *
     * The key is derived exactly the way it was on the way in, so the two sides cannot drift apart if
     * the peek ever stops using the framework's own key. Without this a chat that is dismissed and
     * then says the same thing again — a resend, which carries the original message's older stamp —
     * would be taken for a re-post and ignored.
     */
    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        runCatching {
            val key = NotificationPeek.read(sbn).notifKey
            if (key.isNotEmpty()) synchronized(handled) { handled.remove(key) }
        }.onFailure { Log.w(TAG, "could not handle removal", it) }
    }

    /**
     * Everything the de-duplication map holds describes notifications that were on screen while this
     * listener was bound. After a rebind the shade has moved on without us, so the old stamps can only
     * be misleading.
     */
    override fun onListenerDisconnected() {
        synchronized(handled) { handled.clear() }
    }

    /**
     * True when [info] carries a message this listener has not already acted on, recording it if so.
     *
     * Recorded here rather than once a rule has agreed to fire, because the question this map answers
     * is "have I seen this message?" — which has nothing to do with whether a rule wanted it.
     *
     * Synchronized because a notification callback is not promised to arrive on any one thread, and an
     * access-ordered map reorders itself even on a plain read.
     */
    /** The stamp last acted on for [notifKey], for the log line that explains a discarded re-post. */
    private fun lastStampFor(notifKey: String): Long? =
        synchronized(handled) { handled[notifKey] }

    private fun isNewMessage(info: MessageInfo): Boolean {
        // No key means nothing to remember it by. Letting it through beats having every keyless
        // notification share one slot and silence each other.
        if (info.notifKey.isEmpty()) return true
        synchronized(handled) {
            if (!ConversationMatch.isNewer(info, handled[info.notifKey])) return false
            handled[info.notifKey] = ConversationMatch.stampOf(info)
            return true
        }
    }

    /**
     * The listener sees the current interruption filter without needing policy access, which a plain
     * app would.
     */
    private fun inDoNotDisturb(): Boolean =
        currentInterruptionFilter.let {
            it == INTERRUPTION_FILTER_PRIORITY ||
                it == INTERRUPTION_FILTER_ALARMS ||
                it == INTERRUPTION_FILTER_NONE
        }

    private fun screenOn(): Boolean =
        getSystemService(PowerManager::class.java)?.isInteractive ?: true

    private companion object {
        const val TAG = "HiLightNotif"

        /**
         * How many notification keys are tracked at once. Well past what a phone holds in the shade,
         * so in practice only keys the listener never saw dismissed are ever evicted.
         */
        const val MAX_TRACKED = 200
    }
}
