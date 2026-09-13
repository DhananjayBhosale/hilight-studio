package com.hilight.studio

/**
 * Everything the guards need to decide whether the array may light, with no Context attached.
 *
 * Pulled out of [Store] for the same reason the renderer's safety limiter is a class of its own: this
 * is four independent rules in a priority order, the kind of thing that breaks quietly and shows up
 * as "the light just doesn't work sometimes".
 *
 * [batteryPct] is the *effective* level — a charging phone reports 100, because there is no reason to
 * hold the array back on the charger.
 */
data class GuardState(
    val screenOffOnly: Boolean = false,
    val screenOn: Boolean = false,
    val faceDownOnly: Boolean = false,
    val faceDown: Boolean = false,
    val quietEnabled: Boolean = false,
    val quietDim: Boolean = false,
    val inQuietWindow: Boolean = false,
    val saverGuard: Boolean = true,
    val powerSaveMode: Boolean = false,
    val batteryGuard: Boolean = true,
    val batteryPct: Int = 100,
    val batteryMinPct: Int = Limits.BATTERY_DEFAULT_PCT,
) {
    /** Why the array must stay dark right now, or null if it may light. */
    fun suppression(): Suppression? {
        if (screenOffOnly && screenOn) return Suppression.SCREEN_ON
        return alertSuppression()
    }

    /**
     * Why a per-app notification flash must not fire right now, or null if it may.
     *
     * Everything [suppression] covers except the global "only while the screen is off" switch. That
     * switch is about the always-on look: it says when a glow is worth having, not that the phone
     * should stop telling you things. Letting it silence alerts made every per-app colour dead for
     * as long as the screen was on, and left each rule's own screen-off checkbox — the control that
     * actually means "only flash when I can't see the screen" — with nothing to do.
     *
     * Quiet hours and the two battery rules do still hold: being dark at 3am, or on a nearly flat
     * phone, is the entire point of them.
     */
    fun alertSuppression(): Suppression? {
        if (faceDownOnly && !faceDown) return Suppression.NOT_FACE_DOWN
        // a dimmed quiet window is not a suppression: it still lights, just far lower
        if (quietEnabled && !quietDim && inQuietWindow) return Suppression.QUIET_HOURS
        // Battery Saver is a direct request to stop spending power on extras, so it outranks the
        // level threshold and holds even on the charger.
        if (saverGuard && powerSaveMode) return Suppression.POWER_SAVER
        // strictly below, to match the "Pause below N%" label on the slider
        if (batteryGuard && batteryPct < batteryMinPct) return Suppression.LOW_BATTERY
        return null
    }
}

/** Manual previews intentionally bypass only the face-down gate, matching [outputSuppression]. */
internal fun GuardState.previewSuppressionReason(): Suppression? =
    copy(faceDownOnly = false).alertSuppression()

internal enum class SettingsSuppressionSection { GLOW, PAUSE }

/** Routes the live explanation to the settings group that owns the condition. */
internal fun Suppression.settingsSection(): SettingsSuppressionSection = when (this) {
    Suppression.SCREEN_ON, Suppression.NOT_FACE_DOWN -> SettingsSuppressionSection.GLOW
    Suppression.QUIET_HOURS, Suppression.POWER_SAVER, Suppression.LOW_BATTERY ->
        SettingsSuppressionSection.PAUSE
}

/** Manual finite previews bypass only the face-down gate; every power/safety guard still applies. */
internal fun GuardState.outputSuppression(
    hasTransientAlert: Boolean,
    activeAlertSource: AlertSource?,
): Suppression? {
    val effective = if (activeAlertSource == AlertSource.PREVIEW) {
        copy(faceDownOnly = false)
    } else {
        this
    }
    return if (hasTransientAlert) effective.alertSuppression() else effective.suppression()
}
