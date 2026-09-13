package com.hilight.studio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShizukuRecoveryNotificationTest {
    @Test
    fun `manager death warns even while exact service cleanup is fenced`() {
        assertTrue(shizukuUnavailableForNotice(ShizukuBackend.State.FAILED, false))
        assertTrue(shizukuUnavailableForNotice(ShizukuBackend.State.NOT_RUNNING, false))
        assertFalse(shizukuUnavailableForNotice(ShizukuBackend.State.FAILED, true))
        assertFalse(shizukuUnavailableForNotice(ShizukuBackend.State.NOT_RUNNING, true))
        assertFalse(shizukuUnavailableForNotice(ShizukuBackend.State.CONNECTING, false))
    }

    private fun warn(optedIn: Boolean = true, master: Boolean = true,
                     lastWorking: String? = "SHIZUKU", selected: Transport = Transport.AUTO,
                     unavailable: Boolean = true, connected: Boolean = false,
                     rootStarting: Boolean = false) = shouldWarnAboutShizuku(
        optedIn, master, lastWorking, selected, unavailable, connected, rootStarting,
    )

    @Test
    fun `previously working shizuku is reported after loss or reboot`() {
        assertTrue(warn())
        assertTrue(warn(selected = Transport.SHIZUKU))
    }

    @Test
    fun `no warning for new users opted out disabled or other transports`() {
        assertFalse(warn(optedIn = false))
        assertFalse(warn(master = false))
        assertFalse(warn(lastWorking = null))
        assertFalse(warn(lastWorking = "ROOT"))
        assertFalse(warn(lastWorking = "ADB"))
        assertFalse(warn(selected = Transport.ADB))
        assertFalse(warn(selected = Transport.ROOT))
    }

    @Test
    fun `reconnection working fallback and root startup suppress the warning`() {
        assertFalse(warn(unavailable = false))
        assertFalse(warn(connected = true))
        assertFalse(warn(rootStarting = true))
    }
}
