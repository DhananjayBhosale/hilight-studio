package com.hilight.studio

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleBackupTest {
    private val rule = AppRule(
        pkg = "com.example.chat", label = "Chat", keyword = "private phrase",
        conversationName = "Friend", conversationKey = "chat-17",
        excludedPackages = setOf("com.example.other"),
        look = Ambient(pattern = Pattern.CUSTOM, perLed = List(LED_COUNT) { 0xFF112233.toInt() }),
        repeatWhilePending = true,
    )

    @Test fun `backup preserves full rules and order with explicit private filters`() {
        val second = AppRule(pkg = AppRule.ANY_APP, label = "Any app")
        assertEquals(listOf(rule, second), RuleBackup.decode(RuleBackup.encode(listOf(rule, second))))
    }

    @Test fun `merge skips identical rules and keeps saved order without overwriting`() {
        val another = AppRule(pkg = "com.other", label = "Other")
        val merged = RuleBackup.merge(listOf(rule), listOf(rule, another))
        assertEquals(listOf(rule, another), merged.rules)
        assertEquals(1, merged.added)
        assertEquals(1, merged.skipped)
    }

    @Test fun `unchanged legacy backup skips an identity migrated saved rule`() {
        val saved = rule.copy(stableId = rule.id)
        val merged = RuleBackup.merge(listOf(saved), RuleBackup.decode(RuleBackup.encode(listOf(rule))))
        assertEquals(listOf(saved), merged.rules)
        assertEquals(0, merged.added)
        assertEquals(1, merged.skipped)
    }

    @Test fun `conflicting identity rejects entire merge`() {
        rejects { RuleBackup.merge(listOf(rule), listOf(rule.copy(brightness = 0.5f))) }
    }

    @Test fun `reject unsupported version malformed data and trailing content`() {
        val original = RuleBackup.encode(listOf(rule))
        rejects { RuleBackup.decode(JSONObject(original).put("version", 2).toString()) }
        rejects { RuleBackup.decode("not a backup") }
        rejects { RuleBackup.decode(original + "{}") }
        rejects { RuleBackup.decode(JSONObject(original).put("rules", "bad").toString()) }
    }

    @Test fun `reject duplicate IDs and over count before mutating saved rules`() {
        val objectRule = rule.toPrefsJson()
        rejects { RuleBackup.decode(envelope(JSONArray().put(objectRule).put(objectRule))) }
        rejects { RuleBackup.decode(envelope(JSONArray().apply { repeat(501) { put(objectRule) } })) }
    }

    @Test fun `reject oversized utf8 files`() {
        rejects { RuleBackup.decode("x".repeat(RuleBackup.MAX_BYTES + 1)) }
        rejects { RuleBackup.decode("界".repeat(RuleBackup.MAX_BYTES / 2)) }
    }

    @Test fun `reject unsafe numbers unknown patterns and coerced booleans`() {
        listOf(
            "durationMs" to -1, "durationMs" to 60_001, "durationMs" to 3000.5,
            "speedMs" to 0, "brightness" to 1.1, "brightness" to "0.5",
            "color" to 4294967296L, "enabled" to "true", "pattern" to "future-pattern",
            "trigger" to "UNKNOWN", "repeatIntervalMs" to 1,
            "repeatPulseMs" to 3_001, "stableId" to "",
        ).forEach { (key, value) ->
            rejects { RuleBackup.decode(envelope(JSONArray().put(rule.toPrefsJson().put(key, value)))) }
        }
    }

    @Test fun `reject malformed nested look instead of silently replacing it`() {
        val look = Ambient().toPrefsJson().put("perLed", JSONArray().put(1))
        rejects { RuleBackup.decode(envelope(JSONArray().put(rule.toPrefsJson().put("look", look)))) }
        rejects { RuleBackup.decode(envelope(JSONArray().put(rule.toPrefsJson().put("look", "bad")))) }
    }

    @Test fun `reject wrong package and oversized identifying text`() {
        listOf("pkg" to "../path", "keyword" to "a".repeat(4097), "label" to 15).forEach { (key, value) ->
            rejects { RuleBackup.decode(envelope(JSONArray().put(rule.toPrefsJson().put(key, value)))) }
        }
    }

    private fun envelope(rules: JSONArray) = JSONObject().put("format", "hilight-rules")
        .put("version", 1).put("rules", rules).toString()

    private fun rejects(block: () -> Unit) {
        val failure = runCatching(block).exceptionOrNull()
        assertTrue("Expected rejection, got $failure", failure is IllegalArgumentException)
    }
}
