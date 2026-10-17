package com.ohenrique.carradio.tuner.qn8035

import com.ohenrique.carradio.tuner.FoundStation
import com.ohenrique.carradio.tuner.PowerState
import com.ohenrique.carradio.tuner.Region
import com.ohenrique.carradio.tuner.SeekDirection
import com.ohenrique.carradio.tuner.Sensitivity
import com.ohenrique.carradio.tuner.scanBand
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class Qn8035TunerTest {

    private val stations = mapOf(89_100 to 44, 96_100 to 47, 98_500 to 33, 106_300 to 18)

    private fun TestScope.tunerFor(chip: FakeQn8035) = Qn8035Tuner(
        config = Qn8035Config(),
        openBus = { chip },
        dispatcher = StandardTestDispatcher(testScheduler),
    )

    @Test
    fun `power on resets the chip and tunes`() = runTest {
        val chip = FakeQn8035(stations)
        val tuner = tunerFor(chip)

        tuner.powerOn(Region.BRAZIL, 98_500)

        assertEquals(PowerState.ON, tuner.state.value.power)
        assertEquals(0x00 to 0x80, chip.writes.first())
        // 98.5 MHz is channel 770 = 0x302.
        assertEquals(0x02, chip.registers[0x07])
        assertEquals(0x3, chip.registers[0x0A] and 0x3)
        // RXREQ | RDSEN | CCA_CH_DIS
        assertEquals(0x19, chip.registers[0x00])
        // 75 µs de-emphasis, full analog gain.
        assertEquals(0x47, chip.registers[0x14])
        assertEquals(770, chip.tunedChannel)
        tuner.powerOff()
        assertTrue(chip.closed)
    }

    @Test
    fun `power on reports an error when the chip is not a QN8035`() = runTest {
        val tuner = tunerFor(FakeQn8035(productId = 0x13))

        tuner.powerOn(Region.BRAZIL, 98_500)

        assertEquals(PowerState.ERROR, tuner.state.value.power)
        assertTrue(tuner.state.value.error!!.contains("QN8035"))
    }

    @Test
    fun `seek up stops on the next station`() = runTest {
        val tuner = tunerFor(FakeQn8035(stations))
        tuner.powerOn(Region.BRAZIL, 90_000)

        val found = tuner.seek(SeekDirection.UP)

        assertEquals(FoundStation(96_100, 47), found)
        assertEquals(96_100, tuner.state.value.frequencyKhz)
        assertTrue(tuner.state.value.signal.stereo)
        tuner.powerOff()
    }

    @Test
    fun `seek down wraps around the band`() = runTest {
        val tuner = tunerFor(FakeQn8035(stations))
        tuner.powerOn(Region.BRAZIL, 88_000)

        val found = tuner.seek(SeekDirection.DOWN)

        assertEquals(106_300, found?.frequencyKhz)
        tuner.powerOff()
    }

    @Test
    fun `local sensitivity skips weak stations`() = runTest {
        val tuner = tunerFor(FakeQn8035(stations))
        tuner.powerOn(Region.BRAZIL, 99_000)
        tuner.setSensitivity(Sensitivity.LOCAL)

        val found = tuner.seek(SeekDirection.UP)

        // 106.3 (18 dBµV) is below the LOC threshold, so the seek wraps to 89.1.
        assertEquals(89_100, found?.frequencyKhz)
        tuner.powerOff()
    }

    @Test
    fun `failed seek returns to where it started`() = runTest {
        val tuner = tunerFor(FakeQn8035(emptyMap()))
        tuner.powerOn(Region.BRAZIL, 99_900)

        assertNull(tuner.seek(SeekDirection.UP))
        assertEquals(99_900, tuner.state.value.frequencyKhz)
        tuner.powerOff()
    }

    @Test
    fun `scan finds every station in the band`() = runTest {
        val tuner = tunerFor(FakeQn8035(stations))
        tuner.powerOn(Region.BRAZIL, 100_000)

        val found = tuner.scanBand()

        assertEquals(listOf(89_100, 96_100, 98_500, 106_300), found.map { it.frequencyKhz })
        assertEquals(96_100, tuner.state.value.frequencyKhz)
        tuner.powerOff()
    }

    @Test
    fun `mute sets MUTE_EN`() = runTest {
        val chip = FakeQn8035(stations)
        val tuner = tunerFor(chip)
        tuner.powerOn(Region.EUROPE, 98_500)

        tuner.setMuted(true)

        assertEquals(0x87, chip.registers[0x14])
        tuner.powerOff()
    }

    @Test
    fun `station name arrives through RDS polling`() = runTest {
        val chip = FakeQn8035(stations)
        val tuner = tunerFor(chip)
        tuner.powerOn(Region.BRAZIL, 96_100)
        // The first group after tuning is discarded as stale.
        chip.queueRdsGroup(0, 0, 0, 0)
        "POP 96  ".chunked(2).forEachIndexed { segment, chars ->
            chip.queueRdsGroup(0xC123, segment, 0, (chars[0].code shl 8) or chars[1].code)
        }

        advanceTimeBy(1_000)

        assertEquals("POP 96", tuner.state.value.rds.programService)
        assertEquals(0xC123, tuner.state.value.rds.programId)
        tuner.powerOff()
    }

    @Test
    fun `clock dividers match the datasheet for a 32 kHz crystal`() {
        assertEquals(1 to 2945, Qn8035Registers.clockDividers(32_768))
    }
}
