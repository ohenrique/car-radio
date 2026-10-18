package com.ohenrique.carradio.tuner.nwd

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * An NWD head unit as the tuner sees it: com.nwd.radio.service's RadioFeature, the MCU's source,
 * mute and sleep state as Settings.System and the vendor's broadcasts show them, and com.nwd.kernel
 * switching the source on request. Frequencies are in the service's own unit, set by [fmMin],
 * [fmMax] and [fmStep] (10 kHz by default, like the K4811); AM is in kHz.
 *
 * Everything happens on the caller's thread, right away, unless [scope] is given: then the unit
 * answers in the background like the real one, a station search moving one channel per
 * [seekStepMs], a tune landing after [tuneDelayMs] and a claimed source switching after
 * [claimDelayMs]. With [inOrder] too, tunes and seeks run one after another, as on an MCU behind a
 * serial line. Set [clock] to the test's virtual time for anything time based.
 */
internal class FakeNwd(
    var type: Int = Nwd.RadioType.MCU,
    val fmMin: Int = 8_750,
    val fmMax: Int = 10_800,
    val fmStep: Int = 10,
    var points: List<NwdRadioPoint?>? = listOf(NwdRadioPoint(fmMin, fmMax, fmStep), NwdRadioPoint(530, 1_710, 10)),
    var band: Int = Nwd.Band.FM1,
    /** The FM station, also kept while the band is AM. */
    var frequency: Int = 9_810,
    var amFrequency: Int = 1_010,
    val stations: Set<Int> = setOf(8_910, 9_610, 9_850, 10_630),
    /** RDS names the service reports with each station; others come with a blank name. */
    var names: Map<Int, String> = emptyMap(),
    /** The call that sweeps to the next station; the other one moves a single channel. */
    val stationSeekCall: SeekCall? = SeekCall.SEARCH,
    /** A call that does nothing at all on this unit. */
    val inertCall: SeekCall? = null,
    val trueMeansUp: Boolean = true,
    /** Reports state 2 while sweeping, through getRadioState and the callback. */
    val reportsBusy: Boolean = true,
    /** Whether RadioCallback notices reach this client. */
    val deliversCallbacks: Boolean = true,
    val sourceReadable: Boolean = true,
    var source: Int = Nwd.Source.ANDROID,
    val requestSwitches: Boolean = true,
    val appInSwitches: Boolean = true,
    val claimDelayMs: Long = 0,
    /** After the source switches to the radio, getRadioState stays 0 this long, and tunes are dropped. */
    val readyAfterClaimMs: Long = 0,
    /** What getRadioState says while nothing is going on. */
    val restState: Int = Nwd.State.NORMAL,
    val muteReadable: Boolean = true,
    /** The whole unit's mute. */
    var muted: Boolean = false,
    val setMuteWorks: Boolean = true,
    /** Drops this many tunes. */
    var ignoresTunes: Int = 0,
    val tuneDelayMs: Long = 0,
    /** False for services that keep their band whatever band a tune with no preset asks for. */
    val honoursTuneBand: Boolean = true,
    /** Drops tunes while another source has the speakers, as an MCU may. */
    val tunesNeedSource: Boolean = false,
    val bandCommandWorks: Boolean = true,
    val changeBandWorks: Boolean = true,
    val bandCycle: List<Int> = listOf(Nwd.Band.FM1, Nwd.Band.FM2, Nwd.Band.FM3, Nwd.Band.AM1, Nwd.Band.AM2),
    /** Drops band changes that come faster than this after the last one taken. */
    val changeBandMinGapMs: Long = 500,
    var mcuState: Int = Nwd.McuState.AWAKE,
    var inCall: Boolean = false,
    /** LOC. */
    var local: Boolean = false,
    /** Stereo decoding on, as opposed to mono; not the pilot. */
    var stereoSetting: Boolean = true,
    var stereoPilot: Boolean = true,
    var radioText: String? = null,
    /** Calls that throw, by name, as a service that refuses them. */
    val refusedCalls: Set<String> = emptySet(),
    val scope: CoroutineScope? = null,
    val seekStepMs: Long = 20,
    /** Tunes and seeks wait for the one before to finish (a seek sent while a tune lands starts after it). */
    val inOrder: Boolean = false,
) : NwdService {

    data class Command(val name: String, val args: List<Any?>, val atMs: Long)

    var clock: () -> Long = { 0L }

    val commands = mutableListOf<Command>()
    val reads = mutableMapOf<String, Int>()
    val callsAfterClose = mutableListOf<String>()
    var registered = false
    var closed = false
    var seeking = false
        private set

    /** Runs inside [getRtMessage], to change things while the tuner waits for the answer. */
    var duringRtRead: (() -> Unit)? = null

    /** When set, [getCurrentFrequency] answers this instead of the real station. */
    var frozenReading: NwdFrequency? = null

    private var readyAt = 0L
    private var lastBandChangeAt: Long? = null

    private val queue = if (inOrder && scope != null) {
        Channel<suspend () -> Unit>(Channel.UNLIMITED).also { queue -> scope.launch { for (job in queue) job() } }
    } else {
        null
    }

    override var listener: ((NwdEvent) -> Unit)? = null
    override var onDisconnected: (() -> Unit)? = null

    fun named(name: String) = commands.filter { it.name == name }

    fun argsOf(name: String) = named(name).map { it.args }

    fun readsOf(name: String) = reads[name] ?: 0

    /** What getCurrentFrequency reports. */
    fun current() = if (Nwd.Band.isFm(band)) {
        NwdFrequency(band, names[frequency] ?: "", frequency)
    } else {
        NwdFrequency(band, "", amFrequency)
    }

    // RadioFeature.

    override fun getRadioType(): Int = query("getRadioType") { type }

    override fun getRadioPoint(): List<NwdRadioPoint?>? = query("getRadioPoint") { points }

    override fun getCurrentFrequency(): NwdFrequency? = query("getCurrentFrequency") { frozenReading ?: current() }

    override fun getRadioState(): Int = query("getRadioState") {
        when {
            seeking && reportsBusy -> 2
            clock() < readyAt -> Nwd.State.CLOSED
            else -> restState
        }
    }

    override fun setCurrentFrequency(frequency: Int, band: Int, presetIndex: Int) =
        command("setCurrentFrequency", frequency, band, presetIndex) {
            when {
                ignoresTunes > 0 -> ignoresTunes--
                clock() < readyAt -> Unit
                tunesNeedSource && source != Nwd.Source.RADIO -> Unit
                else -> onRadio(tuneDelayMs) { applyTune(frequency, band, presetIndex) }
            }
        }

    override fun seek(up: Boolean) = command("seek", up) { move(SeekCall.SEEK, up) }

    override fun search(up: Boolean) = command("search", up) { move(SeekCall.SEARCH, up) }

    override fun changeBand() = command("changeBand") {
        val now = clock()
        val last = lastBandChangeAt
        if (last != null && now - last < changeBandMinGapMs) return@command
        lastBandChangeAt = now
        if (!changeBandWorks) return@command
        switchBand(bandCycle[(bandCycle.indexOf(band) + 1) % bandCycle.size])
    }

    override fun sendRadioCommand(action: Int, value: Int) = command("sendRadioCommand", action, value) {
        val band = action - Nwd.Command.SELECT_BAND
        if (bandCommandWorks && value == Nwd.Command.ON && band in Nwd.Band.FM1..Nwd.Band.AM2) switchBand(band)
    }

    override fun setNearOn(on: Boolean) = command("setNearOn", on) {
        local = on
        notice(NwdEvent.NearOnChanged(on))
    }

    override fun isNearOn(): Boolean = query("isNearOn") { local }

    override fun hasStereoPilot(): Boolean = query("hasStereoPilot") { stereoPilot }

    override fun setStereoOn(on: Boolean) = command("setStereoOn", on) { stereoSetting = on }

    override fun isStereoOn(): Boolean = query("isStereoOn") { stereoSetting }

    override fun getRtMessage(): String? = query("getRtMessage") {
        duringRtRead?.invoke()
        radioText
    }

    override fun registerCallback() = command("registerCallback") { registered = true }

    override fun unregisterCallback() = command("unregisterCallback") { registered = false }

    // Settings.System and the system around the service.

    override fun currentSource(): Int? = query("currentSource") { if (sourceReadable) source else null }

    override fun isUnitMuted(): Boolean? = query("isUnitMuted") { if (muteReadable) muted else null }

    override fun mcuState(): Int? = query("mcuState") { mcuState }

    override fun isPhoneCallActive(): Boolean = query("isPhoneCallActive") { inCall }

    override fun requestSource(sourceId: Int) = command("requestSource", sourceId) {
        if (requestSwitches && sourceId == Nwd.Source.RADIO) claimed()
    }

    override fun sendAppInOut(appId: Int, operation: Int, event: Int) = command("sendAppInOut", appId, operation, event) {
        if (appInSwitches && appId == Nwd.App.RADIO && operation == Nwd.App.OPERATION_IN) claimed()
    }

    override fun sendMediaPlay(appId: Int) = command("sendMediaPlay", appId) {}

    override fun setUnitMuted(muted: Boolean) = command("setUnitMuted", muted) {
        if (setMuteWorks) setMute(muted)
    }

    override fun close() {
        closed = true
    }

    // What happens on the unit by itself.

    /** Another source takes the speakers (or the radio gets them back), as the MCU reports it. */
    fun switchSource(id: Int) {
        source = id
        if (sourceReadable) broadcast(NwdEvent.SourceChanged(id))
    }

    /** The mute key on the unit. */
    fun setMute(muted: Boolean) {
        this.muted = muted
        if (muteReadable) broadcast(NwdEvent.UnitMuteChanged(muted))
    }

    /** The stock radio or a key moves the dial. */
    fun pressStockKey(raw: Int) {
        if (!Nwd.Band.isFm(band)) band = Nwd.Band.FM1
        moveTo(raw)
    }

    /** The stock radio leaves the service on AM. */
    fun goToAm(khz: Int) {
        amFrequency = khz
        switchBand(Nwd.Band.AM1)
    }

    /** A frequency notice about a station the service is not on (an old one, still in flight). */
    fun notify(raw: Int, programService: String?, band: Int = this.band) {
        notice(NwdEvent.FrequencyChanged(NwdFrequency(band, programService, raw)))
    }

    fun pushRadioText(text: String?) {
        radioText = text
        notice(NwdEvent.RadioTextChanged(text))
    }

    fun pushPilot(stereo: Boolean) {
        stereoPilot = stereo
        notice(NwdEvent.StereoPilotChanged(stereo))
    }

    /** notifyRadioScanState: non-zero while the service scans. */
    fun pushScanState(state: Int) {
        notice(NwdEvent.ScanStateChanged(state))
    }

    fun pushRadioPoint(points: List<NwdRadioPoint?>) {
        this.points = points
        notice(NwdEvent.RadioPointChanged(points))
    }

    /** ACC off: the vendor tears FM down and may forget the station ([forgetTo]). */
    fun sleep(forgetTo: Int? = null) {
        mcuState = 2
        broadcast(NwdEvent.McuStateChanged(2))
        if (forgetTo != null) moveTo(forgetTo)
    }

    fun wake() {
        mcuState = Nwd.McuState.AWAKE
        broadcast(NwdEvent.McuStateChanged(Nwd.McuState.AWAKE))
    }

    /** com.nwd.radio.service dies. */
    fun die() {
        onDisconnected?.invoke()
    }

    private fun claimed() = later(claimDelayMs) {
        readyAt = clock() + readyAfterClaimMs
        switchSource(Nwd.Source.RADIO)
        if (readyAfterClaimMs > 0 && scope != null) {
            scope.launch {
                delay(readyAfterClaimMs)
                notice(NwdEvent.StateChanged(Nwd.State.NORMAL))
            }
        }
    }

    private fun applyTune(raw: Int, band: Int, presetIndex: Int) {
        if (presetIndex != Nwd.NO_PRESET) {
            // The service tunes what it keeps in that slot instead.
            moveTo(stations.first())
            return
        }
        val target = if (honoursTuneBand) band else this.band
        if (!Nwd.Band.isFm(target)) return
        this.band = target
        moveTo(raw.coerceIn(fmMin, fmMax))
    }

    private fun move(call: SeekCall, argument: Boolean) {
        if (call == inertCall || !Nwd.Band.isFm(band)) return
        val delta = if (argument == trueMeansUp) 1 else -1
        if (call == stationSeekCall) sweep(delta) else onRadio(seekStepMs) { moveTo(next(frequency, delta)) }
    }

    private fun sweep(delta: Int) {
        val run: suspend () -> Unit = {
            val origin = frequency
            seeking = true
            if (reportsBusy) notice(NwdEvent.StateChanged(2))
            var f = origin
            var found = false
            for (i in 0 until (fmMax - fmMin) / fmStep) {
                if (scope != null) delay(seekStepMs)
                f = next(f, delta)
                moveTo(f)
                if (f in stations) {
                    found = true
                    break
                }
            }
            if (!found) moveTo(origin)
            seeking = false
            if (reportsBusy) notice(NwdEvent.StateChanged(Nwd.State.NORMAL))
        }
        when {
            queue != null -> queue.trySend(run)
            scope != null -> scope.launch { run() }
            else -> runBlocking { run() }
        }
    }

    private fun switchBand(band: Int) {
        this.band = band
        notice(NwdEvent.FrequencyChanged(current()))
    }

    private fun next(f: Int, delta: Int): Int {
        val n = f + delta * fmStep
        return when {
            n > fmMax -> fmMin
            n < fmMin -> fmMax
            else -> n
        }
    }

    private fun moveTo(f: Int) {
        frequency = f
        notice(NwdEvent.FrequencyChanged(current()))
    }

    /** A tune or a one channel step: behind the ones before it when [inOrder]. */
    private fun onRadio(delayMs: Long, action: () -> Unit) {
        if (queue == null) {
            later(delayMs, action)
        } else {
            queue.trySend {
                delay(delayMs)
                action()
            }
        }
    }

    private fun later(delayMs: Long, action: () -> Unit) {
        if (scope == null || delayMs <= 0) action() else scope.launch {
            delay(delayMs)
            action()
        }
    }

    /** A RadioCallback notice: only while registered, and only if this unit delivers them. */
    private fun notice(event: NwdEvent) {
        if (deliversCallbacks && registered && !closed) listener?.invoke(event)
    }

    /** A Settings.System change or a vendor broadcast, which reach every app. */
    private fun broadcast(event: NwdEvent) {
        if (!closed) listener?.invoke(event)
    }

    private fun <T> query(name: String, answer: () -> T): T {
        guard(name)
        reads[name] = readsOf(name) + 1
        return answer()
    }

    private fun command(name: String, vararg args: Any?, action: () -> Unit) {
        guard(name)
        commands += Command(name, args.toList(), clock())
        action()
    }

    private fun guard(name: String) {
        if (closed) {
            callsAfterClose += name
            throw NwdException("A conexão com o com.nwd.radio.service foi fechada")
        }
        if (name in refusedCalls) throw NwdException("O com.nwd.radio.service recusou o comando: $name")
    }
}
