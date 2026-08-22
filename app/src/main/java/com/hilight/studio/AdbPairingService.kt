package com.hilight.studio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.app.Service
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class PairingPhase {
    OFF,
    SEARCHING,
    WAITING_FOR_CODE,
    PAIRING,
    DONE,
    FAILED,
}

class AdbPairingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var searchJob: Job? = null
    private var target: PairingTarget? = null
    private var deadline = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resumed = phaseFlow.value.takeIf { it != PairingPhase.OFF } ?: PairingPhase.SEARCHING
        startForeground(NOTIFICATION_ID, notification(resumed, detailFlow.value))
        when (intent?.action) {
            ACTION_REPLY -> onCode(RemoteInput.getResultsFromIntent(intent)?.getString(KEY_CODE))
            ACTION_STOP -> {
                finish(PairingPhase.OFF, null)
                return START_NOT_STICKY
            }

            else -> beginSearch()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        if (phaseFlow.value != PairingPhase.DONE) phaseFlow.value = PairingPhase.OFF
        super.onDestroy()
    }

    private fun beginSearch() {
        if (searchJob?.isActive == true) return
        deadline = SystemClock.elapsedRealtime() + SEARCH_WINDOW_MS
        phaseFlow.value = PairingPhase.SEARCHING
        detailFlow.value = null
        update(PairingPhase.SEARCHING, null)
        searchJob = scope.launch {
            while (SystemClock.elapsedRealtime() < deadline) {
                val found = AdbAccess.findPairingTarget(this@AdbPairingService, SEARCH_STEP_MS)
                if (found != null) {
                    target = found
                    phaseFlow.value = PairingPhase.WAITING_FOR_CODE
                    update(PairingPhase.WAITING_FOR_CODE, null)
                    return@launch
                }
                delay(500)
            }
            finish(PairingPhase.FAILED, "no pairing dialog was found on this phone")
        }
    }

    private fun onCode(raw: String?) {
        val code = raw?.filter { it.isDigit() }.orEmpty()
        if (code.length != CODE_LENGTH) {
            detailFlow.value = "the pairing code is six digits"
            update(PairingPhase.WAITING_FOR_CODE, detailFlow.value)
            return
        }
        val where = target
        if (where == null) {
            beginSearch()
            detailFlow.value = "still looking for the pairing dialog"
            update(PairingPhase.SEARCHING, detailFlow.value)
            return
        }
        searchJob?.cancel()
        phaseFlow.value = PairingPhase.PAIRING
        detailFlow.value = null
        update(PairingPhase.PAIRING, null)
        scope.launch {
            val fresh = AdbAccess.findPairingTarget(this@AdbPairingService, RECHECK_MS) ?: where
            target = fresh
            val ok = AdbAccess.pairAndConnect(this@AdbPairingService, fresh, code)
            if (ok) {
                finish(PairingPhase.DONE, null)
            } else {
                target = null
                finish(PairingPhase.FAILED, AdbAccess.detail.value ?: "pairing was refused")
            }
        }
    }

    private fun finish(phase: PairingPhase, why: String?) {
        phaseFlow.value = phase
        detailFlow.value = why
        val keepNotification = phase == PairingPhase.DONE || phase == PairingPhase.FAILED
        if (keepNotification) {
            notify(notification(phase, why))
            stopForeground(STOP_FOREGROUND_DETACH)
        } else {
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
        stopSelf()
    }

    private fun update(phase: PairingPhase, why: String?) {
        detailFlow.value = why
        notify(notification(phase, why))
    }

    private fun notify(n: Notification) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, n)
    }

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "HiLight setup", NotificationManager.IMPORTANCE_HIGH)
        )
    }

    private fun notification(phase: PairingPhase, why: String?): Notification {
        val builder = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.hilight_logo)
            .setContentTitle(title(phase))
            .setContentText(why ?: body(phase))
            .setStyle(Notification.BigTextStyle().bigText(why ?: body(phase)))
            .setOnlyAlertOnce(phase != PairingPhase.WAITING_FOR_CODE)
            .setOngoing(phase != PairingPhase.DONE && phase != PairingPhase.FAILED)
        if (phase == PairingPhase.WAITING_FOR_CODE) {
            builder.addAction(replyAction())
        }
        if (phase != PairingPhase.DONE) {
            builder.addAction(
                Notification.Action.Builder(null, "Cancel", servicePendingIntent(ACTION_STOP, 2))
                    .build()
            )
        }
        return builder.build()
    }

    private fun replyAction(): Notification.Action {
        val input = RemoteInput.Builder(KEY_CODE)
            .setLabel("Six-digit pairing code")
            .build()
        return Notification.Action.Builder(null, "Enter code", servicePendingIntent(ACTION_REPLY, 1))
            .addRemoteInput(input)
            .setAllowGeneratedReplies(false)
            .build()
    }

    private fun servicePendingIntent(action: String, request: Int): PendingIntent {
        val intent = Intent(this, AdbPairingService::class.java).setAction(action)
        return PendingIntent.getForegroundService(
            this,
            request,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
    }

    private fun title(phase: PairingPhase): String = when (phase) {
        PairingPhase.WAITING_FOR_CODE -> "Enter the pairing code"
        PairingPhase.PAIRING -> "Pairing"
        PairingPhase.DONE -> "HiLight is connected"
        PairingPhase.FAILED -> "Pairing did not finish"
        else -> "Waiting for the pairing dialog"
    }

    private fun body(phase: PairingPhase): String = when (phase) {
        PairingPhase.WAITING_FOR_CODE ->
            "Type the six digits shown in the Wireless debugging dialog, then send."

        PairingPhase.PAIRING -> "Starting the renderer."
        PairingPhase.DONE -> "The LEDs are ready. This is remembered across reboots."
        PairingPhase.FAILED -> "Open HiLight Studio to try again."
        else ->
            "In Wireless debugging, tap Pair device with pairing code and leave that dialog open."
    }

    companion object {
        private const val CHANNEL = "adb_pairing"
        private const val NOTIFICATION_ID = 8
        private const val KEY_CODE = "hilight_pairing_code"
        private const val CODE_LENGTH = 6
        private const val SEARCH_WINDOW_MS = 300_000L
        private const val SEARCH_STEP_MS = 8_000L
        private const val RECHECK_MS = 6_000L

        const val ACTION_REPLY = "com.hilight.studio.action.PAIR_REPLY"
        const val ACTION_STOP = "com.hilight.studio.action.PAIR_STOP"

        private val phaseFlow = MutableStateFlow(PairingPhase.OFF)
        val phase: StateFlow<PairingPhase> = phaseFlow.asStateFlow()

        private val detailFlow = MutableStateFlow<String?>(null)
        val detail: StateFlow<String?> = detailFlow.asStateFlow()

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, AdbPairingService::class.java))
        }
    }
}
