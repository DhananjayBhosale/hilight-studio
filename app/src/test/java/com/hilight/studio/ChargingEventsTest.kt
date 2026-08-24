package com.hilight.studio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChargingEventsTest {

    @Test
    fun `plugging in fires once and later readings do not repeat it`() {
        val events = ChargingEvents()

        assertEquals(ChargingEvents.Event.PLUGGED_IN, events.onReading(40, plugged = true, full = false))
        assertNull(events.onReading(41, plugged = true, full = false))
        assertNull(events.onReading(42, plugged = true, full = false))
    }

    @Test
    fun `reaching full fires once per session`() {
        val events = ChargingEvents()
        events.onReading(95, plugged = true, full = false)

        assertEquals(ChargingEvents.Event.FULL, events.onReading(100, plugged = true, full = true))
        // Android keeps re-broadcasting the level while the phone sits on the charger
        assertNull(events.onReading(100, plugged = true, full = true))
        assertNull(events.onReading(100, plugged = true, full = false))
    }

    @Test
    fun `a level of 100 counts as full even when the status lags`() {
        val events = ChargingEvents()
        events.onReading(99, plugged = true, full = false)

        assertEquals(ChargingEvents.Event.FULL, events.onReading(100, plugged = true, full = false))
    }

    @Test
    fun `plugging in a full phone is only a plug-in`() {
        val events = ChargingEvents()

        assertEquals(ChargingEvents.Event.PLUGGED_IN, events.onReading(100, plugged = true, full = true))
        assertNull(events.onReading(100, plugged = true, full = true))
    }

    @Test
    fun `unplugging resets so the next session fires again`() {
        val events = ChargingEvents()
        events.onReading(50, plugged = true, full = false)
        events.onReading(100, plugged = true, full = true)

        assertNull(events.onReading(100, plugged = false, full = false))
        assertEquals(ChargingEvents.Event.PLUGGED_IN, events.onReading(99, plugged = true, full = false))
        assertEquals(ChargingEvents.Event.FULL, events.onReading(100, plugged = true, full = true))
    }

    @Test
    fun `starting up on the charger is not a plug-in`() {
        val events = ChargingEvents(pluggedNow = true, fullNow = false)

        assertNull(events.onReading(60, plugged = true, full = false))
        assertEquals(ChargingEvents.Event.FULL, events.onReading(100, plugged = true, full = true))
    }

    @Test
    fun `starting up on the charger already full announces nothing`() {
        val events = ChargingEvents(pluggedNow = true, fullNow = true)

        assertNull(events.onReading(100, plugged = true, full = true))
    }

    @Test
    fun `readings off the charger are ignored`() {
        val events = ChargingEvents()

        assertNull(events.onReading(100, plugged = false, full = true))
        assertNull(events.onReading(10, plugged = false, full = false))
    }
}
