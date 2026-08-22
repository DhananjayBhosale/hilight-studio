package com.hilight.studio

import android.os.PowerManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

class NotificationTrigger : NotificationListenerService() {
    private val store by lazy { Store.get(this) }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        Log.d(TAG, "posted by ${sbn.packageName}")
        if (sbn.packageName == packageName && sbn.notification.channelId == "fg_watch") return
        val rule = store.ruleFor(sbn.packageName, Trigger.NOTIFICATION) ?: return
        if (sbn.isOngoing) return
        if (rule.onlyWhenScreenOff && screenOn()) return
        if (store.respectDnd.value && inDoNotDisturb()) {
            Log.i(TAG, "suppressed by Do Not Disturb")
            return
        }
        if (rule.keyword.isNotBlank() && !matchesKeyword(sbn, rule.keyword)) return
        Log.i(TAG, "alert for ${sbn.packageName} pattern=${rule.pattern.key}")
        store.fireAlert(rule)
    }

    private fun inDoNotDisturb(): Boolean =
        currentInterruptionFilter.let {
            it == INTERRUPTION_FILTER_PRIORITY ||
                it == INTERRUPTION_FILTER_ALARMS ||
                it == INTERRUPTION_FILTER_NONE
        }

    private fun matchesKeyword(sbn: StatusBarNotification, keyword: String): Boolean {
        val extras = sbn.notification.extras
        val haystack = buildString {
            append(extras.getCharSequence(android.app.Notification.EXTRA_TITLE) ?: "")
            append(' ')
            append(extras.getCharSequence(android.app.Notification.EXTRA_TEXT) ?: "")
            append(' ')
            append(extras.getCharSequence(android.app.Notification.EXTRA_BIG_TEXT) ?: "")
        }
        return haystack.contains(keyword.trim(), ignoreCase = true)
    }

    private fun screenOn(): Boolean =
        getSystemService(PowerManager::class.java)?.isInteractive ?: true

    private companion object {
        const val TAG = "HiLightNotif"
    }
}
