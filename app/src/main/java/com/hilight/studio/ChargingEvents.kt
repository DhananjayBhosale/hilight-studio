package com.hilight.studio

import android.content.Intent
import android.os.BatteryManager

/**
 * Which charging moments deserve a gauge, worked out from the raw battery readings.
 *
 * Pure and Android-free for the same reason as [GuardState]: the once-per-session rules are the part
 * that goes wrong quietly. Without them a phone sitting at 100% would flash every time Android
 * re-broadcast its level, and launching the app on the charger would read as plugging it in.
 *
 * [pluggedNow] and [fullNow] prime the tracker with the state at construction, so the first reading
 * after start-up is a no-op rather than a plug-in.
 */
class ChargingEvents(pluggedNow: Boolean = false, fullNow: Boolean = false) {

    enum class Event { PLUGGED_IN, FULL }

    var plugged: Boolean = pluggedNow
        private set

    /** Whether the full moment has already been announced in this charging session. */
    private var fullAnnounced = pluggedNow && fullNow

    /**
     * Folds one reading in and says which moment, if any, it marks.
     *
     * A plug-in fires once per session. Plugging in a phone that is already full is that session's
     * full moment as well, so it gets the plug-in gauge (which shows full) and nothing later. The
     * full moment fires on the first reading that reports full, once, and unplugging resets both.
     */
    fun onReading(levelPct: Int, plugged: Boolean, full: Boolean): Event? {
        val isFull = full || levelPct >= 100
        if (plugged != this.plugged) {
            this.plugged = plugged
            fullAnnounced = plugged && isFull
            return if (plugged) Event.PLUGGED_IN else null
        }
        if (!plugged || fullAnnounced || !isFull) return null
        fullAnnounced = true
        return Event.FULL
    }
}

/** One ACTION_BATTERY_CHANGED broadcast, reduced to what the charging gauge needs. */
data class BatteryReading(val levelPct: Int, val plugged: Boolean, val full: Boolean) {
    companion object {
        fun from(i: Intent?): BatteryReading {
            if (i == null) return BatteryReading(levelPct = 100, plugged = false, full = false)
            val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            val pct = if (level < 0 || scale <= 0) 100 else (level * 100 / scale).coerceIn(0, 100)
            return BatteryReading(
                levelPct = pct,
                plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0,
                full = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ==
                    BatteryManager.BATTERY_STATUS_FULL,
            )
        }
    }
}
