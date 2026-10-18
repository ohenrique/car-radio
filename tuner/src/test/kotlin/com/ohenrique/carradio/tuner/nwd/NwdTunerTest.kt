package com.ohenrique.carradio.tuner.nwd

import com.ohenrique.carradio.tuner.PowerState
import com.ohenrique.carradio.tuner.Region
import com.ohenrique.carradio.tuner.SeekDirection
import com.ohenrique.carradio.tuner.Sensitivity
import com.ohenrique.carradio.tuner.TunerActivity
import com.ohenrique.carradio.tuner.scanBand
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NwdTunerTest {

    private val tuners = mutableListOf<NwdTuner>()

    /**
     * runTest, plus a power off of every tuner the test made, even when an assertion failed: the
     * poll loop never goes idle, so runTest would otherwise run it forever.
     */
    private fun nwdTest(body: suspend TestScope.() -> Unit) = runTest {
        try {
            body()
        } finally {
            tuners.forEach { it.powerOff() }
        }
    }

    private fun TestScope.tunerFor(
        fake: FakeNwd,
        takeSpeakers: Boolean = true,
        trace: (String) -> Unit = { println("${testScheduler.currentTime} ms: $it") },
    ): NwdTuner {
        fake.clock = { testScheduler.currentTime }
        return tunerFor({ fake }, takeSpeakers, trace)
    }

    private fun TestScope.tunerFor(
        connect: suspend () -> NwdService,
        takeSpeakers: Boolean = true,
        trace: (String) -> Unit = { println("${testScheduler.currentTime} ms: $it") },
    ) = NwdTuner(
        connect = connect,
        dispatcher = StandardTestDispatcher(testScheduler),
        commandDispatcher = StandardTestDispatcher(testScheduler),
        now = { testScheduler.currentTime },
        takeSpeakers = takeSpeakers,
        trace = trace,
    ).also { tuners += it }

    private val claims = setOf("requestSource", "sendAppInOut", "sendMediaPlay")

    private fun FakeNwd.claimNames() = commands.map { it.name }.filter { it in claims }

    private fun FakeNwd.tunes() = argsOf("setCurrentFrequency")

    // Claiming the source.

    @Test
    fun `type 0 claims with a source request and confirms the source`() = nwdTest {
        val fake = FakeNwd()
        val tuner = tunerFor(fake)

        tuner.powerOn(Region.BRAZIL, 98_100)

        assertEquals(PowerState.ON, tuner.state.value.power)
        assertEquals(listOf(listOf<Any?>(Nwd.Source.RADIO)), fake.argsOf("requestSource"))
        assertEquals(listOf("requestSource"), fake.claimNames())
        assertEquals(Nwd.Source.RADIO, fake.source)
        assertFalse(tuner.state.value.muted)
        assertTrue(fake.registered)
        tuner.powerOff()
    }

    @Test
    fun `type 0 falls back to app in when the source request does not switch`() = nwdTest {
        val fake = FakeNwd(requestSwitches = false)
        val tuner = tunerFor(fake)

        tuner.powerOn(Region.BRAZIL, 98_100)

        assertEquals(listOf("requestSource", "sendAppInOut"), fake.claimNames())
        assertEquals(listOf(listOf<Any?>(Nwd.App.RADIO, Nwd.App.OPERATION_IN, Nwd.App.EVENT_NONE)), fake.argsOf("sendAppInOut"))
        assertTrue(fake.named("sendAppInOut").single().atMs - fake.named("requestSource").single().atMs >= 3_000)
        assertEquals(Nwd.Source.RADIO, fake.source)
        assertFalse(tuner.state.value.muted)
        tuner.powerOff()
    }

    @Test
    fun `other types announce the radio app and media play first and fall back to the source request`() = nwdTest {
        val fake = FakeNwd(type = Nwd.RadioType.SOC, appInSwitches = false)
        val tuner = tunerFor(fake)

        tuner.powerOn(Region.BRAZIL, 98_100)

        assertEquals(listOf("sendAppInOut", "sendMediaPlay", "requestSource"), fake.claimNames())
        assertEquals(listOf(listOf<Any?>(Nwd.App.RADIO)), fake.argsOf("sendMediaPlay"))
        assertEquals(Nwd.Source.RADIO, fake.source)
        tuner.powerOff()
    }

    @Test
    fun `an unknown radio type uses the type 0 order without media play`() = nwdTest {
        val fake = FakeNwd(refusedCalls = setOf("getRadioType"), requestSwitches = false)
        val tuner = tunerFor(fake)

        tuner.powerOn(Region.BRAZIL, 98_100)

        assertEquals(PowerState.ON, tuner.state.value.power)
        assertEquals(listOf("requestSource", "sendAppInOut"), fake.claimNames())
        tuner.powerOff()
    }

    @Test
    fun `an unreadable source sends the first claim once and carries on`() = nwdTest {
        val fake = FakeNwd(sourceReadable = false, requestSwitches = false, appInSwitches = false)
        val tuner = tunerFor(fake)

        tuner.powerOn(Region.BRAZIL, 98_100)
        assertTrue(testScheduler.currentTime < 3_000)
        tuner.tune(96_100)
        tuner.reclaimSource()
        tuner.setMuted(false)

        assertEquals(listOf("requestSource"), fake.claimNames())
        assertEquals(96_100, tuner.state.value.frequencyKhz)
        // Nothing to check against, so the radio counts as playing.
        assertFalse(tuner.state.value.muted)
        tuner.powerOff()
    }

    @Test
    fun `no claim is sent when the radio already is the source`() = nwdTest {
        val fake = FakeNwd(source = Nwd.Source.RADIO)
        val tuner = tunerFor(fake)

        tuner.powerOn(Region.BRAZIL, 98_100)
        tuner.tune(96_100)
        tuner.reclaimSource()

        assertTrue(fake.claimNames().isEmpty())
        tuner.powerOff()
    }

    @Test
    fun `two claims are never sent within three seconds`() = nwdTest {
        val fake = FakeNwd()
        val tuner = tunerFor(fake)
        tuner.powerOn(Region.BRAZIL, 98_100)

        // The unit bounces back to another source right after the claim.
        advanceTimeBy(500)
        fake.switchSource(BT_MUSIC)
        tuner.tune(96_100)
        assertEquals(1, fake.named("requestSource").size)
        // The tune went out anyway, once the wait for the first claim was over.
        assertEquals(listOf(listOf<Any?>(9_610, Nwd.Band.FM1, Nwd.NO_PRESET)), fake.tunes())

        advanceTimeBy(3_000)
        fake.switchSource(BT_MUSIC)
        tuner.tune(98_500)
        assertEquals(2, fake.named("requestSource").size)
        assertEquals(Nwd.Source.RADIO, fake.source)
        tuner.powerOff()
    }

    @Test
    fun `leaves the speakers to a phone call`() = nwdTest {
        val fake = FakeNwd()
        val tuner = tunerFor(fake)
        tuner.powerOn(Region.BRAZIL, 98_100)
        fake.inCall = true
        fake.switchSource(Nwd.Source.ANDROID)

        tuner.reclaimSource()
        tuner.setMuted(false)
        tuner.setMuted(true)
        tuner.tune(96_100)
        assertEquals(1, fake.named("requestSource").size)
        assertTrue(fake.named("setUnitMuted").isEmpty())
        assertEquals(Nwd.Source.ANDROID, fake.source)

        fake.inCall = false
        advanceTimeBy(3_000)
        tuner.reclaimSource()
        assertEquals(Nwd.Source.RADIO, fake.source)
        tuner.powerOff()
    }

    @Test
    fun `power on during a call claims nothing`() = nwdTest {
        val fake = FakeNwd(inCall = true)
        val tuner = tunerFor(fake)

        tuner.powerOn(Region.BRAZIL, 98_100)

        assertEquals(PowerState.ON, tuner.state.value.power)
        assertTrue(fake.claimNames().isEmpty())
        assertTrue(tuner.state.value.muted)
        tuner.powerOff()
    }

    @Test
    fun `tunes only after the claim and once the tuner is ready`() = nwdTest {
        val fake = FakeNwd(
            claimDelayMs = 400,
            readyAfterClaimMs = 700,
            tunesNeedSource = true,
            scope = backgroundScope,
        )
        val tuner = tunerFor(fake)

        tuner.powerOn(Region.BRAZIL, 96_100)
        advanceTimeBy(5_000)

        val tune = fake.named("setCurrentFrequency").single()
        assertTrue(tune.atMs >= fake.named("requestSource").single().atMs + 400 + 700)
        assertEquals(9_610, fake.frequency)
        assertEquals(96_100, tuner.state.value.frequencyKhz)
        tuner.powerOff()
    }

    @Test
    fun `a service that never reports ready is waited for once`() = nwdTest {
        val fake = FakeNwd(restState = Nwd.State.CLOSED)
        val tuner = tunerFor(fake)
        tuner.powerOn(Region.BRAZIL, 98_100)

        val start = testScheduler.currentTime
        tuner.tune(96_100)
        val first = testScheduler.currentTime - start
        tuner.tune(98_500)
        val second = testScheduler.currentTime - start - first

        assertTrue(first >= 2_000)
        assertTrue(second < 2_000)
        tuner.powerOff()
    }

    @Test
    fun `power on without taking the speakers leaves another source alone`() = nwdTest {
        val fake = FakeNwd(source = BT_MUSIC, muted = true)
        val tuner = tunerFor(fake, takeSpeakers = false)

        tuner.powerOn(Region.BRAZIL, 96_100)
        advanceTimeBy(5_000)

        assertEquals(PowerState.ON, tuner.state.value.power)
        assertTrue(fake.claimNames().isEmpty())
        assertTrue(fake.tunes().isEmpty())
        assertTrue(fake.named("setUnitMuted").isEmpty())
        assertEquals(BT_MUSIC, fake.source)
        assertTrue(tuner.state.value.muted)
        tuner.powerOff()
    }

    @Test
    fun `power on without taking the speakers still restores while the radio plays`() = nwdTest {
        val fake = FakeNwd(source = Nwd.Source.RADIO)
        val tuner = tunerFor(fake, takeSpeakers = false)

        tuner.powerOn(Region.BRAZIL, 96_100)
        advanceTimeBy(5_000)

        assertEquals(listOf(listOf<Any?>(9_610, Nwd.Band.FM1, Nwd.NO_PRESET)), fake.tunes())
        tuner.powerOff()
    }

    // Units and bands.

    @Test
    fun `reads 10 kHz units from the radio point`() = nwdTest {
        val fake = FakeNwd()
        val tuner = tunerFor(fake)
        tuner.powerOn(Region.BRAZIL, 98_100)

        tuner.tune(96_100)

        assertEquals(listOf(listOf<Any?>(9_610, Nwd.Band.FM1, Nwd.NO_PRESET)), fake.tunes())
        assertEquals(96_100, tuner.state.value.frequencyKhz)
        assertEquals(Region.AMERICAS, tuner.state.value.region)
        tuner.powerOff()
    }

    @Test
    fun `reads 100 kHz units`() = nwdTest {
        val fake = FakeNwd(fmMin = 875, fmMax = 1_080, fmStep = 1, frequency = 981, stations = setOf(891, 961, 985))
        val tuner = tunerFor(fake)
        tuner.powerOn(Region.BRAZIL, 98_100)
        assertEquals(98_100, tuner.state.value.frequencyKhz)

        tuner.tune(96_100)

        assertEquals(listOf(listOf<Any?>(961, Nwd.Band.FM1, Nwd.NO_PRESET)), fake.tunes())
        assertEquals(96_100, tuner.state.value.frequencyKhz)
        assertEquals(98_500, tuner.seek(SeekDirection.UP)?.frequencyKhz)
        tuner.powerOff()
    }

    @Test
    fun `reads kHz units`() = nwdTest {
        val fake = FakeNwd(fmMin = 87_500, fmMax = 108_000, fmStep = 100, frequency = 98_100, stations = setOf(96_100))
        val tuner = tunerFor(fake)
        tuner.powerOn(Region.BRAZIL, 98_100)

        tuner.tune(89_100)

        assertEquals(listOf(listOf<Any?>(89_100, Nwd.Band.FM1, Nwd.NO_PRESET)), fake.tunes())
        assertEquals(89_100, tuner.state.value.frequencyKhz)
        tuner.powerOff()
    }

    @Test
    fun `falls back to the current FM frequency when the radio point is missing or empty`() = nwdTest {
        for (points in listOf(null, emptyList(), listOf(NwdRadioPoint(0, 0, 0), NwdRadioPoint(0, 0, 0)))) {
            val fake = FakeNwd(points = points)
            val tuner = tunerFor(fake)
            tuner.powerOn(Region.BRAZIL, 98_100)

            tuner.tune(96_100)

            assertEquals(listOf(listOf<Any?>(9_610, Nwd.Band.FM1, Nwd.NO_PRESET)), fake.tunes())
            // The app's region stays: the service did not say what its band is.
            assertEquals(Region.BRAZIL, tuner.state.value.region)
            tuner.powerOff()
        }

        val fake = FakeNwd(points = null, fmMin = 875, fmMax = 1_080, fmStep = 1, frequency = 981)
        val tuner = tunerFor(fake)
        tuner.powerOn(Region.BRAZIL, 98_100)
        tuner.tune(96_100)
        assertEquals(listOf(listOf<Any?>(961, Nwd.Band.FM1, Nwd.NO_PRESET)), fake.tunes())
        tuner.powerOff()
    }

    @Test
    fun `never takes the unit from an AM reading or a zero`() = nwdTest {
        // 1010 kHz would read as 101.0 MHz in 100 kHz units.
        val am = FakeNwd(points = null, band = Nwd.Band.AM1, amFrequency = 1_010, bandCommandWorks = false, changeBandWorks = false)
        val amTuner = tunerFor(am)
        amTuner.powerOn(Region.BRAZIL, 96_100)
        advanceTimeBy(10_000)
        assertEquals(listOf<Any?>(9_610, Nwd.Band.FM1, Nwd.NO_PRESET), am.tunes().first())
        assertEquals(9_610, am.frequency)
        amTuner.powerOff()

        val cold = FakeNwd(points = null, frequency = 0)
        val coldTuner = tunerFor(cold)
        coldTuner.powerOn(Region.BRAZIL, 96_100)
        advanceTimeBy(5_000)
        assertEquals(listOf(listOf<Any?>(9_610, Nwd.Band.FM1, Nwd.NO_PRESET)), cold.tunes())
        assertEquals(96_100, coldTuner.state.value.frequencyKhz)
        coldTuner.powerOff()
    }

    @Test
    fun `maps the service band to the closest region`() = nwdTest {
        val cases = mapOf(
            Triple(7_600, 10_800, 10) to Region.BRAZIL,
            Triple(8_750, 10_790, 20) to Region.USA,
            Triple(8_750, 10_800, 10) to Region.AMERICAS,
            Triple(8_750, 10_800, 5) to Region.EUROPE,
        )
        for ((band, region) in cases) {
            val fake = FakeNwd(fmMin = band.first, fmMax = band.second, fmStep = band.third, frequency = 9_810)
            val tuner = tunerFor(fake)
            tuner.powerOn(Region.JAPAN, 98_100)
            assertEquals(region, tuner.state.value.region)
            tuner.powerOff()
        }
    }

    @Test
    fun `a radio point notice rebuilds the band`() = nwdTest {
        val fake = FakeNwd(points = null)
        val tuner = tunerFor(fake)
        tuner.powerOn(Region.BRAZIL, 98_100)

        fake.pushRadioPoint(listOf(NwdRadioPoint(8_750, 10_790, 20), NwdRadioPoint(530, 1_710, 10)))
        assertEquals(Region.USA, tuner.state.value.region)
        // An AM entry or a placeholder alone changes nothing.
        fake.pushRadioPoint(listOf(NwdRadioPoint(0, 0, 0), NwdRadioPoint(530, 1_710, 10)))
        assertEquals(Region.USA, tuner.state.value.region)
        tuner.powerOff()
    }

    @Test
    fun `sends preset index 0 and the tracked FM band`() = nwdTest {
        val fake = FakeNwd(band = Nwd.Band.FM2)
        val tuner = tunerFor(fake)
        tuner.powerOn(Region.BRAZIL, 98_100)

        tuner.tune(96_100)

        assertEquals(listOf(listOf<Any?>(9_610, Nwd.Band.FM2, Nwd.NO_PRESET)), fake.tunes())
        assertEquals(Nwd.Band.FM2, fake.band)
        assertTrue(fake.named("sendRadioCommand").isEmpty())
        assertTrue(fake.named("changeBand").isEmpty())
        tuner.powerOff()
    }

    @Test
    fun `selects FM1 with the band command when the service is on AM`() = nwdTest {
        val fake = FakeNwd()
        val tuner = tunerFor(fake)
        tuner.powerOn(Region.BRAZIL, 98_100)

        fake.goToAm(1_010)
        // The dial keeps the last FM station while the service is on AM.
        assertEquals(98_100, tuner.state.value.frequencyKhz)
        tuner.tune(96_100)

        assertEquals(listOf(listOf<Any?>(Nwd.Command.SELECT_BAND + Nwd.Band.FM1, Nwd.Command.ON)), fake.argsOf("sendRadioCommand"))
        assertTrue(fake.named("changeBand").isEmpty())
        assertEquals(listOf(listOf<Any?>(9_610, Nwd.Band.FM1, Nwd.NO_PRESET)), fake.tunes())
        assertEquals(Nwd.Band.FM1, fake.band)
        assertEquals(96_100, tuner.state.value.frequencyKhz)
        tuner.powerOff()
    }

    @Test
    fun `powers on in FM when the service is on AM`() = nwdTest {
        val fake = FakeNwd(band = Nwd.Band.AM2)
        val tuner = tunerFor(fake)

        tuner.powerOn(Region.BRAZIL, 98_100)

        assertEquals(1, fake.named("sendRadioCommand").size)
        assertEquals(Nwd.Band.FM1, fake.band)
        assertEquals(98_100, tuner.state.value.frequencyKhz)
        tuner.powerOff()
    }

    @Test
    fun `cycles the band at most once per 550 ms when the band command does nothing`() = nwdTest {
        val fake = FakeNwd(bandCommandWorks = false)
        val tuner = tunerFor(fake)
        tuner.powerOn(Region.BRAZIL, 98_100)

        fake.goToAm(1_010)
        tuner.tune(96_100)

        val changes = fake.named("changeBand")
        // AM1, AM2, FM1.
        assertEquals(2, changes.size)
        assertTrue(changes[1].atMs - changes[0].atMs >= 550)
        assertEquals(Nwd.Band.FM1, fake.band)
        assertEquals(9_610, fake.frequency)
        assertEquals(96_100, tuner.state.value.frequencyKhz)
        tuner.powerOff()
    }

    @Test
    fun `stops cycling when a band change does nothing and tunes FM1 directly`() = nwdTest {
        val fake = FakeNwd(bandCommandWorks = false, changeBandWorks = false)
        val tuner = tunerFor(fake)
        tuner.powerOn(Region.BRAZIL, 98_100)

        fake.goToAm(1_010)
        tuner.tune(96_100)

        assertEquals(1, fake.named("changeBand").size)
        assertEquals(listOf(listOf<Any?>(9_610, Nwd.Band.FM1, Nwd.NO_PRESET)), fake.tunes())
        assertEquals(Nwd.Band.FM1, fake.band)
        assertEquals(96_100, tuner.state.value.frequencyKhz)
        tuner.powerOff()
    }

    @Test
    fun `gives up cycling after six changes`() = nwdTest {
        val fake = FakeNwd(bandCommandWorks = false, bandCycle = listOf(Nwd.Band.AM1, Nwd.Band.AM2))
        val tuner = tunerFor(fake)
        tuner.powerOn(Region.BRAZIL, 98_100)

        fake.goToAm(1_010)
        tuner.tune(96_100)

        assertEquals(6, fake.named("changeBand").size)
        assertEquals(96_100, tuner.state.value.frequencyKhz)
        tuner.powerOff()
    }

    @Test
    fun `allwinner services cycle the band before the band command`() = nwdTest {
        val fake = FakeNwd(type = Nwd.RadioType.ALLWINNER, source = Nwd.Source.RADIO)
        val tuner = tunerFor(fake)
        tuner.powerOn(Region.BRAZIL, 98_100)

        fake.goToAm(1_010)
        tuner.tune(96_100)

        assertEquals(2, fake.named("changeBand").size)
        assertTrue(fake.named("sendRadioCommand").isEmpty())
        assertEquals(96_100, tuner.state.value.frequencyKhz)
        tuner.powerOff()
    }

    @Test
    fun `a service that ignores the band of a tune is brought to FM by cycling`() = nwdTest {
        val fake = FakeNwd(bandCommandWorks = false, honoursTuneBand = false)
        val tuner = tunerFor(fake)
        tuner.powerOn(Region.BRAZIL, 98_100)

        fake.goToAm(1_010)
        tuner.tune(96_100)

        assertEquals(2, fake.named("changeBand").size)
        assertEquals(1, fake.tunes().size)
        assertEquals(9_610, fake.frequency)
        assertEquals(96_100, tuner.state.value.frequencyKhz)
        tuner.powerOff()
    }

    @Test
    fun `a tune the service cannot take out of AM gives up without looping`() = nwdTest {
        val fake = FakeNwd(bandCommandWorks = false, changeBandWorks = false, honoursTuneBand = false)
        val tuner = tunerFor(fake)
        tuner.powerOn(Region.BRAZIL, 98_100)

        fake.goToAm(1_010)
        tuner.tune(96_100)
        val sent = fake.commands.size
        advanceTimeBy(10_000)

        assertEquals(PowerState.ON, tuner.state.value.power)
        // One resend, as the first tune was ignored, and nothing after.
        assertEquals(2, fake.tunes().size)
        assertEquals(sent, fake.commands.size)
        assertEquals(98_100, tuner.state.value.frequencyKhz)
        assertTrue(Nwd.Band.isAm(fake.band))
        tuner.powerOff()
    }

    // Tuning.

    @Test
    fun `a tune is confirmed by readback when no callback arrives`() = nwdTest {
        val fake = FakeNwd(deliversCallbacks = false, scope = backgroundScope, tuneDelayMs = 300)
        val tuner = tunerFor(fake)
        tuner.powerOn(Region.BRAZIL, 98_100)

        tuner.tune(96_100)

        assertEquals(1, fake.tunes().size)
        assertEquals(96_100, tuner.state.value.frequencyKhz)
        tuner.powerOff()
    }

    @Test
    fun `a tune is confirmed by a callback while the readback lags`() = nwdTest {
        val fake = FakeNwd()
        val tuner = tunerFor(fake)
        tuner.powerOn(Region.BRAZIL, 98_100)
        fake.frozenReading = fake.current()

        val start = testScheduler.currentTime
        tuner.tune(96_100)

        assertTrue(testScheduler.currentTime - start < 1_600)
        assertEquals(1, fake.tunes().size)
        assertEquals(96_100, tuner.state.value.frequencyKhz)
        tuner.powerOff()
    }

    @Test
    fun `ignores frequency notices that contradict a fresh tune for 1_5 s, with the name they carry`() = nwdTest {
        val fake = FakeNwd(scope = backgroundScope, tuneDelayMs = 800, names = mapOf(9_610 to "NOVA FM"))
        val tuner = tunerFor(fake)
        tuner.powerOn(Region.BRAZIL, 98_100)

        val tuning = launch { tuner.tune(96_100) }
        advanceTimeBy(300)
        fake.notify(9_810, "ANTIGA")
        runCurrent()
        assertEquals(96_100, tuner.state.value.frequencyKhz)
        assertNull(tuner.state.value.rds.programService)

        advanceTimeBy(3_000)
        assertTrue(tuning.isCompleted)
        assertEquals(96_100, tuner.state.value.frequencyKhz)
        assertEquals("NOVA FM", tuner.state.value.rds.programService)
        assertEquals(1, fake.tunes().size)
        tuner.powerOff()
    }

    @Test
    fun `accepts a different frequency once the window is over`() = nwdTest {
        val fake = FakeNwd(ignoresTunes = 1)
        val tuner = tunerFor(fake)
        tuner.powerOn(Region.BRAZIL, 98_100)

        // A tune cancelled while the service ignores it leaves its window open.
        val tuning = launch { tuner.tune(96_100) }
        advanceTimeBy(200)
        tuning.cancelAndJoin()
        fake.pressStockKey(9_890)
        assertEquals(96_100, tuner.state.value.frequencyKhz)

        advanceTimeBy(2_500)
        assertEquals(98_900, tuner.state.value.frequencyKhz)

        // A confirmed tune closes its window at once.
        tuner.tune(96_100)
        fake.pressStockKey(9_850)
        assertEquals(98_500, tuner.state.value.frequencyKhz)
        tuner.powerOff()
    }

    @Test
    fun `resends a tune once when the service ignored it`() = nwdTest {
        val fake = FakeNwd(ignoresTunes = 1)
        val tuner = tunerFor(fake)
        tuner.powerOn(Region.BRAZIL, 98_100)

        tuner.tune(96_100)

        val tunes = fake.named("setCurrentFrequency")
        assertEquals(2, tunes.size)
        assertTrue(tunes[1].atMs - tunes[0].atMs >= 1_600)
        assertEquals(9_610, fake.frequency)
        assertEquals(96_100, tuner.state.value.frequencyKhz)
        tuner.powerOff()
    }

    @Test
    fun `does not resend when the dial moved elsewhere`() = nwdTest {
        val fake = FakeNwd(ignoresTunes = 1)
        val tuner = tunerFor(fake)
        tuner.powerOn(Region.BRAZIL, 98_100)

        val tuning = launch { tuner.tune(96_100) }
        advanceTimeBy(500)
        fake.pressStockKey(9_850)
        advanceTimeBy(5_000)

        assertTrue(tuning.isCompleted)
        assertEquals(1, fake.tunes().size)
        assertEquals(98_500, tuner.state.value.frequencyKhz)
        tuner.powerOff()
    }

    @Test
    fun `rapid step tunes end on the last target`() = nwdTest {
        val fake = FakeNwd(scope = backgroundScope, tuneDelayMs = 300)
        val tuner = tunerFor(fake)
        tuner.powerOn(Region.BRAZIL, 98_100)

        // As RadioController does: each tap cancels and joins the tune before it.
        var operation: Job? = null
        fun step(khz: Int) {
            val previous = operation
            operation = launch {
                previous?.cancelAndJoin()
                tuner.tune(khz)
            }
        }
        step(98_300)
        advanceTimeBy(110)
        step(98_500)
        advanceTimeBy(110)
        step(98_700)
        advanceTimeBy(110)
        step(98_900)
        advanceTimeBy(5_000)

        assertEquals(98_900, tuner.state.value.frequencyKhz)
        assertEquals(9_890, fake.frequency)
        assertEquals(listOf<Any?>(9_890, Nwd.Band.FM1, Nwd.NO_PRESET), fake.tunes().last())
        tuner.powerOff()
    }

    @Test
    fun `tuning while a seek runs waits for it to end`() = nwdTest {
        val fake = FakeNwd(frequency = 9_300, stations = setOf(10_630), scope = backgroundScope)
        val tuner = tunerFor(fake)
        tuner.powerOn(Region.BRAZIL, 93_000)

        val seeking = launch { tuner.seek(SeekDirection.UP) }
        advanceTimeBy(300)
        seeking.cancel()
        tuner.tune(95_000)
        advanceTimeBy(5_000)

        assertTrue(fake.named("setCurrentFrequency").single().atMs >= 2_600)
        assertEquals(95_000, tuner.state.value.frequencyKhz)
        assertEquals(9_500, fake.frequency)
        assertEquals(TunerActivity.IDLE, tuner.state.value.activity)
        tuner.powerOff()
    }

    @Test
    fun `shows the requested frequency at once and the real one when the tune fails`() = nwdTest {
        val fake = FakeNwd(ignoresTunes = 2)
        val tuner = tunerFor(fake)
        tuner.powerOn(Region.BRAZIL, 98_100)

        val tuning = launch { tuner.tune(96_100) }
        advanceTimeBy(50)
        assertEquals(96_100, tuner.state.value.frequencyKhz)
        advanceTimeBy(5_000)

        assertTrue(tuning.isCompleted)
        assertEquals(2, fake.tunes().size)
        assertEquals(98_100, tuner.state.value.frequencyKhz)
        tuner.powerOff()
    }

    @Test
    fun `a tune to where the service already is sends nothing`() = nwdTest {
        val fake = FakeNwd()
        val tuner = tunerFor(fake)
        tuner.powerOn(Region.BRAZIL, 98_100)

        tuner.tune(98_100)

        assertTrue(fake.tunes().isEmpty())
        tuner.powerOff()
    }

    // Power on and waking up.

    @Test
    fun `power on restores the saved station when the service is elsewhere`() = nwdTest {
        val fake = FakeNwd()
        val tuner = tunerFor(fake)

        tuner.powerOn(Region.BRAZIL, 96_100)
        advanceTimeBy(5_000)

        assertEquals(listOf(listOf<Any?>(9_610, Nwd.Band.FM1, Nwd.NO_PRESET)), fake.tunes())
        assertEquals(96_100, tuner.state.value.frequencyKhz)
        tuner.powerOff()
    }

    @Test
    fun `power on keeps the station when it already matches`() = nwdTest {
        val fake = FakeNwd()
        val tuner = tunerFor(fake)

        tuner.powerOn(Region.BRAZIL, 98_100)
        advanceTimeBy(5_000)

        assertTrue(fake.tunes().isEmpty())
        assertEquals(98_100, tuner.state.value.frequencyKhz)
        tuner.powerOff()
    }

    @Test
    fun `power on restores after a cold zero reading`() = nwdTest {
        val fake = FakeNwd(frequency = 0)
        val tuner = tunerFor(fake)

        tuner.powerOn(Region.BRAZIL, 96_100)
        advanceTimeBy(5_000)

        assertEquals(listOf(listOf<Any?>(9_610, Nwd.Band.FM1, Nwd.NO_PRESET)), fake.tunes())
        assertEquals(96_100, tuner.state.value.frequencyKhz)
        tuner.powerOff()
    }

    @Test
    fun `power on resends the restore once after 1_6 s`() = nwdTest {
        val fake = FakeNwd(ignoresTunes = 1)
        val tuner = tunerFor(fake)

        tuner.powerOn(Region.BRAZIL, 96_100)
        advanceTimeBy(10_000)

        val tunes = fake.named("setCurrentFrequency")
        assertEquals(2, tunes.size)
        assertTrue(tunes[1].atMs - tunes[0].atMs >= 1_600)
        assertEquals(9_610, fake.frequency)
        assertEquals(96_100, tuner.state.value.frequencyKhz)
        tuner.powerOff()
    }

    @Test
    fun `a user tune cancels the pending restore`() = nwdTest {
        val fake = FakeNwd(ignoresTunes = 1)
        val tuner = tunerFor(fake)
        tuner.powerOn(Region.BRAZIL, 96_100)
        advanceTimeBy(500)

        tuner.tune(89_100)
        advanceTimeBy(10_000)

        assertEquals(
            listOf(listOf<Any?>(9_610, Nwd.Band.FM1, Nwd.NO_PRESET), listOf<Any?>(8_910, Nwd.Band.FM1, Nwd.NO_PRESET)),
            fake.tunes(),
        )
        assertEquals(89_100, tuner.state.value.frequencyKhz)
        tuner.powerOff()
    }

    @Test
    fun `restores the station after the unit wakes up`() = nwdTest {
        val fake = FakeNwd()
        val tuner = tunerFor(fake)
        tuner.powerOn(Region.BRAZIL, 98_100)

        fake.sleep(forgetTo = 8_750)
        advanceTimeBy(3_000)
        fake.wake()
        advanceTimeBy(5_000)

        assertEquals(listOf(listOf<Any?>(9_810, Nwd.Band.FM1, Nwd.NO_PRESET)), fake.tunes())
        assertEquals(9_810, fake.frequency)
        assertEquals(98_100, tuner.state.value.frequencyKhz)
        assertEquals(1, fake.named("requestSource").size)
        tuner.powerOff()
    }

    @Test
    fun `wake does not claim the source`() = nwdTest {
        val fake = FakeNwd()
        val tuner = tunerFor(fake)
        tuner.powerOn(Region.BRAZIL, 98_100)

        fake.sleep(forgetTo = 8_750)
        fake.switchSource(BT_MUSIC)
        advanceTimeBy(3_000)
        fake.wake()
        advanceTimeBy(10_000)

        assertEquals(1, fake.named("requestSource").size)
        assertTrue(fake.named("sendAppInOut").isEmpty())
        assertTrue(fake.tunes().isEmpty())
        assertEquals(BT_MUSIC, fake.source)
        tuner.powerOff()
    }

    @Test
    fun `polling pauses while the unit sleeps`() = nwdTest {
        val fake = FakeNwd(deliversCallbacks = false)
        val tuner = tunerFor(fake)
        tuner.powerOn(Region.BRAZIL, 98_100)
        advanceTimeBy(2_500)
        assertTrue(fake.readsOf("getCurrentFrequency") > 0)

        fake.sleep()
        advanceTimeBy(1_100)
        val asleep = fake.readsOf("getCurrentFrequency")
        advanceTimeBy(5_000)
        assertEquals(asleep, fake.readsOf("getCurrentFrequency"))
        assertTrue(fake.readsOf("mcuState") > 0)

        fake.wake()
        advanceTimeBy(3_000)
        assertTrue(fake.readsOf("getCurrentFrequency") > asleep)
        tuner.powerOff()
    }

    @Test
    fun `notices the unit waking up by polling too`() = nwdTest {
        val fake = FakeNwd()
        val tuner = tunerFor(fake)
        tuner.powerOn(Region.BRAZIL, 98_100)

        fake.sleep(forgetTo = 8_750)
        advanceTimeBy(3_000)
        // No broadcast this time.
        fake.mcuState = Nwd.McuState.AWAKE
        advanceTimeBy(5_000)

        assertEquals(9_810, fake.frequency)
        tuner.powerOff()
    }

    // Seeking.

    @Test
    fun `seeks with search true for up by default`() = nwdTest {
        val fake = FakeNwd(frequency = 9_000)
        val tuner = tunerFor(fake)
        tuner.powerOn(Region.BRAZIL, 90_000)

        val up = tuner.seek(SeekDirection.UP)
        assertEquals(96_100, up?.frequencyKhz)
        assertNull(up?.rssiDbuv)
        assertEquals(89_100, tuner.seek(SeekDirection.DOWN)?.frequencyKhz)

        assertEquals(listOf(listOf<Any?>(true), listOf<Any?>(false)), fake.argsOf("search"))
        assertTrue(fake.named("seek").isEmpty())
        assertEquals(TunerActivity.IDLE, tuner.state.value.activity)
        tuner.powerOff()
    }

    @Test
    fun `learns that true means down and keeps it for the session`() = nwdTest {
        val fake = FakeNwd(frequency = 9_000, trueMeansUp = false)
        val tuner = tunerFor(fake)
        tuner.powerOn(Region.BRAZIL, 90_000)

        // The first try goes the wrong way; it still is a station.
        assertEquals(89_100, tuner.seek(SeekDirection.UP)?.frequencyKhz)
        assertEquals(96_100, tuner.seek(SeekDirection.UP)?.frequencyKhz)
        assertEquals(89_100, tuner.seek(SeekDirection.DOWN)?.frequencyKhz)

        assertEquals(listOf(listOf<Any?>(true), listOf<Any?>(false), listOf<Any?>(true)), fake.argsOf("search"))
        tuner.powerOff()
    }

    @Test
    fun `switches to seek when search only moves one channel without a busy state`() = nwdTest {
        val fake = FakeNwd(frequency = 9_000, stationSeekCall = SeekCall.SEEK)
        val tuner = tunerFor(fake)
        tuner.powerOn(Region.BRAZIL, 90_000)

        assertEquals(96_100, tuner.seek(SeekDirection.UP)?.frequencyKhz)
        assertEquals(98_500, tuner.seek(SeekDirection.UP)?.frequencyKhz)

        assertEquals(listOf("search", "seek", "seek"), fake.commands.map { it.name }.filter { it == "seek" || it == "search" })
        tuner.powerOff()
    }

    @Test
    fun `keeps search when a one channel move came with a busy state`() = nwdTest {
        val fake = FakeNwd(frequency = 9_000, stations = setOf(9_010, 9_610))
        val tuner = tunerFor(fake)
        tuner.powerOn(Region.BRAZIL, 90_000)

        assertEquals(90_100, tuner.seek(SeekDirection.UP)?.frequencyKhz)
        assertEquals(96_100, tuner.seek(SeekDirection.UP)?.frequencyKhz)

        assertTrue(fake.named("seek").isEmpty())
        tuner.powerOff()
    }

    @Test
    fun `tries the other call when the first one does nothing`() = nwdTest {
        val fake = FakeNwd(frequency = 9_000, stationSeekCall = SeekCall.SEEK, inertCall = SeekCall.SEARCH)
        val tuner = tunerFor(fake)
        tuner.powerOn(Region.BRAZIL, 90_000)

        assertEquals(96_100, tuner.seek(SeekDirection.UP)?.frequencyKhz)
        assertEquals(98_500, tuner.seek(SeekDirection.UP)?.frequencyKhz)

        assertEquals(listOf("search", "seek", "seek"), fake.commands.map { it.name }.filter { it == "seek" || it == "search" })
        tuner.powerOff()
    }

    @Test
    fun `switches at most twice per session`() = nwdTest {
        // Neither call stops on stations: both move one channel.
        val fake = FakeNwd(frequency = 9_000, stationSeekCall = null)
        val tuner = tunerFor(fake)
        tuner.powerOn(Region.BRAZIL, 90_000)

        repeat(3) { tuner.seek(SeekDirection.UP) }

        assertEquals(
            listOf("search", "seek", "search", "search"),
            fake.commands.map { it.name }.filter { it == "seek" || it == "search" },
        )
        assertEquals(90_400, tuner.state.value.frequencyKhz)
        tuner.powerOff()
    }

    @Test
    fun `treats a wrap around the band edge as the requested direction`() = nwdTest {
        val fake = FakeNwd(frequency = 10_700, stations = setOf(8_910, 10_630))
        val tuner = tunerFor(fake)
        tuner.powerOn(Region.BRAZIL, 107_000)

        assertEquals(89_100, tuner.seek(SeekDirection.UP)?.frequencyKhz)
        assertEquals(106_300, tuner.seek(SeekDirection.UP)?.frequencyKhz)
        assertEquals(89_100, tuner.seek(SeekDirection.UP)?.frequencyKhz)

        assertEquals(listOf(listOf<Any?>(true), listOf<Any?>(true), listOf<Any?>(true)), fake.argsOf("search"))
        tuner.powerOff()
    }

    @Test
    fun `learns the direction from the first move even across the band edge`() = nwdTest {
        // search(true) goes down here, and from 88.1 it wraps to the top of the band, which
        // end to end looks like a long way up.
        val fake = FakeNwd(frequency = 8_810, stations = setOf(10_630), trueMeansUp = false, scope = backgroundScope)
        val tuner = tunerFor(fake)
        tuner.powerOn(Region.BRAZIL, 88_100)

        tuner.seek(SeekDirection.UP)
        tuner.seek(SeekDirection.UP)

        assertEquals(listOf(listOf<Any?>(true), listOf<Any?>(false)), fake.argsOf("search"))
        tuner.powerOff()
    }

    @Test
    fun `seek with no station returns null`() = nwdTest {
        val fake = FakeNwd(stations = emptySet())
        val tuner = tunerFor(fake)
        tuner.powerOn(Region.BRAZIL, 98_100)

        assertNull(tuner.seek(SeekDirection.UP))

        assertEquals(98_100, tuner.state.value.frequencyKhz)
        assertEquals(TunerActivity.IDLE, tuner.state.value.activity)
        // The empty sweep showed busy: search stays the station seek.
        assertTrue(fake.named("seek").isEmpty())
        tuner.powerOff()
    }

    @Test
    fun `seek follows the busy state of a slow service`() = nwdTest {
        val fake = FakeNwd(frequency = 9_000, scope = backgroundScope)
        val tuner = tunerFor(fake)
        tuner.powerOn(Region.BRAZIL, 90_000)

        val seeking = launch { assertEquals(96_100, tuner.seek(SeekDirection.UP)?.frequencyKhz) }
        advanceTimeBy(500)
        assertEquals(TunerActivity.SEEKING, tuner.state.value.activity)
        advanceTimeBy(5_000)

        assertTrue(seeking.isCompleted)
        assertEquals(96_100, tuner.state.value.frequencyKhz)
        assertEquals(TunerActivity.IDLE, tuner.state.value.activity)
        assertEquals(1, fake.named("search").size)
        tuner.powerOff()
    }

    @Test
    fun `seeks by polling when callbacks never arrive`() = nwdTest {
        val fake = FakeNwd(frequency = 9_000, deliversCallbacks = false, scope = backgroundScope)
        val tuner = tunerFor(fake)
        tuner.powerOn(Region.BRAZIL, 90_000)

        assertEquals(96_100, tuner.seek(SeekDirection.UP)?.frequencyKhz)
        assertEquals(98_500, tuner.seek(SeekDirection.UP)?.frequencyKhz)

        assertEquals(2, fake.named("search").size)
        assertTrue(fake.named("seek").isEmpty())
        tuner.powerOff()
    }

    @Test
    fun `seek waits for the dial to settle when the service reports no busy state`() = nwdTest {
        val fake = FakeNwd(frequency = 9_000, reportsBusy = false, scope = backgroundScope)
        val tuner = tunerFor(fake)
        tuner.powerOn(Region.BRAZIL, 90_000)

        assertEquals(96_100, tuner.seek(SeekDirection.UP)?.frequencyKhz)

        assertEquals(1, fake.named("search").size)
        assertTrue(fake.named("seek").isEmpty())
        tuner.powerOff()
    }

    @Test
    fun `scan finds every station in frequency order`() = nwdTest {
        val fake = FakeNwd()
        val tuner = tunerFor(fake)
        tuner.powerOn(Region.BRAZIL, 98_100)

        val found = tuner.scanBand()

        assertEquals(listOf(89_100, 96_100, 98_500, 106_300), found.map { it.frequencyKhz })
        assertEquals(89_100, tuner.state.value.frequencyKhz)
        tuner.powerOff()
    }

    @Test
    fun `a new tuner instance learns again`() = nwdTest {
        val first = FakeNwd(frequency = 9_000, trueMeansUp = false)
        val firstTuner = tunerFor(first)
        firstTuner.powerOn(Region.BRAZIL, 90_000)
        firstTuner.seek(SeekDirection.UP)
        firstTuner.seek(SeekDirection.UP)
        firstTuner.powerOff()

        val second = FakeNwd(frequency = 9_000, trueMeansUp = false)
        val secondTuner = tunerFor(second)
        secondTuner.powerOn(Region.BRAZIL, 90_000)
        secondTuner.seek(SeekDirection.UP)

        assertEquals(listOf(listOf<Any?>(true), listOf<Any?>(false)), first.argsOf("search"))
        assertEquals(listOf(listOf<Any?>(true)), second.argsOf("search"))
        secondTuner.powerOff()
    }

    @Test
    fun `seeking takes the speakers back and returns to FM`() = nwdTest {
        val fake = FakeNwd(frequency = 9_000)
        val tuner = tunerFor(fake)
        tuner.powerOn(Region.BRAZIL, 90_000)
        advanceTimeBy(3_000)
        fake.switchSource(BT_MUSIC)
        fake.goToAm(1_010)

        assertEquals(96_100, tuner.seek(SeekDirection.UP)?.frequencyKhz)

        assertEquals(Nwd.Source.RADIO, fake.source)
        assertEquals(Nwd.Band.FM1, fake.band)
        tuner.powerOff()
    }

    // Mute and source.

    @Test
    fun `power on makes the radio the source and unmutes the unit`() = nwdTest {
        val fake = FakeNwd(muted = true)
        val tuner = tunerFor(fake)

        tuner.powerOn(Region.BRAZIL, 98_100)

        assertEquals(listOf(listOf<Any?>(false)), fake.argsOf("setUnitMuted"))
        assertFalse(fake.muted)
        assertEquals(Nwd.Source.RADIO, fake.source)
        assertFalse(tuner.state.value.muted)
        tuner.powerOff()
    }

    @Test
    fun `mute is sent only while the radio is the source`() = nwdTest {
        val fake = FakeNwd()
        val tuner = tunerFor(fake)
        tuner.powerOn(Region.BRAZIL, 98_100)

        tuner.setMuted(true)
        assertTrue(fake.muted)
        assertTrue(tuner.state.value.muted)
        tuner.setMuted(false)
        assertFalse(fake.muted)
        assertFalse(tuner.state.value.muted)

        fake.switchSource(BT_MUSIC)
        assertTrue(tuner.state.value.muted)
        tuner.setMuted(true)
        assertEquals(listOf(listOf<Any?>(true), listOf<Any?>(false)), fake.argsOf("setUnitMuted"))
        tuner.powerOff()
    }

    @Test
    fun `the app's mute is lifted when another source takes over`() = nwdTest {
        val fake = FakeNwd()
        val tuner = tunerFor(fake)
        tuner.powerOn(Region.BRAZIL, 98_100)
        tuner.setMuted(true)

        fake.switchSource(BT_MUSIC)

        assertFalse(fake.muted)
        assertTrue(tuner.state.value.muted)
        tuner.powerOff()
        // Already lifted: power off has nothing to undo.
        assertEquals(listOf(listOf<Any?>(true), listOf<Any?>(false)), fake.argsOf("setUnitMuted"))
    }

    @Test
    fun `a mute the app did not set is left alone when the source changes`() = nwdTest {
        val fake = FakeNwd()
        val tuner = tunerFor(fake)
        tuner.powerOn(Region.BRAZIL, 98_100)

        fake.setMute(true)
        fake.switchSource(BT_MUSIC)

        assertTrue(fake.muted)
        assertTrue(fake.named("setUnitMuted").isEmpty())
        tuner.powerOff()
        assertTrue(fake.named("setUnitMuted").isEmpty())
    }

    @Test
    fun `power off undoes the app's mute and leaves the source alone`() = nwdTest {
        val fake = FakeNwd()
        val tuner = tunerFor(fake)
        tuner.powerOn(Region.BRAZIL, 98_100)
        tuner.setMuted(true)

        tuner.powerOff()

        assertFalse(fake.muted)
        assertEquals(Nwd.Source.RADIO, fake.source)
        assertEquals(1, fake.named("requestSource").size)
        assertEquals(PowerState.OFF, tuner.state.value.power)
    }

    @Test
    fun `power off unregisters the callback and closes the service`() = nwdTest {
        val fake = FakeNwd()
        val tuner = tunerFor(fake)
        tuner.powerOn(Region.BRAZIL, 96_100)
        advanceTimeBy(5_000)

        tuner.powerOff()
        advanceTimeBy(10_000)

        assertFalse(fake.registered)
        assertTrue(fake.closed)
        assertTrue(fake.callsAfterClose.isEmpty())
        assertEquals(PowerState.OFF, tuner.state.value.power)
        assertEquals(TunerActivity.IDLE, tuner.state.value.activity)
    }

    @Test
    fun `power off during a restore stops it`() = nwdTest {
        val fake = FakeNwd(ignoresTunes = 1)
        val tuner = tunerFor(fake)
        tuner.powerOn(Region.BRAZIL, 96_100)
        advanceTimeBy(500)

        tuner.powerOff()
        advanceTimeBy(10_000)

        assertEquals(1, fake.tunes().size)
        assertTrue(fake.callsAfterClose.isEmpty())
    }

    @Test
    fun `reclaiming keeps the user's mute while the radio stays the source`() = nwdTest {
        val fake = FakeNwd()
        val tuner = tunerFor(fake)
        tuner.powerOn(Region.BRAZIL, 98_100)
        tuner.setMuted(true)
        val sent = fake.commands.size

        tuner.reclaimSource()

        assertEquals(sent, fake.commands.size)
        assertTrue(fake.muted)
        assertTrue(tuner.state.value.muted)
        tuner.powerOff()
    }

    @Test
    fun `reclaiming takes the source back from another one`() = nwdTest {
        val fake = FakeNwd()
        val tuner = tunerFor(fake)
        tuner.powerOn(Region.BRAZIL, 98_100)
        advanceTimeBy(3_000)
        fake.switchSource(BT_MUSIC)

        tuner.reclaimSource()

        assertEquals(2, fake.named("requestSource").size)
        assertEquals(Nwd.Source.RADIO, fake.source)
        assertFalse(tuner.state.value.muted)
        tuner.powerOff()
    }

    @Test
    fun `reclaiming does nothing when the source is unknown`() = nwdTest {
        val fake = FakeNwd(sourceReadable = false)
        val tuner = tunerFor(fake)
        tuner.powerOn(Region.BRAZIL, 98_100)
        advanceTimeBy(3_000)
        val sent = fake.commands.size

        tuner.reclaimSource()

        assertEquals(sent, fake.commands.size)
        tuner.powerOff()
    }

    @Test
    fun `reclaiming after a silent source change lifts the app's mute`() = nwdTest {
        val fake = FakeNwd()
        val tuner = tunerFor(fake)
        tuner.powerOn(Region.BRAZIL, 98_100)
        tuner.setMuted(true)
        advanceTimeBy(3_050)

        // No broadcast, and no poll yet: reclaiming is the first to see it.
        fake.source = BT_MUSIC
        tuner.reclaimSource()

        assertFalse(fake.muted)
        assertEquals(Nwd.Source.RADIO, fake.source)
        assertFalse(tuner.state.value.muted)
        assertEquals(listOf(listOf<Any?>(true), listOf<Any?>(false)), fake.argsOf("setUnitMuted"))
        tuner.powerOff()
    }

    @Test
    fun `unmuting takes the source back`() = nwdTest {
        val fake = FakeNwd()
        val tuner = tunerFor(fake)
        tuner.powerOn(Region.BRAZIL, 98_100)
        advanceTimeBy(3_000)
        fake.switchSource(BT_MUSIC)

        tuner.setMuted(false)

        assertEquals(Nwd.Source.RADIO, fake.source)
        assertFalse(tuner.state.value.muted)
        tuner.powerOff()
    }

    @Test
    fun `muted follows the unit's mute state and the source`() = nwdTest {
        val fake = FakeNwd()
        val tuner = tunerFor(fake)
        tuner.powerOn(Region.BRAZIL, 98_100)

        fake.setMute(true)
        assertTrue(tuner.state.value.muted)
        fake.setMute(false)
        assertFalse(tuner.state.value.muted)
        fake.switchSource(BT_MUSIC)
        assertTrue(tuner.state.value.muted)
        fake.switchSource(Nwd.Source.RADIO)
        assertFalse(tuner.state.value.muted)
        tuner.powerOff()
    }

    @Test
    fun `an optimistic mute is corrected when the unit does not mute`() = nwdTest {
        val fake = FakeNwd(setMuteWorks = false)
        val tuner = tunerFor(fake)
        tuner.powerOn(Region.BRAZIL, 98_100)

        tuner.setMuted(true)
        assertTrue(tuner.state.value.muted)
        advanceTimeBy(1_500)

        assertFalse(tuner.state.value.muted)
        tuner.powerOff()
        // The app still undoes what it sent.
        assertEquals(listOf(listOf<Any?>(true), listOf<Any?>(false)), fake.argsOf("setUnitMuted"))
    }

    @Test
    fun `pausing while another source plays does not stick the mute icon`() = nwdTest {
        val fake = FakeNwd()
        val tuner = tunerFor(fake)
        tuner.powerOn(Region.BRAZIL, 98_100)
        fake.switchSource(BT_MUSIC)

        tuner.setMuted(true)
        fake.switchSource(Nwd.Source.RADIO)

        assertFalse(tuner.state.value.muted)
        assertTrue(fake.named("setUnitMuted").isEmpty())
        tuner.powerOff()
    }

    // Status.

    @Test
    fun `shows the stereo pilot, not the stereo setting`() = nwdTest {
        val fake = FakeNwd(stereoPilot = false, stereoSetting = true)
        val tuner = tunerFor(fake)
        tuner.powerOn(Region.BRAZIL, 98_100)
        assertFalse(tuner.state.value.signal.stereo)

        fake.pushPilot(true)

        assertTrue(tuner.state.value.signal.stereo)
        assertNull(tuner.state.value.signal.rssiDbuv)
        tuner.powerOff()
    }

    @Test
    fun `cleans the station name and ignores blanks and null`() = nwdTest {
        val fake = FakeNwd()
        val tuner = tunerFor(fake)
        tuner.powerOn(Region.BRAZIL, 98_100)

        fake.notify(9_810, "  POP 98\u0000 ")
        assertEquals("POP 98", tuner.state.value.rds.programService)
        for (junk in listOf("null", "NULL", "", "   ", "\u0001\u0002", "��", null)) {
            fake.notify(9_810, junk)
            assertEquals("POP 98", tuner.state.value.rds.programService)
        }
        fake.notify(9_810, "POP FM")
        assertEquals("POP FM", tuner.state.value.rds.programService)
        tuner.powerOff()
    }

    @Test
    fun `shows radio text from callbacks and from polling`() = nwdTest {
        val fake = FakeNwd()
        val tuner = tunerFor(fake)
        tuner.powerOn(Region.BRAZIL, 98_100)

        fake.pushRadioText(" Tocando agora ")
        assertEquals("Tocando agora", tuner.state.value.rds.radioText)

        fake.radioText = "Previsão do tempo"
        advanceTimeBy(1_100)
        assertEquals("Previsão do tempo", tuner.state.value.rds.radioText)
        tuner.powerOff()
    }

    @Test
    fun `drops a radio text poll that straddles a station change`() = nwdTest {
        val fake = FakeNwd(radioText = "Texto da antiga")
        val tuner = tunerFor(fake)
        tuner.powerOn(Region.BRAZIL, 98_100)
        fake.duringRtRead = {
            fake.duringRtRead = null
            fake.pressStockKey(9_850)
        }

        advanceTimeBy(1_050)

        assertEquals(1, fake.readsOf("getRtMessage"))
        assertEquals(98_500, tuner.state.value.frequencyKhz)
        assertNull(tuner.state.value.rds.radioText)
        tuner.powerOff()
    }

    @Test
    fun `clears RDS on a new station`() = nwdTest {
        val fake = FakeNwd()
        val tuner = tunerFor(fake)
        tuner.powerOn(Region.BRAZIL, 98_100)
        fake.notify(9_810, "POP 98")
        fake.pushRadioText("Tocando agora")

        tuner.tune(96_100)

        assertNull(tuner.state.value.rds.programService)
        assertNull(tuner.state.value.rds.radioText)
        tuner.powerOff()
    }

    @Test
    fun `RDS off hides the text and stops polling it`() = nwdTest {
        val fake = FakeNwd(radioText = "Tocando agora")
        val tuner = tunerFor(fake)
        tuner.powerOn(Region.BRAZIL, 98_100)
        advanceTimeBy(1_100)
        assertEquals("Tocando agora", tuner.state.value.rds.radioText)

        tuner.setRdsEnabled(false)
        val reads = fake.readsOf("getRtMessage")
        fake.pushRadioText("Outro texto")
        fake.notify(9_810, "POP 98")
        advanceTimeBy(5_000)

        assertNull(tuner.state.value.rds.radioText)
        assertNull(tuner.state.value.rds.programService)
        assertEquals(reads, fake.readsOf("getRtMessage"))
        assertTrue(fake.commands.none { it.name.contains("RDS", ignoreCase = true) })
        tuner.powerOff()
    }

    @Test
    fun `polls the state when callbacks never arrive`() = nwdTest {
        val fake = FakeNwd(deliversCallbacks = false)
        val tuner = tunerFor(fake)
        tuner.powerOn(Region.BRAZIL, 98_100)
        // Past the power on restore, which would put 98.1 back.
        advanceTimeBy(100)

        fake.pressStockKey(9_850)
        fake.stereoPilot = false
        fake.local = true
        fake.radioText = "Tocando agora"
        advanceTimeBy(5_100)

        assertEquals(98_500, tuner.state.value.frequencyKhz)
        assertFalse(tuner.state.value.signal.stereo)
        assertEquals(Sensitivity.LOCAL, tuner.state.value.sensitivity)
        assertEquals("Tocando agora", tuner.state.value.rds.radioText)
        tuner.powerOff()
    }

    @Test
    fun `LOC is sent only when the service differs`() = nwdTest {
        val fake = FakeNwd(local = false)
        val tuner = tunerFor(fake)
        tuner.powerOn(Region.BRAZIL, 98_100)

        tuner.setSensitivity(Sensitivity.DX)
        assertTrue(fake.named("setNearOn").isEmpty())
        tuner.setSensitivity(Sensitivity.LOCAL)
        assertEquals(listOf(listOf<Any?>(true)), fake.argsOf("setNearOn"))
        assertEquals(Sensitivity.LOCAL, tuner.state.value.sensitivity)
        assertTrue(fake.local)
        tuner.powerOff()
    }

    @Test
    fun `shows the sensitivity the service reports`() = nwdTest {
        val fake = FakeNwd(local = true)
        val tuner = tunerFor(fake)

        tuner.powerOn(Region.BRAZIL, 98_100)

        assertEquals(Sensitivity.LOCAL, tuner.state.value.sensitivity)
        tuner.setSensitivity(Sensitivity.DX)
        assertFalse(fake.local)
        tuner.powerOff()
    }

    @Test
    fun `mono is sent as stereo off only after the user asked`() = nwdTest {
        val fake = FakeNwd(stereoSetting = true)
        val tuner = tunerFor(fake)
        tuner.powerOn(Region.BRAZIL, 98_100)

        tuner.setForceMono(false)
        assertTrue(fake.named("setStereoOn").isEmpty())
        tuner.setForceMono(true)
        tuner.setForceMono(false)
        assertEquals(listOf(listOf<Any?>(false), listOf<Any?>(true)), fake.argsOf("setStereoOn"))
        tuner.powerOff()
    }

    @Test
    fun `settings chosen before connecting are applied after`() = nwdTest {
        val fake = FakeNwd(local = false, stereoSetting = true)
        val tuner = tunerFor(fake)
        tuner.setSensitivity(Sensitivity.LOCAL)
        tuner.setForceMono(false)
        tuner.setRdsEnabled(false)

        tuner.powerOn(Region.BRAZIL, 98_100)

        assertEquals(listOf(listOf<Any?>(true)), fake.argsOf("setNearOn"))
        assertTrue(fake.named("setStereoOn").isEmpty())
        assertEquals(Sensitivity.LOCAL, tuner.state.value.sensitivity)
        assertFalse(tuner.state.value.rdsEnabled)
        tuner.powerOff()
    }

    // Errors and the contract.

    @Test
    fun `losing the service turns the tuner off safely and flags the lost connection`() = nwdTest {
        val fake = FakeNwd()
        val tuner = tunerFor(fake)
        tuner.powerOn(Region.BRAZIL, 98_100)

        fake.die()
        tuner.setSensitivity(Sensitivity.LOCAL)
        tuner.setForceMono(true)
        tuner.setMuted(true)
        tuner.reclaimSource()
        tuner.tune(96_100)
        assertNull(tuner.seek(SeekDirection.UP))
        advanceTimeBy(10_000)

        assertEquals(PowerState.ERROR, tuner.state.value.power)
        assertEquals(NwdTuner.CONNECTION_LOST, tuner.state.value.error)
        assertTrue(tuner.state.value.connectionLost)
        assertTrue(fake.closed)
        assertTrue(fake.callsAfterClose.isEmpty())
        tuner.powerOff()
    }

    @Test
    fun `losing the service in the middle of a tune ends it quietly`() = nwdTest {
        val fake = FakeNwd(ignoresTunes = 1)
        val tuner = tunerFor(fake)
        tuner.powerOn(Region.BRAZIL, 98_100)

        val tuning = launch { tuner.tune(96_100) }
        advanceTimeBy(500)
        fake.die()
        advanceTimeBy(5_000)

        assertTrue(tuning.isCompleted)
        assertEquals(PowerState.ERROR, tuner.state.value.power)
        assertTrue(fake.callsAfterClose.isEmpty())
    }

    @Test
    fun `power on clears a lost connection`() = nwdTest {
        var fake = FakeNwd()
        val tuner = tunerFor({ fake })
        tuner.powerOn(Region.BRAZIL, 98_100)
        fake.die()
        assertTrue(tuner.state.value.connectionLost)

        fake = FakeNwd()
        tuner.powerOn(Region.BRAZIL, 98_100)

        assertEquals(PowerState.ON, tuner.state.value.power)
        assertFalse(tuner.state.value.connectionLost)
        assertNull(tuner.state.value.error)
        tuner.powerOff()
    }

    @Test
    fun `reports an error when the service does not answer`() = nwdTest {
        val tuner = NwdTuner({ awaitCancellation() })

        tuner.powerOn(Region.BRAZIL, 98_100)

        assertEquals(PowerState.ERROR, tuner.state.value.power)
        assertTrue(tuner.state.value.error!!.contains("não respondeu"))
        assertFalse(tuner.state.value.connectionLost)
    }

    @Test
    fun `reports an error when the service cannot be bound`() = nwdTest {
        val tuner = NwdTuner({ throw NwdException("Não encontrei o serviço de rádio com.nwd.radio.service") })

        tuner.powerOn(Region.BRAZIL, 98_100)

        assertEquals(PowerState.ERROR, tuner.state.value.power)
        assertEquals("Não encontrei o serviço de rádio com.nwd.radio.service", tuner.state.value.error)
    }

    @Test
    fun `reports the service's message when it refuses the frequency`() = nwdTest {
        val fake = FakeNwd(refusedCalls = setOf("getCurrentFrequency"))
        val tuner = tunerFor(fake)

        tuner.powerOn(Region.BRAZIL, 98_100)

        assertEquals(PowerState.ERROR, tuner.state.value.power)
        assertEquals("O com.nwd.radio.service recusou o comando: getCurrentFrequency", tuner.state.value.error)
        assertTrue(fake.closed)
    }

    @Test
    fun `optional calls the service refuses do not stop the radio`() = nwdTest {
        val fake = FakeNwd(
            refusedCalls = setOf("registerCallback", "getRadioPoint", "hasStereoPilot", "isNearOn", "getRtMessage", "isStereoOn"),
        )
        val tuner = tunerFor(fake)
        tuner.powerOn(Region.BRAZIL, 98_100)
        advanceTimeBy(3_000)

        tuner.tune(96_100)
        tuner.setSensitivity(Sensitivity.LOCAL)
        tuner.setForceMono(true)

        assertEquals(PowerState.ON, tuner.state.value.power)
        assertEquals(96_100, tuner.state.value.frequencyKhz)
        assertEquals(listOf(listOf<Any?>(true)), fake.argsOf("setNearOn"))
        assertEquals(listOf(listOf<Any?>(false)), fake.argsOf("setStereoOn"))
        tuner.powerOff()
    }

    @Test
    fun `setters and power off never throw while disconnected`() = nwdTest {
        val tuner = NwdTuner({ FakeNwd() })

        tuner.setSensitivity(Sensitivity.LOCAL)
        tuner.setForceMono(true)
        tuner.setRdsEnabled(false)
        tuner.setMuted(true)
        tuner.reclaimSource()
        tuner.tune(96_100)
        assertNull(tuner.seek(SeekDirection.UP))
        tuner.powerOff()

        assertEquals(PowerState.OFF, tuner.state.value.power)
        assertEquals(Sensitivity.LOCAL, tuner.state.value.sensitivity)
        assertTrue(tuner.state.value.forceMono)
    }

    @Test
    fun `reports its capabilities`() {
        val tuner = NwdTuner({ FakeNwd() })

        assertTrue(tuner.managesAudioSource)
        assertFalse(tuner.reportsSignalStrength)
        assertTrue(tuner.usesHeadUnitBand)
        assertNotNull(tuner.name)
    }

    @Test
    fun `traces its decisions`() = nwdTest {
        val lines = mutableListOf<String>()
        val fake = FakeNwd(requestSwitches = false, ignoresTunes = 1)
        val tuner = tunerFor(fake, trace = { lines += it })

        tuner.powerOn(Region.BRAZIL, 96_100)
        advanceTimeBy(10_000)

        assertTrue(lines.any { it.startsWith("Tipo do rádio: 0") })
        assertTrue(lines.any { it.startsWith("Faixa FM do serviço: 8750 a 10800") })
        assertTrue(lines.any { it.contains("REQUEST_CHANGE_SOURCE(4): a fonte continua 0") })
        assertTrue(lines.any { it.contains("APP_IN_OUT(8, 1, 0): fonte 0 → 4") })
        assertTrue(lines.any { it.contains("ignorou a sintonia em 9610") })
        assertTrue(lines.any { it == "Sintonia confirmada: 9610" })
        tuner.powerOff()
    }
}

/** An MCU source id that is neither Android nor the radio. */
private const val BT_MUSIC = 7
