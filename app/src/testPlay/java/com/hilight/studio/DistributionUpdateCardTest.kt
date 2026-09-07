package com.hilight.studio

import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability
import androidx.lifecycle.Lifecycle
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    @Test
    fun updateFlowLaunchRequiresLiveCompositionAndStartedActivity() {
        assertTrue(canLaunchPlayUpdate(true, Lifecycle.State.STARTED))
        assertTrue(canLaunchPlayUpdate(true, Lifecycle.State.RESUMED))
        assertFalse(canLaunchPlayUpdate(false, Lifecycle.State.RESUMED))
        assertFalse(canLaunchPlayUpdate(true, Lifecycle.State.CREATED))
        assertFalse(canLaunchPlayUpdate(true, Lifecycle.State.DESTROYED))
    }

    @Test
    fun returningFromBackgroundClearsAnIgnoredCheckingResult() {
        assertEquals(
            PlayUpdateState.IDLE,
            playUpdateStateOnResume(PlayUpdateState.CHECKING),
        )
        assertEquals(
            PlayUpdateState.DOWNLOADING,
            playUpdateStateOnResume(PlayUpdateState.DOWNLOADING),
        )
    }
}
