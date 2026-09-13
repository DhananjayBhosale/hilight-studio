package com.hilight.studio

import android.content.Intent
import android.os.PowerManager
import org.junit.Assert.assertEquals
import org.junit.Test

class ScreenLifecycleDecisionTest {

    @Test
    fun `screen off arms and refreshes`() {
        assertEquals(
            ScreenLifecycleAction.ARM_AND_REFRESH,
            screenLifecycleAction(Intent.ACTION_SCREEN_OFF, alertScreenOffGated = false),
        )
    }

    @Test
    fun `ordinary alert survives screen wake`() {
        assertEquals(
            ScreenLifecycleAction.REFRESH_ONLY,
            screenLifecycleAction(Intent.ACTION_SCREEN_ON, alertScreenOffGated = false),
        )
    }

    @Test
    fun `screen off gated alert stops on screen wake`() {
        assertEquals(
            ScreenLifecycleAction.CANCEL_AND_REFRESH,
            screenLifecycleAction(Intent.ACTION_SCREEN_ON, alertScreenOffGated = true),
        )
    }

    @Test
    fun `unlock stops every alert`() {
        assertEquals(
            ScreenLifecycleAction.CANCEL_AND_REFRESH,
            screenLifecycleAction(Intent.ACTION_USER_PRESENT, alertScreenOffGated = false),
        )
        assertEquals(
            ScreenLifecycleAction.CANCEL_AND_REFRESH,
            screenLifecycleAction(Intent.ACTION_USER_PRESENT, alertScreenOffGated = true),
        )
    }

    @Test
    fun `power changes only refresh guards`() {
        assertEquals(
            ScreenLifecycleAction.REFRESH_ONLY,
            screenLifecycleAction(Intent.ACTION_POWER_CONNECTED, alertScreenOffGated = false),
        )
        assertEquals(
            ScreenLifecycleAction.REFRESH_ONLY,
            screenLifecycleAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED, false),
        )
    }

    @Test
    fun `missing broadcast action only refreshes guards`() {
        assertEquals(
            ScreenLifecycleAction.REFRESH_ONLY,
            screenLifecycleAction(action = null, alertScreenOffGated = true),
        )
    }
}
