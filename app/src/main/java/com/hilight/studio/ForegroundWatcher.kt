package com.hilight.studio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper

class ForegroundWatcher : Service() {
    private lateinit var thread: HandlerThread
    private lateinit var handler: Handler
    private val main = Handler(Looper.getMainLooper())
    private val store by lazy { Store.get(this) }
    private var lastPkg: String? = null

    private var stopped = false

    private val tick = object : Runnable {
        override fun run() {
            val pkg = currentForegroundPackage()
            if (pkg != null && pkg != lastPkg) {
                lastPkg = pkg

                main.post {
                    if (!stopped) {
                        val rule = store.ruleFor(pkg, Trigger.FOREGROUND)
                        store.setForegroundOverride(if (rule != null) pkg else null, rule)
                    }
                }
            }
            handler.postDelayed(this, POLL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        thread = HandlerThread("fg-watch").also { it.start() }
        handler = Handler(thread.looper)
        startForeground(1, notification())
        handler.post(tick)
    }

    override fun onDestroy() {
        stopped = true
        handler.removeCallbacksAndMessages(null)
        thread.quitSafely()
        main.post { store.setForegroundOverride(null, null) }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    private fun currentForegroundPackage(): String? {
        val usm = getSystemService(UsageStatsManager::class.java) ?: return null
        val now = System.currentTimeMillis()
        val events = usm.queryEvents(now - 10_000, now)
        var pkg: String? = null
        val e = android.app.usage.UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            if (e.eventType == android.app.usage.UsageEvents.Event.ACTIVITY_RESUMED) pkg = e.packageName
        }
        return pkg
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "HiLight app watcher", NotificationManager.IMPORTANCE_MIN)
        )
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("HiLight Studio")
            .setContentText("Watching for apps with light rules")
            .setSmallIcon(R.drawable.hilight_logo)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL = "fg_watch"
        private const val POLL_MS = 1000L

        fun syncRunning(ctx: Context, rules: List<AppRule>, enabled: Boolean) {
            val needed = enabled && rules.any { it.enabled && it.trigger == Trigger.FOREGROUND }
            val intent = Intent(ctx, ForegroundWatcher::class.java)
            if (needed) ctx.startForegroundService(intent) else ctx.stopService(intent)
        }

        fun hasUsageAccess(ctx: Context): Boolean {
            val usm = ctx.getSystemService(UsageStatsManager::class.java) ?: return false
            val now = System.currentTimeMillis()
            return usm.queryEvents(now - 60_000, now).hasNextEvent()
        }
    }
}
