package com.hilight.studio

import org.junit.Assert.*
import org.junit.Test

class GroupedRulesTest {
    private val fallback = AppRule("example.app", "Example", stableId = "fallback")
    private val invoice = fallback.copy(stableId = "invoice", keyword = "invoice")
    private val urgent = fallback.copy(stableId = "urgent", keyword = "urgent")

    @Test fun nonmatchingTextRuleDoesNotSwallowFallback() {
        val info = MessageInfo(pkg = "example.app", text = "hello")
        assertEquals(fallback, resolveNotificationRule(listOf(invoice, fallback), info))
    }

    @Test fun textConditionsWinOverFallbackAndOrderBreaksEqualSpecificityTies() {
        val info = MessageInfo(pkg = "example.app", text = "URGENT invoice")
        assertEquals(invoice, resolveNotificationRule(listOf(fallback, invoice, urgent), info))
        assertEquals(urgent, resolveNotificationRule(listOf(fallback, urgent, invoice), info))
    }

    @Test fun disabledRuleAndCatchAllExclusionStillApply() {
        val any = fallback.copy(pkg = AppRule.ANY_APP, stableId = "any", excludedPackages = setOf("example.app"))
        val info = MessageInfo(pkg = "example.app", text = "invoice")
        assertNull(resolveNotificationRule(listOf(invoice.copy(enabled = false), any), info))
    }

    @Test fun keyedConversationKeepsPrecedenceAndTextConditionIsRequired() {
        val chat = fallback.copy(stableId = "chat", conversationKey = "chat-key", keyword = "invoice")
        val info = MessageInfo(pkg = "example.app", shortcutId = "chat-key", text = "invoice")
        assertEquals(chat, resolveNotificationRule(listOf(invoice, fallback, chat), info))
        assertEquals(fallback, resolveNotificationRule(listOf(chat, fallback), info.copy(text = "hello")))
    }
}
