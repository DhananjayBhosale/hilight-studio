package com.hilight.studio

data class GuardState(
    val screenOffOnly: Boolean = false,
    val screenOn: Boolean = false,
    val quietEnabled: Boolean = false,
    val quietDim: Boolean = false,
    val inQuietWindow: Boolean = false,
    val saverGuard: Boolean = true,
    val powerSaveMode: Boolean = false,
    val batteryGuard: Boolean = true,
    val batteryPct: Int = 100,
    val batteryMinPct: Int = Limits.BATTERY_DEFAULT_PCT,
) {
    fun suppression(): Suppression? {
        if (screenOffOnly && screenOn) return Suppression.SCREEN_ON
        return alertSuppression()
    }

    fun alertSuppression(): Suppression? {
        if (quietEnabled && !quietDim && inQuietWindow) return Suppression.QUIET_HOURS

        if (saverGuard && powerSaveMode) return Suppression.POWER_SAVER

        if (batteryGuard && batteryPct < batteryMinPct) return Suppression.LOW_BATTERY
        return null
    }
}
