package com.hilight.studio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val WHATSAPP = "com.whatsapp"
private const val TELEGRAM = "org.telegram.messenger"
private const val DISCORD = "com.discord"

/** The Indian flag, escaped so a file re-encoding cannot quietly change what is being asserted. */
private const val FLAG = "\uD83C\uDDEE\uD83C\uDDF3"

/** A single smiling face, for the name that is nothing but an emoji. */
private const val SMILEY = "\uD83D\uDE42"

/**
 * The per-conversation matcher, exercised without a device.
 *
 * Every case is a hand-written notification put in front of a hand-written rule, which is the whole
 * reason the ladder lives in Conversation.kt rather than inside the listener service. The cases that
 * matter most are the ones where a rule must *not* fire: a light that comes on for the wrong chat is
 * indistinguishable from a light that is broken.
 */
class ConversationMatchTest {

    /** A notification as the listener would hand it over. */
    private fun notif(
        pkg: String = WHATSAPP,
        shortcutId: String? = null,
        sender: String? = null,
        conversationTitle: String? = null,
        title: String? = null,
        text: String? = null,
        isGroupSummary: Boolean = false,
        isMessagingStyle: Boolean = true,
        messageStampMs: Long = 0L,
        postTimeMs: Long = 0L,
    ) = MessageInfo(
        pkg = pkg,
        notifKey = "0|$pkg|1|null|10123",
        shortcutId = shortcutId,
        sender = sender,
        conversationTitle = conversationTitle,
        title = title,
        text = text,
        isGroupSummary = isGroupSummary,
        isMessagingStyle = isMessagingStyle,
        messageStampMs = messageStampMs,
        postTimeMs = postTimeMs,
    )

    /** A rule scoped to one chat. */
    private fun convoRule(
        name: String? = null,
        key: String? = null,
        pkg: String = WHATSAPP,
        includeGroups: Boolean = false,
        enabled: Boolean = true,
        trigger: Trigger = Trigger.NOTIFICATION,
    ) = AppRule(
        pkg = pkg,
        label = name ?: key ?: pkg,
        enabled = enabled,
        trigger = trigger,
        conversationKey = key,
        conversationName = name,
        includeGroups = includeGroups,
    )

    /** A plain "anything from this app" rule. */
    private fun appRule(
        pkg: String = WHATSAPP,
        enabled: Boolean = true,
        trigger: Trigger = Trigger.NOTIFICATION,
    ) = AppRule(pkg = pkg, label = pkg, enabled = enabled, trigger = trigger)

    private fun textRule(
        text: String,
        pkg: String = WHATSAPP,
        enabled: Boolean = true,
    ) = AppRule(pkg = pkg, label = pkg, enabled = enabled, keyword = text)

    private fun catchAll(
        enabled: Boolean = true,
        trigger: Trigger = Trigger.NOTIFICATION,
    ) = AppRule(pkg = AppRule.ANY_APP, label = "Any app", enabled = enabled, trigger = trigger)

    // ---------------------------------------------------------------- normalise

    @Test
    fun `case and surrounding whitespace fall away`() {
        assertEquals("sujay", ConversationMatch.normalise("  SUJAY  "))
        assertEquals(ConversationMatch.normalise("Sujay"), ConversationMatch.normalise("sujay"))
    }

    @Test
    fun `an emoji in a contact name is not part of the name`() {
        // the reported case: a contact saved with a flag after the name, matched against a rule made
        // from the contact picker, which never carries the flag
        assertEquals(
            ConversationMatch.normalise("Sujay"),
            ConversationMatch.normalise("Sujay $FLAG"),
        )
    }

    @Test
    fun `brackets and punctuation are not part of the name`() {
        assertEquals(
            ConversationMatch.normalise("Sujay work"),
            ConversationMatch.normalise("Sujay (work)"),
        )
        assertEquals(
            ConversationMatch.normalise("Sujay Kumar"),
            ConversationMatch.normalise("Sujay-Kumar"),
        )
    }

