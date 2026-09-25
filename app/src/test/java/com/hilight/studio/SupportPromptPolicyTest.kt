package com.hilight.studio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SupportPromptPolicyTest {
    private val five = setOf("solid", "gradient", "breathe", "blink", "pulse")

    @Test fun `first five distinct styles never prompt`() {
        assertFalse(shouldOfferSupportChoice(five.take(4).toSet(), false, false))
    }

    @Test fun `next style selection after five offers supporter choice`() {
        assertTrue(shouldOfferSupportChoice(five, false, false))
    }

    @Test fun `a previously seen style can trigger the once per update prompt`() {
        assertTrue(shouldOfferSupportChoice(five, false, false))
    }

    @Test fun `shown prompt and supporter purchase both remove the prompt`() {
        assertFalse(shouldOfferSupportChoice(five, true, false))
        assertFalse(shouldOfferSupportChoice(five, false, true))
    }

    @Test fun `prompt is suppressed only for the version where it was shown`() {
        assertTrue(wasSupportPromptShownForVersion(lastPromptedVersion = 18, currentVersion = 18))
        assertFalse(wasSupportPromptShownForVersion(lastPromptedVersion = 18, currentVersion = 19))
    }
}
