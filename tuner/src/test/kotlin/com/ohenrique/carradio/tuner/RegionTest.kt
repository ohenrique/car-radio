package com.ohenrique.carradio.tuner

import com.ohenrique.carradio.tuner.sim.SimulatedTuner
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class RegionTest {

    @Test
    fun `snap clamps and rounds to the channel grid`() {
        assertEquals(76_100, Region.BRAZIL.snap(70_000))
        assertEquals(107_900, Region.BRAZIL.snap(108_000))
        assertEquals(98_500, Region.BRAZIL.snap(98_540))
        assertEquals(98_300, Region.USA.snap(98_350))
        assertEquals(98_550, Region.EUROPE.snap(98_560))
    }

    @Test
    fun `step wraps around the band`() {
        assertEquals(98_600, Region.BRAZIL.step(98_500, 1))
        assertEquals(76_100, Region.BRAZIL.step(107_900, 1))
        assertEquals(107_900, Region.BRAZIL.step(76_100, -1))
    }

    @Test
    fun `formats frequencies`() {
        assertEquals("98.5", formatMhz(98_500))
        assertEquals("107.9", formatMhz(107_900))
        assertEquals("87.55", formatMhz(87_550))
    }

    @Test
    fun `strongest keeps the best stations sorted by frequency`() {
        val stations = listOf(FoundStation(89_100, 10), FoundStation(96_100, 40), FoundStation(98_500, 30))
        assertEquals(listOf(96_100, 98_500), stations.strongest(2).map { it.frequencyKhz })
    }

    @Test
    fun `simulator scan finds its stations`() = runTest {
        val tuner = SimulatedTuner(stepDelayMs = 0)
        tuner.powerOn(Region.BRAZIL, 98_500)

        val found = tuner.scanBand()

        assertEquals(SimulatedTuner.DEFAULT_STATIONS.map { it.frequencyKhz }, found.map { it.frequencyKhz })
        assertEquals("POP 96", tuner.state.value.rds.programService)
    }
}
