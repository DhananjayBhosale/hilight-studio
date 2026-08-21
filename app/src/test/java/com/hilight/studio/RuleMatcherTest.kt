package com.hilight.studio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RuleMatcherTest {
    private fun rule(pkg: String, keyword: String = "") =
        AppRule(pkg = pkg, label = pkg, keyword = keyword)

    @Test
    fun `two same-app contacts match independently`() {
        val first = rule("chat", "Alice")
        val second = rule("chat", "Bob")
        val rules = listOf(first, second)

        assertEquals(first, RuleMatcher.notificationRuleFor(rules, "chat", "Alice: hello"))
        assertEquals(second, RuleMatcher.notificationRuleFor(rules, "chat", "Bob: hello"))
    }

    @Test
    fun `specific rule beats an earlier blank fallback`() {
        val blank = rule("mail")
        val specific = rule("mail", "invoice")

        assertEquals(
            specific,
            RuleMatcher.notificationRuleFor(listOf(blank, specific), "mail", "New invoice"),
        )
    }

    @Test
    fun `app blank rule is the fallback when specific rule misses`() {
        val blank = rule("mail")
        val specific = rule("mail", "invoice")

        assertEquals(
            blank,
            RuleMatcher.notificationRuleFor(listOf(specific, blank), "mail", "New message"),
        )
    }

    @Test
    fun `app rules return no match without a matching condition or fallback`() {
        assertNull(
            RuleMatcher.notificationRuleFor(listOf(rule("mail", "invoice")), "mail", "New message"),
        )
    }

    @Test
    fun `global catch-all handles an app without exact rules`() {
        val global = rule(AppRule.ANY_APP)

        assertEquals(global, RuleMatcher.notificationRuleFor(listOf(global), "chrome", "New mail"))
    }

    @Test
    fun `enabled exact rules block global fallback even when they miss`() {
        val exact = rule("mail", "invoice")
        val global = rule(AppRule.ANY_APP)

        assertNull(RuleMatcher.notificationRuleFor(listOf(exact, global), "mail", "New message"))
    }

    @Test
    fun `disabled rules are ignored`() {
        val disabled = rule("bethany", "Bethany").copy(enabled = false)

        assertNull(RuleMatcher.notificationRuleFor(listOf(disabled), "bethany", "Bethany: hello"))
    }

    @Test
    fun `keyword matching is case insensitive`() {
        val matching = rule("mail", "INVOICE")

        assertEquals(matching, RuleMatcher.notificationRuleFor(listOf(matching), "mail", "new invoice"))
    }

    @Test
    fun `surrounding keyword whitespace is trimmed`() {
        val matching = rule("mail", "  invoice  ")

        assertEquals(matching, RuleMatcher.notificationRuleFor(listOf(matching), "mail", "new invoice"))
    }
}