    @Test
    fun `a number saved without a name keeps only its digits`() {
        assertEquals("91 98765 43210", ConversationMatch.normalise("+91 98765 43210"))
        // the same number written three ways, as three different apps would show it
        assertEquals(
            ConversationMatch.normalise("+91 98765 43210"),
            ConversationMatch.normalise("(+91) 98765-43210"),
        )
    }

    @Test
    fun `nothing at all normalises to nothing`() {
        assertEquals("", ConversationMatch.normalise(null))
        assertEquals("", ConversationMatch.normalise(""))
        assertEquals("", ConversationMatch.normalise("   "))
        // a name that is only an emoji is indistinguishable from a blank one after normalising, so
        // no rule can ever be written for it
        assertEquals("", ConversationMatch.normalise(SMILEY))
    }

    // ---------------------------------------------------------------- strength, by key

    @Test
    fun `a matching shortcut id is the strongest match`() {
        assertEquals(
            MatchStrength.KEY,
            ConversationMatch.strength(
                convoRule(name = "Sujay", key = "chat-sujay"),
                notif(shortcutId = "chat-sujay", sender = "Sujay"),
            ),
        )
    }

    @Test
    fun `a different shortcut id beats a matching name`() {
        // the case the whole ladder exists for: two chats with the same display name, which happens
        // with a personal and a work contact, or with two people of the same name. A key on both
        // sides settles it, and no amount of name similarity may override that.
        assertNull(
            ConversationMatch.strength(
                convoRule(name = "Sujay", key = "chat-sujay-personal"),
                notif(shortcutId = "chat-sujay-work", sender = "Sujay"),
            ),
        )
    }

    @Test
    fun `a rule with no key still matches by name`() {
        // rules made before the first sighting have no key; the notification's is simply ignored
        assertEquals(
            MatchStrength.NAME,
            ConversationMatch.strength(
                convoRule(name = "Sujay"),
                notif(shortcutId = "chat-sujay", sender = "Sujay"),
            ),
        )
    }

    @Test
    fun `a rule with a key falls back to the name when the notification has none`() {
        // apps drop the shortcutId on some notifications even when they usually set one
        assertEquals(
            MatchStrength.NAME,
            ConversationMatch.strength(
                convoRule(name = "Sujay", key = "chat-sujay"),
                notif(sender = "Sujay"),
            ),
        )
    }

    @Test
    fun `a key-only rule has no name to fall back to`() {
        assertNull(
            ConversationMatch.strength(
                convoRule(key = "chat-sujay"),
                notif(sender = "Sujay"),
            ),
        )
    }

    @Test
    fun `a plain app rule is never a conversation match`() {
        assertNull(ConversationMatch.strength(appRule(), notif(sender = "Sujay")))
        assertNull(ConversationMatch.strength(catchAll(), notif(sender = "Sujay")))
    }

    // ---------------------------------------------------------------- strength, by name

    @Test
    fun `a person rule ignores them speaking in a group`() {
        // otherwise "green for Sujay" lights up for every group he is in, which is the noisiest
        // possible way for the feature to be wrong
        assertNull(
            ConversationMatch.strength(
                convoRule(name = "Sujay", includeGroups = false),
                notif(sender = "Sujay", conversationTitle = "Team Rocket", title = "Team Rocket"),
            ),
        )
    }

    @Test
    fun `a person rule follows them into groups when asked`() {
        assertEquals(
            MatchStrength.NAME,
            ConversationMatch.strength(
                convoRule(name = "Sujay", includeGroups = true),
                notif(sender = "Sujay", conversationTitle = "Team Rocket", title = "Team Rocket"),
            ),
        )
    }

    @Test
    fun `a group rule matches whoever speaks in it`() {
        val message = notif(sender = "Amit", conversationTitle = "Team Rocket", title = "Team Rocket")
        // includeGroups is about following a person into groups, so it must not gate a rule that
        // names the group itself
        assertEquals(
            MatchStrength.NAME,
            ConversationMatch.strength(convoRule(name = "Team Rocket"), message),
        )
        assertEquals(
            MatchStrength.NAME,
            ConversationMatch.strength(convoRule(name = "Team Rocket", includeGroups = true), message),
        )
    }

