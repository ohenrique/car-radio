package com.ohenrique.carradio.tuner

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class UnavailableTunerTest {

    @Test
    fun `power on reports the message as an error`() = runTest {
        val tuner = UnavailableTuner("Nenhum", "não há rádio")

        tuner.powerOn(Region.BRAZIL, 98_540)

        val state = tuner.state.value
        assertEquals(PowerState.ERROR, state.power)
        assertEquals("não há rádio", state.error)
        assertFalse(state.connectionLost)
        assertEquals(98_500, state.frequencyKhz)
    }

    @Test
    fun `everything else does nothing`() = runTest {
        val tuner = UnavailableTuner("Nenhum", "não há rádio")
        tuner.powerOn(Region.BRAZIL, 87_900)

        tuner.tune(98_500)
        tuner.setMuted(true)
        assertNull(tuner.seek(SeekDirection.UP))
        tuner.powerOff()

        assertEquals(PowerState.OFF, tuner.state.value.power)
        assertEquals(87_900, tuner.state.value.frequencyKhz)
        assertFalse(tuner.reportsSignalStrength)
    }
}
