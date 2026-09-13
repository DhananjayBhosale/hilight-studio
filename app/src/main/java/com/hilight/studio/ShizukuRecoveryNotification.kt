package com.hilight.studio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import rikka.shizuku.Shizuku

internal const val SHIZUKU_RECOVERY_CHANNEL = "shizuku_recovery"
internal const val OPEN_SETUP_ACTION = "com.hilight.studio.OPEN_SETUP"
private const val RECOVERY_ID = 56

internal fun shizukuUnavailableForNotice(state: ShizukuBackend.State, managerAlive: Boolean): Boolean =
    !managerAlive && state in setOf(ShizukuBackend.State.NOT_RUNNING,
        ShizukuBackend.State.NOT_INSTALLED, ShizukuBackend.State.FAILED)

internal fun shouldWarnAboutShizuku(
    optedIn: Boolean,
    masterEnabled: Boolean,
    lastWorking: String?,
    selected: Transport,
    unavailable: Boolean,
    rendererConnected: Boolean,
    rootStarting: Boolean,
): Boolean = optedIn && masterEnabled && lastWorking == Transport.SHIZUKU.name &&
    selected in setOf(Transport.AUTO, Transport.SHIZUKU) && unavailable &&
    !rendererConnected && !rootStarting

/** Event-driven notice; no network request, polling service, alarm, or renderer start. */
class ShizukuRecoveryNotification(private val context: Context) {
    private val prefs = context.getSharedPreferences("hilight", Context.MODE_PRIVATE)
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val _enabled = MutableStateFlow(prefs.getBoolean("notifyShizukuLoss", false))
    val enabled = _enabled.asStateFlow()

    fun setEnabled(enabled: Boolean) {
        _enabled.value = enabled
        prefs.edit().putBoolean("notifyShizukuLoss", enabled).apply()
        if (!enabled) clear()
    }

    fun forgetConnection() {
        prefs.edit().remove("lastWorkingTransport").apply()
        clear()
    }

    fun observe(masterEnabled: Boolean, selected: Transport, active: Transport,
                connected: Boolean, state: ShizukuBackend.State, rootStarting: Boolean) {
        if (connected) {
            if (prefs.getString("lastWorkingTransport", null) != active.name) {
                prefs.edit().putString("lastWorkingTransport", active.name).apply()
            }
            clear()
            return
        }
        if (!masterEnabled || !_enabled.value || selected !in setOf(Transport.AUTO, Transport.SHIZUKU)) {
            clear()
            return
        }
        if (shouldWarnAboutShizuku(_enabled.value, masterEnabled,
                prefs.getString("lastWorkingTransport", null), selected,
                shizukuUnavailableForNotice(state,
                    runCatching { Shizuku.pingBinder() }.getOrDefault(false)),
                connected, rootStarting)) show()
    }

    private fun clear() {
        if (prefs.getBoolean("shizukuLossNotified", false)) {
            manager.cancel(RECOVERY_ID)
            prefs.edit().putBoolean("shizukuLossNotified", false).apply()
        }
        if (prefs.getBoolean("shizukuRecoveryJobPending", false)) {
            context.getSystemService(JobScheduler::class.java).cancel(RECOVERY_ID)
            prefs.edit().putBoolean("shizukuRecoveryJobPending", false).apply()
        }
    }

    private fun show() {
        if (prefs.getBoolean("shizukuLossNotified", false) || !manager.areNotificationsEnabled()) return
        manager.createNotificationChannel(NotificationChannel(SHIZUKU_RECOVERY_CHANNEL,
            context.getString(R.string.shizuku_recovery_channel), NotificationManager.IMPORTANCE_DEFAULT))
        if (manager.getNotificationChannel(SHIZUKU_RECOVERY_CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE) return
        val intent = Intent(context, MainActivity::class.java).setAction(OPEN_SETUP_ACTION)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending = PendingIntent.getActivity(context, RECOVERY_ID, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val body = context.getString(R.string.shizuku_recovery_body)
        runCatching {
            manager.notify(RECOVERY_ID, Notification.Builder(context, SHIZUKU_RECOVERY_CHANNEL)
                .setSmallIcon(R.drawable.hilight_logo)
                .setContentTitle(context.getString(R.string.shizuku_recovery_title))
                .setContentText(body).setStyle(Notification.BigTextStyle().bigText(body))
                .setContentIntent(pending).setAutoCancel(true).setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_STATUS).setLocalOnly(true).build())
            prefs.edit().putBoolean("shizukuLossNotified", true).apply()
        }.onFailure { Log.w("HiLightRecovery", "could not post recovery notification", it) }
    }

    fun scheduleAfterBoot() {
        if (!eligibleAfterBoot()) return
        prefs.edit().putBoolean("shizukuLossNotified", false).apply()
        val result = context.getSystemService(JobScheduler::class.java).schedule(
            JobInfo.Builder(RECOVERY_ID, ComponentName(context, ShizukuRecoveryJob::class.java))
                .setMinimumLatency(60_000).setOverrideDeadline(300_000).build(),
        )
        prefs.edit().putBoolean("shizukuRecoveryJobPending", result == JobScheduler.RESULT_SUCCESS).apply()
    }

    fun checkAfterBoot() {
        prefs.edit().putBoolean("shizukuRecoveryJobPending", false).apply()
        // A fresh process receives Shizuku's binder through its provider. This check never asks
        // for root or Shizuku permission and never creates a privileged user service.
        if (eligibleAfterBoot() && !runCatching { Shizuku.pingBinder() }.getOrDefault(false)) show()
    }

    private fun eligibleAfterBoot(): Boolean = shouldWarnAboutShizuku(
        prefs.getBoolean("notifyShizukuLoss", false), prefs.getBoolean("enabled", false),
        prefs.getString("lastWorkingTransport", null),
        runCatching { Transport.valueOf(prefs.getString("transport", "AUTO")!!) }.getOrDefault(Transport.AUTO),
        unavailable = true, rendererConnected = false, rootStarting = false,
    )
}

class ShizukuRecoveryBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            ShizukuRecoveryNotification(context).scheduleAfterBoot()
        }
    }
}

class ShizukuRecoveryJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        ShizukuRecoveryNotification(this).checkAfterBoot()
        return false
    }
    override fun onStopJob(params: JobParameters): Boolean = false
}