    @Test
    fun `the notification title stands in for a missing sender`() {
        // Slack and Discord never adopted MessagingStyle, so the title is all there is
        assertEquals(
            MatchStrength.NAME,
            ConversationMatch.strength(
                convoRule(name = "Sujay", pkg = DISCORD),
                notif(pkg = DISCORD, title = "Sujay", isMessagingStyle = false),
            ),
        )
    }

    @Test
    fun `a discord style title matches by containment`() {
        assertEquals(
            MatchStrength.CONTAINS,
            ConversationMatch.strength(
                convoRule(name = "Sujay", pkg = DISCORD),
                notif(
                    pkg = DISCORD,
                    title = "Sujay (#general, My Server)",
                    isMessagingStyle = false,
                ),
            ),
        )
    }

    @Test
    fun `a short name never matches by containment`() {
        // "Jo" would otherwise light up for Jonathan, Joanna and every channel with "job" in it
        assertNull(
            ConversationMatch.strength(
                convoRule(name = "Jo", pkg = DISCORD),
                notif(pkg = DISCORD, title = "Jonathan Smith", isMessagingStyle = false),
            ),
        )
        // the same name still matches when it is the whole title, so the floor only bounds containment
        assertEquals(
            MatchStrength.NAME,
            ConversationMatch.strength(
                convoRule(name = "Jo", pkg = DISCORD),
                notif(pkg = DISCORD, title = "Jo", isMessagingStyle = false),
            ),
        )
    }

    @Test
    fun `containment obeys the group switch`() {
        // An unnamed WhatsApp group is titled with its members, so the containment branch would fire
        // a rule for Sujay on that group unless it applies the same guard as the sender branch.
        val group = notif(
            sender = "Sujay",
            conversationTitle = "Sujay, Amit, Priya",
            title = "Sujay, Amit, Priya",
        )
        assertNull(ConversationMatch.strength(convoRule(name = "Sujay", includeGroups = false), group))
        assertEquals(
            MatchStrength.NAME,
            ConversationMatch.strength(convoRule(name = "Sujay", includeGroups = true), group),
        )
    }

    // ---------------------------------------------------------------- resolve

    @Test
    fun `a conversation rule beats a plain app rule for the same app`() {
        val sujay = convoRule(name = "Sujay")
        val whatsapp = appRule()
        // the app rule first in the list, since that is the order that used to shadow everything
        val picked = ConversationMatch.resolve(listOf(whatsapp, sujay), notif(sender = "Sujay"))
        assertEquals(sujay.id, picked?.id)
    }

    @Test
    fun `a shortcut id match beats a name match`() {
        val byName = convoRule(name = "Sujay")
        val byKey = convoRule(name = "Sujay", key = "chat-sujay")
        val picked = ConversationMatch.resolve(
            listOf(byName, byKey),
            notif(shortcutId = "chat-sujay", sender = "Sujay"),
        )
        assertEquals(byKey.id, picked?.id)
    }

    @Test
    fun `a plain app rule beats the catch all`() {
        val whatsapp = appRule()
        val picked = ConversationMatch.resolve(
            listOf(catchAll(), whatsapp),
            notif(sender = "Sujay"),
        )
        assertEquals(whatsapp.id, picked?.id)
    }

    @Test
    fun `the catch all only fires when nothing else matches`() {
        val any = catchAll()
        assertEquals(any.id, ConversationMatch.resolve(listOf(any), notif(pkg = TELEGRAM))?.id)
        val telegram = appRule(TELEGRAM)
        assertEquals(
            telegram.id,
            ConversationMatch.resolve(listOf(any, telegram), notif(pkg = TELEGRAM))?.id,
        )
    }

    @Test
    fun `a conversation rule that misses falls back to the app rule`() {
        // Amit messaging must still light the app's own colour; only the per-chat colour is his to miss
        val whatsapp = appRule()
        val picked = ConversationMatch.resolve(
            listOf(convoRule(name = "Sujay"), whatsapp),
            notif(sender = "Amit"),
        )
        assertEquals(whatsapp.id, picked?.id)
    }

    @Test
    fun `an app text rule beats the plain app fallback`() {
        val urgent = textRule("urgent")
        val fallback = appRule()
        assertEquals(
            urgent.id,
            ConversationMatch.resolve(
                listOf(fallback, urgent),
                notif(title = "Signal", text = "This is URGENT"),
            )?.id,
        )
    }

