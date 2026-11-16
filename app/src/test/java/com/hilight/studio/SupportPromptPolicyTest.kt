package com.hilight.studio

import org.junit.Assert.assertEquals
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

    @Test fun `first open after an update offers the choice to non-supporters`() {
        assertTrue(shouldOfferSupportAfterUpdate(20, false, 21, false, false))
        // Updated from a version that never recorded itself: Android's install times decide.
        assertTrue(shouldOfferSupportAfterUpdate(0, true, 21, false, false))
    }

    @Test fun `fresh install, same version and supporters never get the after-update offer`() {
        assertFalse(shouldOfferSupportAfterUpdate(0, false, 21, false, false))
        assertFalse(shouldOfferSupportAfterUpdate(21, true, 21, false, false))
        assertFalse(shouldOfferSupportAfterUpdate(20, false, 21, false, true))
    }

    @Test fun `after-update offer shares the once per version limit with the style prompt`() {
        assertFalse(shouldOfferSupportAfterUpdate(20, false, 21, true, false))
        assertFalse(shouldOfferSupportChoice(five, "chase", wasSupportPromptShownForVersion(21, 21), false))
    }

    @Test fun `after-update offer waits for Play's ownership answer instead of showing`() {
        assertEquals(UpdateOfferDecision.WAIT, decideSupportOfferAfterUpdate(20, false, 21, false, false, false))
        assertEquals(UpdateOfferDecision.SHOW, decideSupportOfferAfterUpdate(20, false, 21, false, false, true))
        // Play said "supporter": never shown, and the version is settled.
        assertEquals(UpdateOfferDecision.SKIP, decideSupportOfferAfterUpdate(20, false, 21, false, true, true))
    }

    @Test fun `only a settled offer records the version as seen`() {
        // WAIT leaves the version unrecorded, so a rotation or offline start asks again.
        assertEquals(UpdateOfferDecision.SKIP, decideSupportOfferAfterUpdate(0, false, 21, false, false, false))
        assertEquals(UpdateOfferDecision.SKIP, decideSupportOfferAfterUpdate(21, true, 21, false, false, false))
        assertEquals(UpdateOfferDecision.SKIP, decideSupportOfferAfterUpdate(20, false, 21, true, false, false))
    }

    @Test fun `monthly purchase is saved like lifetime so an offline start knows`() {
        assertEquals(setOf(SupporterFlag.MONTHLY), supporterFlagsToSave(grantsLifetime = false, grantsMonthly = true))
        assertEquals(setOf(SupporterFlag.LIFETIME), supporterFlagsToSave(grantsLifetime = true, grantsMonthly = false))
    }

    @Test fun `supporter still sees a failed acknowledgement and its retry`() {
        assertTrue(showsPurchaseStatus(supporterOwned = true, acknowledgementFailed = true))
        assertFalse(showsPurchaseStatus(supporterOwned = true, acknowledgementFailed = false))
        assertTrue(showsPurchaseStatus(supporterOwned = false, acknowledgementFailed = false))
    }
}
