package com.hilight.studio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SupportPromptPolicyTest {
    private val five = setOf("solid", "gradient", "breathe", "blink", "pulse")

    @Test fun `first five distinct styles never prompt`() {
        assertFalse(shouldOfferSupportChoice(five.take(4).toSet(), "pulse", false, false))
    }

    @Test fun `sixth distinct style offers supporter choice`() {
        assertTrue(shouldOfferSupportChoice(five, "chase", false, false))
    }

    @Test fun `previously seen style never prompts`() {
        assertFalse(shouldOfferSupportChoice(five, "pulse", false, false))
    }

    @Test fun `continue free and supporter purchase both remove the prompt`() {
        assertFalse(shouldOfferSupportChoice(five, "chase", true, false))
        assertFalse(shouldOfferSupportChoice(five, "chase", false, true))
    }
}
