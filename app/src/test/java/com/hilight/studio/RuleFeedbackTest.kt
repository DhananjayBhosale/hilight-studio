package com.hilight.studio

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RuleFeedbackTest {
    private val any = AppRule(AppRule.ANY_APP, "Any app")
    private val app = AppRule("chat.app", "Chat")

    @Test fun `saved look retains every renderer setting after persistence`() {
        val look = Ambient(
            pattern = Pattern.GRADIENT, color = 0xFFFF0000.toInt(),
            secondColor = 0xFF0000FF.toInt(), perLed = List(LED_COUNT) { it + 1 },
            brightness = 0.42f, speedMs = 3421, rainbowSpread = false,
            randomIntervalMs = 3210, randomPerLed = false, randomSmooth = false,
            randomSaturation = 0.3f, rotateMs = 4500,
        )
        val saved = AppRule.fromJson(app.withLook(look).toPrefsJson())
        assertEquals(look, saved.effectiveLook())
        assertEquals(look.copy(pattern = Pattern.CUSTOM),
            AppRule.fromJson(saved.copy(pattern = Pattern.CUSTOM).toPrefsJson()).effectiveLook())
        assertEquals(look.copy(brightness = 0.8f, color = 5),
            saved.copy(brightness = 0.8f, color = 5).effectiveLook())
    }

    @Test fun `old rules keep existing behavior and new options roundtrip`() {
        val old = AppRule.fromJson(JSONObject().put("pkg", "chat.app"))
        assertFalse(old.ignoreSilent)
        assertFalse(old.repeatWhilePending)
        assertTrue(old.excludedPackages.isEmpty())
        assertNull(old.look)
        val configured = any.copy(ignoreSilent = true, excludedPackages = setOf("chat.app"),
            repeatWhilePending = true, repeatIntervalMs = 30_000)
        assertEquals(configured, AppRule.fromJson(configured.toPrefsJson()))
        assertEquals(5_000, AppRule.fromJson(configured.toPrefsJson().put("repeatIntervalMs", -1)).repeatIntervalMs)
        assertEquals(60_000, AppRule.fromJson(configured.toPrefsJson().put("repeatIntervalMs", Int.MAX_VALUE)).repeatIntervalMs)
    }

    @Test fun `excluded apps skip catch all but retain their explicit rule`() {
        val excluded = any.copy(excludedPackages = setOf(app.pkg))
        val info = MessageInfo(pkg = app.pkg)
        assertNull(ConversationMatch.resolve(listOf(excluded), info))
        assertEquals(app, ConversationMatch.resolve(listOf(excluded, app), info))
        assertEquals(excluded, ConversationMatch.resolve(listOf(excluded), MessageInfo("other.app")))
    }

    @Test fun `silent filtering does not leak to a less specific rule`() {
        val silent = MessageInfo(pkg = app.pkg, isSilent = true)
        assertEquals(any, ConversationMatch.resolve(listOf(any), silent))
        assertNull(ConversationMatch.resolve(listOf(any.copy(ignoreSilent = true)), silent))
        assertNull(ConversationMatch.resolve(listOf(any, app.copy(ignoreSilent = true)), silent))
        val chat = app.copy(conversationKey = "alice", conversationName = "Alice", ignoreSilent = true)
        assertNull(ConversationMatch.resolve(listOf(any, app, chat), silent.copy(shortcutId = "alice")))
        assertEquals(chat, ConversationMatch.resolve(listOf(any, app, chat), silent.copy(shortcutId = "alice", isSilent = false)))
    }
}
