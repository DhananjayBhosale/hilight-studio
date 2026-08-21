package com.hilight.studio

import android.os.PowerManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

/** Turns notifications from chosen apps into HiLight alerts. */
class NotificationTrigger : NotificationListenerService() {

    private val store by lazy { Store.get(this) }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        Log.d(TAG, "posted by ${sbn.packageName}")
        if (sbn.packageName == packageName && sbn.notification.channelId == "fg_watch") return
        val rule = store.notificationRuleFor(sbn.packageName, searchableText(sbn)) ?: return
        if (sbn.isOngoing) return                       // media/progress notifications repeat a lot
        if (rule.onlyWhenScreenOff && screenOn()) return
        if (store.respectDnd.value && inDoNotDisturb()) {
            Log.i(TAG, "suppressed by Do Not Disturb")
            return
        }
        Log.i(TAG, "alert for ${sbn.packageName} pattern=${rule.pattern.key}")
        store.fireAlert(rule)
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

    private fun searchableText(sbn: StatusBarNotification): String {
        val extras = sbn.notification.extras
        return buildString {
            append(extras.getCharSequence(android.app.Notification.EXTRA_TITLE) ?: "")
            append(' ')
            append(extras.getCharSequence(android.app.Notification.EXTRA_TEXT) ?: "")
            append(' ')
            append(extras.getCharSequence(android.app.Notification.EXTRA_BIG_TEXT) ?: "")
        }
    }

    private fun screenOn(): Boolean =
        getSystemService(PowerManager::class.java)?.isInteractive ?: true

    private companion object {
        const val TAG = "HiLightNotif"
    }
}