    @Test
    fun `overlapping app text rules use saved order`() {
        val urgent = textRule("urgent")
        val invoice = textRule("invoice")
        val message = notif(text = "Urgent invoice attached")
        assertEquals(urgent.id, ConversationMatch.resolve(listOf(urgent, invoice), message)?.id)
        assertEquals(invoice.id, ConversationMatch.resolve(listOf(invoice, urgent), message)?.id)
    }

    @Test
    fun `a text rule that misses falls through to the app fallback`() {
        val fallback = appRule()
        assertEquals(
            fallback.id,
            ConversationMatch.resolve(
                listOf(textRule("urgent"), fallback),
                notif(text = "ordinary message"),
            )?.id,
        )
    }

    @Test
    fun `an app text rule that misses does not leak through to Any app`() {
        assertNull(
            ConversationMatch.resolve(
                listOf(textRule("urgent"), catchAll()),
                notif(text = "ordinary message"),
            )
        )
    }

    @Test
    fun `a conversation rule beats a generic text rule`() {
        val bethany = convoRule(name = "Bethany")
        val urgent = textRule("urgent")
        assertEquals(
            bethany.id,
            ConversationMatch.resolve(
                listOf(urgent, bethany),
                notif(sender = "Bethany", text = "urgent"),
            )?.id,
        )
    }

    @Test
    fun `a failed conversation text condition keeps looking`() {
        val conditioned = convoRule(name = "Bethany").copy(keyword = "urgent")
        val fallback = appRule()
        assertEquals(
            fallback.id,
            ConversationMatch.resolve(
                listOf(conditioned, fallback),
                notif(sender = "Bethany", text = "hello"),
            )?.id,
        )
    }

    @Test
    fun `a failed conversation condition can fall through to an app text rule`() {
        val conditioned = convoRule(name = "Bethany").copy(keyword = "urgent")
        val greeting = textRule("hello")
        assertEquals(
            greeting.id,
            ConversationMatch.resolve(
                listOf(conditioned, greeting),
                notif(sender = "Bethany", text = "hello"),
            )?.id,
        )
    }

    @Test
    fun `a plain conversation rule handles the chat when its conditioned sibling misses`() {
        val conditioned = convoRule(name = "Bethany").copy(keyword = "urgent")
        val plain = convoRule(name = "Bethany")
        assertEquals(
            plain.id,
            ConversationMatch.resolve(
                listOf(conditioned, plain),
                notif(sender = "Bethany", text = "hello"),
            )?.id,
        )
    }

    @Test
    fun `a conditioned conversation rule beats its plain conversation rule when both match`() {
        val plain = convoRule(name = "Bethany")
        val urgent = convoRule(name = "Bethany").copy(keyword = "urgent")
        assertEquals(
            urgent.id,
            ConversationMatch.resolve(
                listOf(plain, urgent),
                notif(sender = "Bethany", text = "urgent"),
            )?.id,
        )
    }

    @Test
    fun `a disabled text rule falls through`() {
        val fallback = appRule()
        assertEquals(
            fallback.id,
            ConversationMatch.resolve(
                listOf(textRule("urgent", enabled = false), fallback),
                notif(text = "urgent"),
            )?.id,
        )
    }

    @Test
    fun `text matching trims the condition and ignores case`() {
        val rule = textRule("  InVoice  ")
        assertTrue(ConversationMatch.matchesText(notif(text = "new invoice"), rule.keyword))
    }

    @Test
    fun `text matching searches every notification field HiLight already inspects`() {
        assertTrue(ConversationMatch.matchesText(notif(title = "Security alert"), "security"))
        assertTrue(ConversationMatch.matchesText(notif(sender = "Bethany"), "bethany"))
        assertTrue(
            ConversationMatch.matchesText(
                notif(conversationTitle = "Family chat"),
                "family chat",
            )
        )
    }

    @Test
    fun `a disabled conversation rule falls through to the app rule`() {
        val whatsapp = appRule()
        val picked = ConversationMatch.resolve(
            listOf(convoRule(name = "Sujay", enabled = false), whatsapp),
            notif(sender = "Sujay"),
        )
        assertEquals(whatsapp.id, picked?.id)
    }

