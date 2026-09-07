package com.hilight.studio

import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability
import org.junit.Assert.assertEquals
import org.junit.Test

class DistributionUpdateCardTest {
    @Test
    fun downloadedUpdateTakesPrecedenceOverAvailability() {
        assertEquals(
            PlayUpdateDecision.DOWNLOADED,
            resolvePlayUpdateDecision(
                updateAvailability = UpdateAvailability.UNKNOWN,
                installStatus = InstallStatus.DOWNLOADED,
                flexibleAllowed = false,
            ),
        )
    }

    @Test
    fun availableFlexibleUpdateStartsPlayFlow() {
        assertEquals(
            PlayUpdateDecision.START_FLEXIBLE,
            resolvePlayUpdateDecision(
                updateAvailability = UpdateAvailability.UPDATE_AVAILABLE,
                installStatus = InstallStatus.UNKNOWN,
                flexibleAllowed = true,
            ),
        )
    }

    @Test
    fun confirmedNoUpdateIsCurrent() {
        assertEquals(
            PlayUpdateDecision.CURRENT,
            resolvePlayUpdateDecision(
                updateAvailability = UpdateAvailability.UPDATE_NOT_AVAILABLE,
                installStatus = InstallStatus.UNKNOWN,
                flexibleAllowed = false,
            ),
        )
    }

    @Test
    fun unknownAvailabilityDoesNotClaimCurrent() {
        assertEquals(
            PlayUpdateDecision.UNAVAILABLE,
            resolvePlayUpdateDecision(
                updateAvailability = UpdateAvailability.UNKNOWN,
                installStatus = InstallStatus.UNKNOWN,
                flexibleAllowed = false,
            ),
        )
    }
}
