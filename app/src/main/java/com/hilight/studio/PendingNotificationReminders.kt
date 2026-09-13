package com.hilight.studio

internal class PendingNotificationReminders {
    data class Entry(val key: String, val ruleId: String, val postedAtMs: Long, val dueAtMs: Long)
    private val entries = LinkedHashMap<String, Entry>()

    fun posted(key: String, ruleId: String, postedAtMs: Long, nowMs: Long, intervalMs: Int) {
        if (key.isEmpty()) return
        entries.remove(key)
        entries[key] = Entry(key, ruleId, postedAtMs, nowMs + intervalMs.coerceIn(5_000, 60_000))
        while (entries.size > 200) entries.remove(entries.keys.first())
    }

    fun latest(): Entry? = entries.values.lastOrNull()
    fun remove(key: String) { entries.remove(key) }
    fun retain(keys: Set<String>) { entries.keys.retainAll(keys) }
    fun clear() { entries.clear() }
    fun defer(key: String, nowMs: Long, intervalMs: Int) {
        entries[key]?.let { entries[key] = it.copy(dueAtMs = nowMs + intervalMs.coerceIn(5_000, 60_000)) }
    }
}

internal fun isIncomingCallType(callType: Int): Boolean = callType == 1

internal fun isSilentNotification(importance: Int?, channelHasSound: Boolean, channelVibrates: Boolean): Boolean =
    importance != null && importance >= 0 && (importance < 3 || (!channelHasSound && !channelVibrates))