    @Test
    fun `nothing fires when every rule is switched off`() {
        assertNull(
            ConversationMatch.resolve(
                listOf(
                    convoRule(name = "Sujay", enabled = false),
                    appRule(enabled = false),
                    catchAll(enabled = false),
                ),
                notif(sender = "Sujay"),
            ),
        )
    }

    @Test
    fun `a while-open rule never answers a notification`() {
        // FOREGROUND rules are driven by ForegroundWatcher; picking one here would light the array
        // for an app that is not even open
        assertNull(
            ConversationMatch.resolve(
                listOf(
                    convoRule(name = "Sujay", trigger = Trigger.FOREGROUND),
                    appRule(trigger = Trigger.FOREGROUND),
                    catchAll(trigger = Trigger.FOREGROUND),
                ),
                notif(sender = "Sujay"),
            ),
        )
    }

    @Test
    fun `a group summary fires nothing`() {
        // the summary is posted alongside the real notifications, so honouring it double-fires
        assertNull(
            ConversationMatch.resolve(
                listOf(convoRule(name = "Sujay"), appRule(), catchAll()),
                notif(sender = "Sujay", isGroupSummary = true),
            ),
        )
    }

    @Test
    fun `a conversation rule for one app never fires for another`() {
        val sujayOnWhatsApp = convoRule(name = "Sujay", pkg = WHATSAPP)
        val fromTelegram = notif(pkg = TELEGRAM, sender = "Sujay")
        assertNull(ConversationMatch.resolve(listOf(sujayOnWhatsApp), fromTelegram))
        // and it does not stop Telegram's own rule from answering
        val telegram = appRule(TELEGRAM)
        assertEquals(
            telegram.id,
            ConversationMatch.resolve(listOf(sujayOnWhatsApp, telegram), fromTelegram)?.id,
        )
    }

    @Test
    fun `a catch all conversation rule fires in whichever app the person used`() {
        val anywhere = AppRule(pkg = AppRule.ANY_APP, label = "Sujay", conversationName = "Sujay")
        assertEquals(
            anywhere.id,
            ConversationMatch.resolve(listOf(anywhere), notif(sender = "Sujay"))?.id,
        )
    }

    @Test
    fun `a rule naming the app beats the same person on the catch all`() {
        val anywhere = AppRule(pkg = AppRule.ANY_APP, label = "Sujay", conversationName = "Sujay")
        val onWhatsApp = convoRule(name = "Sujay")
        assertEquals(
            onWhatsApp.id,
            ConversationMatch.resolve(listOf(anywhere, onWhatsApp), notif(sender = "Sujay"))?.id,
        )
    }

    @Test
    fun `resolveWith reports how the rule was found`() {
        val app = appRule()
        val catchAll = catchAll()
        assertEquals(
            MatchStrength.APP,
            ConversationMatch.resolveWith(listOf(catchAll, app), notif(sender = "Sujay"))?.second,
        )
        assertEquals(
            MatchStrength.CATCH_ALL,
            ConversationMatch.resolveWith(listOf(catchAll), notif(pkg = "com.other"))?.second,
        )
    }

    @Test
    fun `a conversation rule with no key and an unmatchable name is rejected`() {
        // A chat named only with an emoji normalises to nothing, so the rule could never fire and
        // must not be created in the first place.
        assertFalse(ConversationMatch.isMatchable(convoRule(name = SMILEY)))
        assertTrue(ConversationMatch.isMatchable(convoRule(name = "Sujay")))
        assertTrue(ConversationMatch.isMatchable(appRule()))
    }

    // ---------------------------------------------------------------- re-post suppression

    @Test
    fun `the first sighting of a chat is always newer`() {
        assertTrue(ConversationMatch.isNewer(notif(messageStampMs = 1_000), null))
    }

    @Test
    fun `a repost carrying the same stamp is not newer`() {
        // a read receipt or a typing indicator re-posts the notification unchanged
        assertFalse(ConversationMatch.isNewer(notif(messageStampMs = 1_000), 1_000))
    }

