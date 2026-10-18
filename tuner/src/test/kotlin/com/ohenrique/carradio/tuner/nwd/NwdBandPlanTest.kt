package com.ohenrique.carradio.tuner.nwd

import com.ohenrique.carradio.tuner.Region
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NwdBandPlanTest {

    @Test
    fun `tells the unit by the size of the number`() {
        assertNull(NwdBandPlan.scaleOf(4_999))
        assertEquals(10, NwdBandPlan.scaleOf(5_000))
        assertEquals(10, NwdBandPlan.scaleOf(12_000))
        assertNull(NwdBandPlan.scaleOf(12_001))
        assertNull(NwdBandPlan.scaleOf(499))
        assertEquals(100, NwdBandPlan.scaleOf(500))
        assertEquals(100, NwdBandPlan.scaleOf(1_200))
        assertNull(NwdBandPlan.scaleOf(1_201))
        assertNull(NwdBandPlan.scaleOf(49_999))
        assertEquals(1, NwdBandPlan.scaleOf(50_000))
        assertEquals(1, NwdBandPlan.scaleOf(120_000))
        assertNull(NwdBandPlan.scaleOf(120_001))
    }

    @Test
    fun `takes the FM entry of the radio point`() {
        val plan = NwdBandPlan.fromRadioPoints(listOf(NwdRadioPoint(8_750, 10_800, 10), NwdRadioPoint(530, 1_710, 10)))

        assertEquals(NwdBandPlan(10, 8_750, 10_800, 10), plan)
        assertEquals(87_500, plan!!.minKhz)
        assertEquals(108_000, plan.maxKhz)
        assertEquals(100, plan.stepKhz)
        assertEquals(206, plan.channels)
        assertEquals(Region.AMERICAS, plan.region)
    }

    @Test
    fun `looks past an invalid first entry, but never takes AM`() {
        assertEquals(
            NwdBandPlan(100, 875, 1_080, 1),
            NwdBandPlan.fromRadioPoints(listOf(NwdRadioPoint(0, 0, 0), NwdRadioPoint(875, 1_080, 1))),
        )
        assertNull(NwdBandPlan.fromRadioPoints(listOf(NwdRadioPoint(0, 0, 0), NwdRadioPoint(530, 1_710, 10))))
        assertNull(NwdBandPlan.fromRadioPoints(listOf(null, NwdRadioPoint(10_800, 8_750, 10))))
        assertNull(NwdBandPlan.fromRadioPoints(listOf(NwdRadioPoint(8_750, 10_800, 0))))
        assertNull(NwdBandPlan.fromRadioPoints(emptyList()))
        assertNull(NwdBandPlan.fromRadioPoints(null))
    }

    @Test
    fun `takes the unit from an FM reading only`() {
        assertEquals(NwdBandPlan(10, 7_610, 10_790, 10), NwdBandPlan.fromFrequency(NwdFrequency(Nwd.Band.FM2, null, 9_810), Region.BRAZIL))
        assertEquals(NwdBandPlan(100, 761, 1_079, 1), NwdBandPlan.fromFrequency(NwdFrequency(Nwd.Band.FM1, "", 981), Region.BRAZIL))
        assertEquals(NwdBandPlan(1, 87_500, 108_000, 50), NwdBandPlan.fromFrequency(NwdFrequency(Nwd.Band.FM1, "", 98_100), Region.EUROPE))
        assertNull(NwdBandPlan.fromFrequency(NwdFrequency(Nwd.Band.AM1, "", 1_010), Region.BRAZIL))
        assertNull(NwdBandPlan.fromFrequency(NwdFrequency(Nwd.Band.FM1, "", 0), Region.BRAZIL))
        assertNull(NwdBandPlan.fromFrequency(null, Region.BRAZIL))
    }

    @Test
    fun `snaps to the service grid and converts back`() {
        val plan = NwdBandPlan(10, 8_750, 10_790, 20)

        assertEquals(9_810, plan.toRaw(98_100))
        assertEquals(9_810, plan.toRaw(98_050))
        assertEquals(9_830, plan.toRaw(98_200))
        assertEquals(8_750, plan.toRaw(76_100))
        assertEquals(10_790, plan.toRaw(108_000))
        assertEquals(98_100, plan.toKhz(9_810))
        assertEquals(Region.USA, plan.region)
    }

    @Test
    fun `never goes past the top of a band that ends off the grid`() {
        val plan = NwdBandPlan(10, 8_750, 10_800, 20)

        assertEquals(10_790, plan.toRaw(108_000))
    }

    @Test
    fun `falls back to 10 kHz units with the app's band`() {
        assertEquals(NwdBandPlan(10, 7_610, 10_790, 10), NwdBandPlan.fallback(Region.BRAZIL))
        assertEquals(NwdBandPlan(10, 8_750, 10_800, 5), NwdBandPlan.fallback(Region.EUROPE))
    }
}
