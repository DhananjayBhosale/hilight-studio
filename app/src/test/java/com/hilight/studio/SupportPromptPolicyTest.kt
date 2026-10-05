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

    @Test fun `previously seen style does not claim more than five when only five were used`() {
        assertFalse(shouldOfferSupportChoice(five, "pulse", false, false))
    }

    @Test fun `existing user with more than five styles gets the once per update prompt`() {
        assertTrue(shouldOfferSupportChoice(five + "chase", "pulse", false, false))
    }

    @Test fun `shown prompt and supporter purchase both remove the prompt`() {
        assertFalse(shouldOfferSupportChoice(five, "chase", true, false))
        assertFalse(shouldOfferSupportChoice(five, "chase", false, true))
    }

    @Test fun `prompt is suppressed only for the version where it was shown`() {
        assertTrue(wasSupportPromptShownForVersion(lastPromptedVersion = 18, currentVersion = 18))
        assertFalse(wasSupportPromptShownForVersion(lastPromptedVersion = 18, currentVersion = 19))
    }
}
