package com.hilight.studio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleDraftsTest {

    @Test
    fun `new app starts with a notification rule`() {
        val draft = nextWholeAppRule("com.example", "Example", emptyList())

        assertEquals(Trigger.NOTIFICATION, draft?.trigger)
    }

    @Test
    fun `second whole app rule uses the missing foreground slot`() {
        val notification = rule(trigger = Trigger.NOTIFICATION)
        val draft = nextWholeAppRule(notification.pkg, notification.label, listOf(notification))

        assertEquals(Trigger.FOREGROUND, draft?.trigger)
        assertTrue(listOf(notification).none { it.id == draft?.id })
    }

    @Test
    fun `foreground only app receives the missing notification slot`() {
        val foreground = rule(trigger = Trigger.FOREGROUND)

        assertEquals(
            Trigger.NOTIFICATION,
            nextWholeAppRule(foreground.pkg, foreground.label, listOf(foreground))?.trigger,
        )
    }

    @Test
    fun `additional whole app rules have independent identities`() {
        val notification = rule(trigger = Trigger.NOTIFICATION)
        val foreground = notification.copy(trigger = Trigger.FOREGROUND)

        val next = nextWholeAppRule(notification.pkg, notification.label, listOf(notification, foreground))!!
        assertNotEquals(notification.id, next.id)
        assertNotEquals(foreground.id, next.id)
        assertEquals(Trigger.NOTIFICATION, next.trigger)
    }

    @Test
    fun `conversation and other app rules do not occupy a whole app slot`() {
        val conversation = rule().copy(conversationName = "Sujay")
        val other = rule(pkg = "com.other")

        assertEquals(
            Trigger.NOTIFICATION,
            nextWholeAppRule("com.example", "Example", listOf(conversation, other))?.trigger,
        )
    }

    @Test
    fun `catch all uses the same two slot contract`() {
        val notification = rule(pkg = AppRule.ANY_APP)

        assertEquals(
            Trigger.FOREGROUND,
            nextWholeAppRule(AppRule.ANY_APP, "Any app", listOf(notification))?.trigger,
        )
    }

    @Test
    fun `copy keeps portable settings and clears source notification identity`() {
        val source = rule(trigger = Trigger.FOREGROUND).copy(
            enabled = false,
            pattern = Pattern.WAVE,
            randomColor = true,
            color = 0xFF123456.toInt(),
            durationMs = 23_000,
            speedMs = 337,
            brightness = 0.42f,
            onlyWhenScreenOff = true,
            onlyWhenFaceDown = true,
            keyword = "invoice",
        )

        val copy = copyWholeAppRule(source, "com.target", "Target")

        assertEquals("com.target", copy.pkg)
        assertEquals("Target", copy.label)
        assertFalse(copy.enabled)
        assertEquals(source.pattern, copy.pattern)
        assertEquals(source.randomColor, copy.randomColor)
        assertEquals(source.color, copy.color)
        assertEquals(source.durationMs, copy.durationMs)
        assertEquals(source.speedMs, copy.speedMs)
        assertEquals(source.brightness, copy.brightness)
        assertEquals(source.onlyWhenScreenOff, copy.onlyWhenScreenOff)
        assertEquals(source.onlyWhenFaceDown, copy.onlyWhenFaceDown)
        assertEquals(source.trigger, copy.trigger)
        assertEquals("", copy.keyword)
        assertNull(copy.conversationKey)
        assertNull(copy.conversationName)
        assertFalse(copy.includeGroups)
        assertFalse(copy.conversationIsGroup)
        assertNotEquals(source.id, copy.id)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `conversation rules cannot be copied between apps`() {
        copyWholeAppRule(rule().copy(conversationName = "Sujay"), "com.target", "Target")
    }

    @Test
    fun `new copy warns even when its value equals the target rule`() {
        val target = rule()

        assertTrue(replacesExistingRule(listOf(target), target, target, isNew = true))
        assertFalse(replacesExistingRule(listOf(target), target, target, isNew = false))
    }

    @Test
    fun `editing into another occupied trigger warns`() {
        val notification = rule(trigger = Trigger.NOTIFICATION)
        val foreground = notification.copy(trigger = Trigger.FOREGROUND)

        assertTrue(
            replacesExistingRule(
                existing = listOf(notification, foreground),
                candidate = notification.copy(trigger = Trigger.FOREGROUND),
                openedRule = notification,
                isNew = false,
            )
        )
    }

    @Test fun `legacy identity survives editing trigger and learning chat key`() {
        val legacy = rule().copy(conversationName = "Alex")
        val migrated = legacy.withStableIdentity()
        assertEquals(legacy.id, migrated.id)
        assertEquals(migrated.id, migrated.copy(conversationKey = "learned", trigger = Trigger.FOREGROUND).id)
        assertEquals(migrated, AppRule.fromJson(migrated.toPrefsJson()))
    }

    @Test fun `app color copy resolves target while keeping manual fallback`() {
        val source = rule().copy(useAppColor = true, stableId = "source", color = 0xff123456.toInt())
        val copied = copyWholeAppRule(source, "target.app", "Target")
        assertTrue(copied.useAppColor)
        assertEquals(source.color, copied.color)
        assertEquals("target.app", copied.pkg)
        assertNotEquals(source.id, copied.id)
    }

    private fun rule(
        pkg: String = "com.example",
        trigger: Trigger = Trigger.NOTIFICATION,
    ) = AppRule(pkg = pkg, label = "Example", trigger = trigger)
}
