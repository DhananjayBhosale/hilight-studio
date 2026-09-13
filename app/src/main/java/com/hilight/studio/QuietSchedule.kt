package com.hilight.studio

/** A window belongs to the day on which it starts. Equal times mean no quiet window. */
data class QuietDay(val enabled: Boolean = true, val startMin: Int = 23 * 60, val endMin: Int = 7 * 60)

/** Monday is index 0. An overnight window continues even if the following day is disabled. */
internal fun isQuietAt(days: List<QuietDay>, day: Int, minute: Int): Boolean {
    if (days.size != 7 || day !in 0..6 || minute !in 0..1439) return false
    val today = days[day]
    val yesterday = days[(day + 6) % 7]
    val todayQuiet = today.enabled && when {
        today.startMin < today.endMin -> minute in today.startMin until today.endMin
        today.startMin > today.endMin -> minute >= today.startMin
        else -> false
    }
    val carriedQuiet = yesterday.enabled && yesterday.startMin > yesterday.endMin && minute < yesterday.endMin
    return todayQuiet || carriedQuiet
}

internal fun encodeQuietDays(days: List<QuietDay>): String = days.joinToString(";") {
    "${if (it.enabled) 1 else 0},${it.startMin},${it.endMin}"
}

internal fun decodeQuietDays(value: String?, fallback: QuietDay): List<QuietDay> {
    val parsed = value?.split(';')?.map { token ->
        val values = token.split(',')
        val enabled = values.getOrNull(0)?.toIntOrNull()
        val start = values.getOrNull(1)?.toIntOrNull()
        val end = values.getOrNull(2)?.toIntOrNull()
        if (values.size != 3 || enabled !in 0..1 || start == null || start !in 0..1439 ||
            end == null || end !in 0..1439) null
        else QuietDay(enabled == 1, start, end)
    }
    return if (parsed?.size == 7 && parsed.all { it != null }) parsed.filterNotNull()
    else List(7) { fallback }
}

/** Ignore only the pose the user is about to change during the countdown. */
internal fun GuardState.scheduledTestSuppressionReason(): Suppression? =
    copy(faceDownOnly = false).alertSuppression()
