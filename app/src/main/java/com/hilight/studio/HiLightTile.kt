package com.hilight.studio

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

class HiLightTile : TileService() {
    private val store by lazy { Store.get(this) }

    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    override fun onStartListening() {
        super.onStartListening()
        store.refreshStatus()
        render()

        main.postDelayed({
            store.refreshStatus()
            render()
        }, 900)
    }

    override fun onClick() {
        super.onClick()
        store.refreshStatus()
        if (!store.status.value.alive) {
            openApp()
            return
        }
        store.setEnabled(!store.enabled.value)
        render()
    }

    private fun render() {
        val tile = qsTile ?: return
        val status = store.status.value
        val on = store.enabled.value

        tile.state = if (on && status.alive) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "HiLight"
        tile.subtitle = when {
            store.suppression.value != null -> store.suppression.value!!.short
            !status.alive -> "No renderer"
            !on -> "Off"
            status.resting -> "Resting"
            status.ambientHeld -> "Timed out"
            else -> store.ambient.value.pattern.label
        }
        tile.updateTile()
    }

    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pending = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        startActivityAndCollapse(pending)
    }

    companion object {
        fun refresh(ctx: android.content.Context) {
            runCatching {
                requestListeningState(ctx, ComponentName(ctx, HiLightTile::class.java))
            }
        }
    }
}
