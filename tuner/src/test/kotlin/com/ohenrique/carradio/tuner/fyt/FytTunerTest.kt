package com.ohenrique.carradio.tuner.fyt

import com.ohenrique.carradio.tuner.PowerState
import com.ohenrique.carradio.tuner.Region
import com.ohenrique.carradio.tuner.SeekDirection
import com.ohenrique.carradio.tuner.Sensitivity
import com.ohenrique.carradio.tuner.TunerActivity
import com.ohenrique.carradio.tuner.fyt.Syu.Main
import com.ohenrique.carradio.tuner.fyt.Syu.Radio
import com.ohenrique.carradio.tuner.fyt.Syu.Sound
import com.ohenrique.carradio.tuner.scanBand
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FytTunerTest {

    @Test
    fun `power on makes the radio the active source and unmutes it`() = runTest {
        val mcu = FakeMcu()
        val tuner = FytTuner({ mcu })

        tuner.powerOn(Region.BRAZIL, 87_900)

        assertEquals(PowerState.ON, tuner.state.value.power)
        assertEquals(listOf(listOf(Main.APP_ID_RADIO)), mcu.commandsTo(Syu.MODULE_MAIN, Main.C_APP_ID))
        assertEquals(listOf(listOf(Sound.VOL_UNMUTE)), mcu.commandsTo(Syu.MODULE_SOUND, Sound.C_VOL))
        assertEquals(Main.APP_ID_RADIO, mcu.appId)
        assertFalse(tuner.state.value.muted)
        assertTrue(tuner.managesAudioSource)
    }

    @Test
    fun `keeps the station the MCU is on and reads the band from it`() = runTest {
        val mcu = FakeMcu(frequency = 9_810)
        val tuner = FytTuner({ mcu })

        tuner.powerOn(Region.BRAZIL, 87_900)

        assertEquals(98_100, tuner.state.value.frequencyKhz)
        assertEquals(Region.AMERICAS, tuner.state.value.region)
        assertTrue(mcu.commandsTo(Syu.MODULE_RADIO, Radio.C_FREQ).isEmpty())
    }

    @Test
    fun `tries the car radio source id when the radio id is refused`() = runTest {
        val mcu = FakeMcu(acceptedAppIds = setOf(Main.APP_ID_CAR_RADIO))
        val tuner = FytTuner({ mcu })

        tuner.powerOn(Region.BRAZIL, 87_900)

        assertEquals(
            listOf(listOf(Main.APP_ID_RADIO), listOf(Main.APP_ID_CAR_RADIO), listOf(Main.APP_ID_RADIO)),
            mcu.commandsTo(Syu.MODULE_MAIN, Main.C_APP_ID),
        )
        assertEquals(Main.APP_ID_CAR_RADIO, mcu.appId)
        assertFalse(tuner.state.value.muted)
    }

    @Test
    fun `works when the service does not report the source`() = runTest {
        val mcu = FakeMcu(reportsAppId = false)
        val tuner = FytTuner({ mcu })

        tuner.powerOn(Region.BRAZIL, 87_900)

        assertEquals(PowerState.ON, tuner.state.value.power)
        assertEquals(1, mcu.commandsTo(Syu.MODULE_MAIN, Main.C_APP_ID).size)
        assertFalse(tuner.state.value.muted)
    }

    @Test
    fun `tunes directly in 10 kHz units`() = runTest {
        val mcu = FakeMcu()
        val tuner = FytTuner({ mcu })
        tuner.powerOn(Region.BRAZIL, 87_900)

        tuner.tune(96_100)

        assertEquals(listOf(listOf(Radio.FREQ_DIRECT, 9_610)), mcu.commandsTo(Syu.MODULE_RADIO, Radio.C_FREQ))
        assertEquals(96_100, tuner.state.value.frequencyKhz)
    }

    @Test
    fun `tunes in kHz on firmware that reports kHz`() = runTest {
        val mcu = FakeMcu(frequency = 98_100, stations = emptySet(), min = 87_500, max = 108_000, step = 50)
        val tuner = FytTuner({ mcu })
        tuner.powerOn(Region.BRAZIL, 87_900)

        tuner.tune(96_150)

        assertEquals(Region.EUROPE, tuner.state.value.region)
        assertEquals(listOf(listOf(Radio.FREQ_DIRECT, 96_150)), mcu.commandsTo(Syu.MODULE_RADIO, Radio.C_FREQ))
        assertEquals(96_150, tuner.state.value.frequencyKhz)
    }

    @Test
    fun `steps to the frequency when direct tuning does nothing`() = runTest {
        val mcu = FakeMcu(directTune = false)
        val tuner = FytTuner({ mcu })
        tuner.powerOn(Region.BRAZIL, 87_900)

        tuner.tune(97_000)
        assertEquals(97_000, tuner.state.value.frequencyKhz)
        tuner.tune(98_500)
        assertEquals(98_500, tuner.state.value.frequencyKhz)

        // Gives up on the direct command after it failed twice.
        tuner.tune(98_000)
        assertEquals(98_000, tuner.state.value.frequencyKhz)
        assertEquals(2, mcu.commandsTo(Syu.MODULE_RADIO, Radio.C_FREQ).size)
    }

    @Test
    fun `seek stops on the next station`() = runTest {
        val mcu = FakeMcu(frequency = 9_000)
        val tuner = FytTuner({ mcu })
        tuner.powerOn(Region.BRAZIL, 87_900)

        val up = tuner.seek(SeekDirection.UP)
        assertEquals(96_100, up?.frequencyKhz)
        assertNull(up?.rssiDbuv)

        val down = tuner.seek(SeekDirection.DOWN)
        assertEquals(89_100, down?.frequencyKhz)
        assertEquals(TunerActivity.IDLE, tuner.state.value.activity)
    }

    @Test
    fun `seek follows the search state of a slow MCU`() = runTest {
        val mcu = FakeMcu(frequency = 9_000, seekScope = backgroundScope)
        val tuner = FytTuner({ mcu })
        tuner.powerOn(Region.BRAZIL, 87_900)

        assertEquals(96_100, tuner.seek(SeekDirection.UP)?.frequencyKhz)
        assertEquals(96_100, tuner.state.value.frequencyKhz)
    }

    @Test
    fun `seek waits for the dial to settle when the MCU reports no search state`() = runTest {
        val mcu = FakeMcu(frequency = 9_000, seekScope = backgroundScope, reportsSearchState = false)
        val tuner = FytTuner({ mcu })
        tuner.powerOn(Region.BRAZIL, 87_900)

        assertEquals(96_100, tuner.seek(SeekDirection.UP)?.frequencyKhz)
    }

    @Test
    fun `seek with no station returns null`() = runTest {
        val mcu = FakeMcu(stations = emptySet())
        val tuner = FytTuner({ mcu })
        tuner.powerOn(Region.BRAZIL, 87_900)

        assertNull(tuner.seek(SeekDirection.UP))
        assertEquals(98_100, tuner.state.value.frequencyKhz)
    }

    @Test
    fun `scan finds every station in frequency order`() = runTest {
        val mcu = FakeMcu()
        val tuner = FytTuner({ mcu })
        tuner.powerOn(Region.BRAZIL, 87_900)

        val found = tuner.scanBand()

        assertEquals(listOf(89_100, 96_100, 98_500, 106_300), found.map { it.frequencyKhz })
        assertEquals(89_100, tuner.state.value.frequencyKhz)
    }

    @Test
    fun `mute only touches the speakers while the radio is playing`() = runTest {
        val mcu = FakeMcu()
        val tuner = FytTuner({ mcu })
        tuner.powerOn(Region.BRAZIL, 87_900)

        tuner.setMuted(true)
        assertTrue(mcu.muted)
        assertTrue(tuner.state.value.muted)

        tuner.setMuted(false)
        assertFalse(mcu.muted)
        assertFalse(tuner.state.value.muted)

        mcu.switchSource(3)
        assertTrue(tuner.state.value.muted)
        tuner.setMuted(true)
        assertEquals(1, mcu.commandsTo(Syu.MODULE_SOUND, Sound.C_VOL).count { it == listOf(Sound.VOL_MUTE) })
    }

    @Test
    fun `tuning takes the speakers back from another source`() = runTest {
        val mcu = FakeMcu()
        val tuner = FytTuner({ mcu })
        tuner.powerOn(Region.BRAZIL, 87_900)
        mcu.switchSource(3)

        tuner.tune(96_100)

        assertEquals(Main.APP_ID_RADIO, mcu.appId)
        assertFalse(tuner.state.value.muted)
    }

    @Test
    fun `shows RDS names and clears them on a new station`() = runTest {
        val mcu = FakeMcu()
        val tuner = FytTuner({ mcu })
        tuner.powerOn(Region.BRAZIL, 87_900)

        mcu.pushText(Radio.U_RDS_CHANNEL_TEXT, "POP 98  ")
        mcu.pushText(Radio.U_RDS_TEXT, "Tocando agora")
        assertEquals("POP 98", tuner.state.value.rds.programService)
        assertEquals("Tocando agora", tuner.state.value.rds.radioText)

        tuner.tune(96_100)
        assertNull(tuner.state.value.rds.programService)
    }

    @Test
    fun `mono is only sent once the user asks for it`() = runTest {
        val mcu = FakeMcu()
        val tuner = FytTuner({ mcu })
        tuner.powerOn(Region.BRAZIL, 87_900)

        tuner.setForceMono(false)
        assertTrue(mcu.commandsTo(Syu.MODULE_RADIO, Radio.C_STEREO).isEmpty())
        tuner.setForceMono(true)
        tuner.setForceMono(false)
        assertEquals(listOf(listOf(0), listOf(1)), mcu.commandsTo(Syu.MODULE_RADIO, Radio.C_STEREO))
    }

    @Test
    fun `settings chosen before connecting are sent only when the MCU differs`() = runTest {
        val mcu = FakeMcu(local = false, rds = true)
        val tuner = FytTuner({ mcu })
        tuner.setSensitivity(Sensitivity.LOCAL)
        tuner.setRdsEnabled(true)
        tuner.setForceMono(false)

        tuner.powerOn(Region.BRAZIL, 87_900)

        assertEquals(listOf(listOf(1)), mcu.commandsTo(Syu.MODULE_RADIO, Radio.C_LOC))
        assertTrue(mcu.commandsTo(Syu.MODULE_RADIO, Radio.C_RDS_ENABLE).isEmpty())
        assertTrue(mcu.commandsTo(Syu.MODULE_RADIO, Radio.C_STEREO).isEmpty())
        assertEquals(Sensitivity.LOCAL, tuner.state.value.sensitivity)
        assertTrue(mcu.local)
    }

    @Test
    fun `shows the sensitivity the MCU reports`() = runTest {
        val mcu = FakeMcu(local = true)
        val tuner = FytTuner({ mcu })

        tuner.powerOn(Region.BRAZIL, 87_900)

        assertEquals(Sensitivity.LOCAL, tuner.state.value.sensitivity)
        tuner.setSensitivity(Sensitivity.DX)
        assertFalse(mcu.local)
    }

    @Test
    fun `power off keeps the radio as the source`() = runTest {
        val mcu = FakeMcu()
        val tuner = FytTuner({ mcu })
        tuner.powerOn(Region.BRAZIL, 87_900)

        tuner.powerOff()

        assertTrue(mcu.closed)
        assertEquals(Main.APP_ID_RADIO, mcu.appId)
        assertEquals(PowerState.OFF, tuner.state.value.power)
    }

    @Test
    fun `reports an error when the service does not answer`() = runTest {
        val tuner = FytTuner({ awaitCancellation() })

        tuner.powerOn(Region.BRAZIL, 87_900)

        assertEquals(PowerState.ERROR, tuner.state.value.power)
        assertNotNull(tuner.state.value.error)
    }

    @Test
    fun `reports an error when the service cannot be bound`() = runTest {
        val tuner = FytTuner({ throw SyuException("com.syu.ms não está instalado") })

        tuner.powerOn(Region.BRAZIL, 87_900)

        assertEquals(PowerState.ERROR, tuner.state.value.power)
        assertEquals("com.syu.ms não está instalado", tuner.state.value.error)
    }
}