    @Test
    fun `an older stamp is not newer`() {
        assertFalse(ConversationMatch.isNewer(notif(messageStampMs = 900), 1_000))
    }

    @Test
    fun `a genuinely new message is newer`() {
        assertTrue(ConversationMatch.isNewer(notif(messageStampMs = 1_001), 1_000))
    }

    @Test
    fun `the post time stands in for a missing message stamp`() {
        // apps without MessagingStyle give no per-message timestamp at all
        assertTrue(ConversationMatch.isNewer(notif(postTimeMs = 5_000), 4_000))
        assertFalse(ConversationMatch.isNewer(notif(postTimeMs = 5_000), 5_000))
    }

    @Test
    fun `the stamp remembered for a notification rejects its own repost`() {
        // the round trip the listener actually performs, and the one that stops a single chat
        // re-firing its colour on every unrelated update inside the same bundle
        val withStamp = notif(messageStampMs = 1_234, postTimeMs = 9_999)
        assertFalse(ConversationMatch.isNewer(withStamp, ConversationMatch.stampOf(withStamp)))

        val stampless = notif(postTimeMs = 9_999)
        assertFalse(ConversationMatch.isNewer(stampless, ConversationMatch.stampOf(stampless)))
    }

    @Test
    fun `the message stamp is preferred over the post time`() {
        // the post time moves on every re-post, so remembering it would defeat the check
        assertEquals(1_234L, ConversationMatch.stampOf(notif(messageStampMs = 1_234, postTimeMs = 9_999)))
        assertEquals(9_999L, ConversationMatch.stampOf(notif(postTimeMs = 9_999)))
    }

    // ---------------------------------------------------------------- what the picker is offered

    @Test
    fun `a group is remembered by its title, not by whoever spoke`() {
        val message = notif(
            shortcutId = "chat-rocket",
            sender = "Sujay",
            conversationTitle = "Team Rocket",
            title = "Team Rocket",
            postTimeMs = 77,
        )
        assertEquals("Team Rocket", message.displayName)
        val ref = message.toRef()
        assertEquals("Team Rocket", ref?.name)
        assertEquals("chat-rocket", ref?.key)
        assertTrue(ref?.isGroup == true)
    }

    @Test
    fun `a notification with no name at all cannot be remembered`() {
        // nothing to show in the picker and nothing to match on, which is what the inspector's
        // "no name" pill is there to explain
        assertNull(notif(title = "").displayName)
        assertNull(notif(title = "").toRef())
    }

    @Test
    fun `the readable description never carries the message text`() {
        // the inspector shows and exports this, so a leak here puts private messages in bug reports
        val described = notif(sender = "Sujay", text = "meet me at the usual place").describe()
        assertFalse(described.contains("usual place"))
        assertTrue(described.contains("Sujay"))
    }

    // ---------------------------------------------------------------- stable rule identity

    @Test
    fun `editing match fields keeps the same saved rule id`() {
        val rule = convoRule(name = "Sujay")
        assertEquals(rule.id, rule.copy(keyword = "urgent", conversationKey = "chat-9").id)
    }

    @Test
    fun `duplicating a rule gives it independent saved identity`() {
        val rule = textRule("urgent")
        assertTrue(rule.id != rule.copyAsNew().id)
    }

    @Test
    fun `semantic duplicate detection is separate from saved identity`() {
        val original = textRule("  Urgent ")
        val duplicate = original.copyAsNew().copy(keyword = "urgent")
        val different = original.copyAsNew().copy(keyword = "invoice")
        assertTrue(ConversationMatch.sameTarget(original, duplicate))
        assertFalse(ConversationMatch.sameTarget(original, different))
    }

    @Test
    fun `a chat that survives normalisation as nothing can never be turned into a rule`() {
        // Reachable from all three picker paths, not only the contact one: a chat named with a single
        // emoji reads perfectly well in the list and produces a rule that stays dark for ever.
        assertFalse(ConversationMatch.isMatchable(convoRule(name = SMILEY)))
        // unless it brought a stable id with it, which is all the matcher needs
        assertTrue(ConversationMatch.isMatchable(convoRule(name = SMILEY, key = "chat-9")))
    }
}
