package com.hilight.studio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RootRecoveryTest {
    @Test
    fun `auto recovers root after confirmed exit while adb has not started cleanup`() {
        assertTrue(shouldPreferRecoveredRoot(Transport.AUTO, Transport.ROOT, true,
            Transport.ADB, false, true))
    }

    @Test
    fun `root recovery preserves source ownership explicit selections and fallback cleanup`() {
        assertFalse(shouldPreferRecoveredRoot(Transport.AUTO, Transport.ROOT, false,
            Transport.ADB, false, true))
        assertFalse(shouldPreferRecoveredRoot(Transport.ADB, Transport.ROOT, true,
            Transport.ADB, false, true))
        assertFalse(shouldPreferRecoveredRoot(Transport.SHIZUKU, Transport.ROOT, true,
            Transport.ADB, false, true))
        assertFalse(shouldPreferRecoveredRoot(Transport.AUTO, Transport.SHIZUKU, true,
            Transport.ADB, false, true))
        assertFalse(shouldPreferRecoveredRoot(Transport.AUTO, Transport.ROOT, true,
            Transport.ADB, true, true))
        assertFalse(shouldPreferRecoveredRoot(Transport.AUTO, Transport.ROOT, true,
            Transport.ADB, false, false))
    }
}
