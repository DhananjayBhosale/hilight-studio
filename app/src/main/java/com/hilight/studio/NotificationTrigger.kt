package com.hilight.studio

import android.app.KeyguardManager
import android.app.Notification
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

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
    private val main = Handler(Looper.getMainLooper())
    private val reminders = PendingNotificationReminders()
    private val incomingCalls = linkedSetOf<String>()
    private var connected = false
    private var observationScope: CoroutineScope? = null
    private var signalOwner: String? = null
    private var reminderOwner: String? = null
    private val unlockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_USER_PRESENT) {
                reminders.clear()
                reminderOwner?.let(store::cancelOwnedAlert)
                reminderOwner = null
                // Keep call state, but a user who unlocked should not see an immediate replay.
                main.removeCallbacks(tick)
                scheduleTick()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        registerReceiver(unlockReceiver, IntentFilter(Intent.ACTION_USER_PRESENT))
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        // Android reconnects an approved notification listener after boot. Touching Store here
        // restores persisted While open rules without requiring the user to open HiLight Studio or
        // wait for an unrelated notification first.
        store.syncForegroundWatcher()
        connected = true
        store.deviceSignals.onInterruptionFilterChanged(currentInterruptionFilter)
        if (store.enabled.value && locked()) seedReminders()
        observationScope?.cancel()
        observationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { scope ->
            scope.launch { store.deviceSignals.settings.collect { reconcileSignals() } }
            scope.launch { store.rules.collect { reconcileSignals() } }
            scope.launch {
                store.enabled.collect { enabled ->
                    if (!enabled) clearSignals() else reconcileSignals()
                }
            }
        }
    }

    override fun onInterruptionFilterChanged(interruptionFilter: Int) {
        store.deviceSignals.onInterruptionFilterChanged(interruptionFilter)
        reconcileSignals()
    }

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

        updateIncomingCall(sbn)
        if (store.deviceSignals.settings.value.callsEnabled && incoming(sbn)) return
        val info = readMessage(sbn)
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

        val rule = store.ruleForMessage(info) ?: run {
            reminders.remove(sbn.key)
            return
        }
        if (rule.keyword.isNotBlank() && !matchesKeyword(info, rule.keyword)) {
            reminders.remove(sbn.key)
            return
        }
        if (rule.repeatWhilePending && store.enabled.value && locked()) {
            reminders.posted(sbn.key, rule.id, sbn.postTime, SystemClock.elapsedRealtime(), rule.repeatIntervalMs)
            scheduleTick()
        } else reminders.remove(sbn.key)

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
        if (rule.onlyWhenFaceDown && !store.isFaceDownNow()) {
            Log.i(TAG, "matched ${info.pkg} but the rule only flashes while face down")
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
        val how = ConversationMatch.strength(rule, info)
            ?: if (rule.isCatchAll) MatchStrength.CATCH_ALL else MatchStrength.APP
        val scope = if (rule.isConversationRule) "chat" else "app"
        Log.i(TAG, "alert for ${info.pkg} rule=$scope match=$how pattern=${rule.pattern.key}")
        store.fireAlert(rule, owner = "notification:${sbn.key}")
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
            reminders.remove(sbn.key)
            incomingCalls.remove(sbn.key)
            store.cancelOwnedAlert("notification:${sbn.key}")
            store.cancelOwnedAlert("reminder:${sbn.key}")
            store.cancelOwnedAlert("call:${sbn.key}")
            reconcileSignals()
        }.onFailure { Log.w(TAG, "could not handle removal", it) }
    }

    /**
     * Everything the de-duplication map holds describes notifications that were on screen while this
     * listener was bound. After a rebind the shade has moved on without us, so the old stamps can only
     * be misleading.
     */
    override fun onListenerDisconnected() {
        synchronized(handled) { handled.clear() }
        connected = false
        observationScope?.cancel()
        observationScope = null
        clearSignals()
        store.deviceSignals.onInterruptionFilterChanged(INTERRUPTION_FILTER_UNKNOWN)
    }

    override fun onDestroy() {
        onListenerDisconnected()
        unregisterReceiver(unlockReceiver)
        super.onDestroy()
    }

    private fun readMessage(sbn: StatusBarNotification): MessageInfo {
        val ranking = Ranking()
        val ranked = currentRanking.getRanking(sbn.key, ranking)
        val channel = if (ranked) ranking.channel else null
        val silent = if (ranked) {
            isSilentNotification(ranking.importance, channel == null || channel.sound != null, channel?.shouldVibrate() == true)
        } else false
        return NotificationPeek.read(sbn).copy(isSilent = silent)
    }

    private fun seedReminders() {
        val active = runCatching { activeNotifications?.sortedBy { it.postTime } }.getOrNull() ?: return
        for (sbn in active) {
            runCatching {
                val info = readMessage(sbn)
                if (!info.isOngoing && !info.isGroupSummary) {
                    val rule = store.ruleForMessage(info)
                    if (rule != null && rule.repeatWhilePending &&
                        (rule.keyword.isBlank() || matchesKeyword(info, rule.keyword))) {
                        reminders.posted(sbn.key, rule.id, sbn.postTime, SystemClock.elapsedRealtime(), rule.repeatIntervalMs)
                    }
                }
            }
        }
    }

    private fun incoming(sbn: StatusBarNotification): Boolean = runCatching {
        sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0 &&
            isIncomingCallType(sbn.notification.extras.getInt(Notification.EXTRA_CALL_TYPE, 0))
    }.getOrDefault(false)

    private fun updateIncomingCall(sbn: StatusBarNotification) {
        if (store.deviceSignals.settings.value.callsEnabled && incoming(sbn)) {
            incomingCalls.add(sbn.key)
        } else if (incomingCalls.remove(sbn.key)) store.cancelOwnedAlert("call:${sbn.key}")
        if (incomingCalls.isNotEmpty()) scheduleTick(immediate = true)
    }

    private val tick = Runnable { reconcileSignals() }

    private fun scheduleTick(immediate: Boolean = false) {
        main.removeCallbacks(tick)
        if (connected && store.enabled.value && (incomingCalls.isNotEmpty() || reminders.latest() != null)) {
            val delay = when {
                immediate -> 0L
                incomingCalls.isNotEmpty() -> 2_000L
                else -> ((reminders.latest()?.dueAtMs ?: 0L) - SystemClock.elapsedRealtime()).coerceIn(2_000L, 60_000L)
            }
            main.postDelayed(tick, delay)
        }
    }

    private fun clearSignals() {
        main.removeCallbacks(tick)
        reminders.clear()
        incomingCalls.clear()
        signalOwner?.let(store::cancelOwnedAlert)
        reminderOwner?.let(store::cancelOwnedAlert)
        signalOwner = null
        reminderOwner = null
    }

    private fun reconcileSignals() {
        runCatching { reconcileSignalState() }.onFailure {
            clearSignals()
            Log.w(TAG, "could not refresh notification signals", it)
        }
    }

    private fun reconcileSignalState() {
        if (!connected || !store.enabled.value) { clearSignals(); return }
        val active = runCatching { activeNotifications?.toList() }.getOrNull()
        if (active == null) { clearSignals(); return }
        val byKey = active.associateBy { it.key }
        reminders.retain(byKey.keys)
        val settings = store.deviceSignals.settings.value
        incomingCalls.clear()
        if (settings.callsEnabled) active.filter(::incoming).forEach { incomingCalls.add(it.key) }
        val newestCall = active.filter { it.key in incomingCalls }.maxByOrNull { it.postTime }
        val owner = newestCall?.let { "call:${it.key}" }
        if (signalOwner != owner) signalOwner?.let(store::cancelOwnedAlert)
        signalOwner = owner
        if (owner != null) {
            store.showDeviceSignal(owner, Ambient(pattern = Pattern.PULSE, color = settings.callColor, speedMs = 1000), 2500)
        }

        if (!locked()) {
            reminders.clear()
            reminderOwner?.let(store::cancelOwnedAlert)
            reminderOwner = null
        }
        val next = reminders.latest()
        if (next != null) {
            val sbn = byKey[next.key]
            val info = sbn?.let { runCatching { readMessage(it) }.getOrNull() }
            val rule = info?.let(store::ruleForMessage)
            if (rule == null || !rule.repeatWhilePending ||
                (rule.keyword.isNotBlank() && !matchesKeyword(info, rule.keyword))) {
                reminders.remove(next.key)
                store.cancelOwnedAlert("reminder:${next.key}")
            } else if (SystemClock.elapsedRealtime() >= next.dueAtMs && !store.hasActiveAlert()) {
                reminderOwner = "reminder:${next.key}"
                store.fireAlert(rule.copy(pattern = Pattern.PULSE, durationMs = 1000, speedMs = 1000), reminderOwner)
                reminders.defer(next.key, SystemClock.elapsedRealtime(), rule.repeatIntervalMs)
            }
        }
        scheduleTick()
    }

    private fun locked(): Boolean = !screenOn() || getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true

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

    /**
     * Matches the rule's keyword against what the peek already read.
     *
     * Deliberately not a second read of the notification's extras. That Bundle can carry a Parcelable
     * this process cannot load, which is why every read of it in [NotificationPeek] is guarded — and
     * touching it again here would put an unguarded read on the path of a rule that had already agreed
     * to fire, so a throw would swallow the alert. The same strings, read once, safely.
     */
    private fun matchesKeyword(info: MessageInfo, keyword: String): Boolean {
        val haystack = buildString {
            append(info.title.orEmpty())
            append(' ')
            append(info.text.orEmpty())
            append(' ')
            append(info.sender.orEmpty())
            append(' ')
            append(info.conversationTitle.orEmpty())
        }
        return haystack.contains(keyword.trim(), ignoreCase = true)
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
