package com.hilight.studio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            -> AdbReconnectService.start(context)
        }
    }
}

class AdbReconnectService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, notification())
        if (job?.isActive != true) job = scope.launch { run() }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun run() {
        Bridge.ensureFiles(this)
        val deadline = SystemClock.elapsedRealtime() + WINDOW_MS
        var wait = FIRST_GAP_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (!AdbAccess.paired(this)) break
            if (AdbAccess.wirelessDebuggingEnabled(this) && AdbAccess.connect(this)) break
            delay(wait)
            wait = (wait * 2).coerceAtMost(MAX_GAP_MS)
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "HiLight reconnect", NotificationManager.IMPORTANCE_MIN)
        )
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("HiLight Studio")
            .setContentText("Restoring renderer access")
            .setSmallIcon(R.drawable.hilight_logo)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL = "adb_reconnect"
        private const val NOTIFICATION_ID = 9
        private const val WINDOW_MS = 180_000L
        private const val FIRST_GAP_MS = 5_000L
        private const val MAX_GAP_MS = 30_000L

        fun start(ctx: Context) {
            if (!AdbAccess.paired(ctx)) return
            runCatching {
                ctx.startForegroundService(Intent(ctx, AdbReconnectService::class.java))
            }
        }
    }
}
