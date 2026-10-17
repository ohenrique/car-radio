package com.ohenrique.carradio.tuner.fyt

import com.ohenrique.carradio.tuner.FoundStation
import com.ohenrique.carradio.tuner.PowerState
import com.ohenrique.carradio.tuner.RdsInfo
import com.ohenrique.carradio.tuner.Region
import com.ohenrique.carradio.tuner.SeekDirection
import com.ohenrique.carradio.tuner.Sensitivity
import com.ohenrique.carradio.tuner.SignalInfo
import com.ohenrique.carradio.tuner.Tuner
import com.ohenrique.carradio.tuner.TunerActivity
import com.ohenrique.carradio.tuner.TunerState
import com.ohenrique.carradio.tuner.fyt.Syu.Main
import com.ohenrique.carradio.tuner.fyt.Syu.Radio
import com.ohenrique.carradio.tuner.fyt.Syu.Sound
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs

/**
 * Radio on FYT head units (UIS7862, UIS8581, UIS7870...), driven through the `com.syu.ms` service
 * the same way the stock radio, the stock launcher widget and NavRadio+ do. No root needed.
 *
 * The tuner chip sits behind the MCU, and the MCU also decides which source reaches the speakers,
 * so besides tuning this claims the radio as the active source ([Main.C_APP_ID]); without that
 * the radio tunes silently.
 */
class FytTuner(
    private val connect: suspend () -> SyuToolkit,
    private val timing: Timing = Timing(),
) : Tuner {

    data class Timing(
        val connectMs: Long = 8_000,
        val echoMs: Long = 400,
        val directTuneMs: Long = 600,
        val seekStartMs: Long = 1_500,
        val seekMaxMs: Long = 30_000,
        val settleMs: Long = 1_200,
        /** How long to wait for the last frequency report after the MCU says a seek ended. */
        val seekTailMs: Long = 150,
    )

    override val name = "FYT (com.syu.ms)"

    override val managesAudioSource = true

    private val _state = MutableStateFlow(TunerState())
    override val state: StateFlow<TunerState> = _state.asStateFlow()

    private val operation = Mutex()

    // Cleared from a Binder thread when the service dies.
    @Volatile private var toolkit: SyuToolkit? = null
    @Volatile private var main: SyuModule? = null
    @Volatile private var radio: SyuModule? = null
    @Volatile private var sound: SyuModule? = null

    // Written from Binder threads.
    private val appId = MutableStateFlow<Int?>(null)
    @Volatile private var soundMuted = false
    /** A [Sound.VOL_MUTE] sent by this app is in force. It silences the whole head unit. */
    @Volatile private var mutedByApp = false
    @Volatile private var fmInKhz: Boolean? = null
    /** False while the MCU is on the AM band. */
    private val onFm = MutableStateFlow(true)
    private val frequencyKnown = MutableStateFlow(false)
    private val searchState = MutableStateFlow(Radio.SEARCH_STATE_NONE)

    /** Whether [Radio.C_FREQ] tunes on this firmware; null until it worked or failed twice. */
    private var directTuneWorks: Boolean? = null
    private var directTuneFailures = 0

    // What the app asked for. Settings can arrive before the service is connected, so they are
    // kept here and sent once it is, and only when the MCU reports something different.
    private var wantedSensitivity: Sensitivity? = null
    private var wantedRds: Boolean? = null
    private var wantedMono = false
    @Volatile private var mcuLocal: Boolean? = null
    @Volatile private var mcuRds: Boolean? = null

    /** Stereo is left to the MCU until the user asks for mono once. */
    private var stereoTouched = false

    override suspend fun powerOn(region: Region, frequencyKhz: Int) {
        if (_state.value.power == PowerState.ON) return
        _state.update { it.copy(power = PowerState.STARTING, region = region, error = null) }
        try {
            val toolkit = withTimeout(timing.connectMs) { connect() }
            this.toolkit = toolkit
            toolkit.listener = ::onUpdate
            toolkit.onDisconnected = { fail(CONNECTION_LOST) }
            main = toolkit.module(Syu.MODULE_MAIN)
            sound = toolkit.module(Syu.MODULE_SOUND)
            radio = toolkit.module(Syu.MODULE_RADIO)
                ?: throw SyuException("O serviço com.syu.ms não ofereceu o módulo de rádio")

            main?.register(Main.U_APP_ID)
            sound?.register(Sound.U_MUTE)
            RADIO_UPDATES.forEach { radio?.register(it) }

            // Knowing the current source lets claimSource() check that the switch took.
            if (main != null) withTimeoutOrNull(timing.echoMs) { appId.first { it != null } }
            claimSource()
            withTimeoutOrNull(timing.settleMs) { frequencyKnown.first { it } }
            ensureFm()
            syncSensitivity()
            syncRds()
            syncStereo()
            _state.update { it.copy(power = PowerState.ON) }
            // The MCU remembers its own station; only tune when it did not tell us where it is.
            if (!frequencyKnown.value) tune(frequencyKhz)
        } catch (e: TimeoutCancellationException) {
            fail("O serviço com.syu.ms não respondeu. Essa central é FYT?")
        } catch (e: CancellationException) {
            fail("Cancelado")
            throw e
        } catch (e: Exception) {
            fail(e.message ?: e.toString())
        }
    }

    override suspend fun powerOff() {
        // The radio stays the MCU's source: releasing it makes the MCU jump to another one.
        toolkit?.let { tk ->
            // But do not leave the whole unit muted by the radio's mute.
            if (mutedByApp) runCatching { sound?.cmd(Sound.C_VOL, Sound.VOL_UNMUTE) }
            mutedByApp = false
            RADIO_UPDATES.forEach { runCatching { radio?.unregister(it) } }
            runCatching { main?.unregister(Main.U_APP_ID) }
            runCatching { sound?.unregister(Sound.U_MUTE) }
            tk.listener = null
            tk.onDisconnected = null
            runCatching { tk.close() }
        }
        toolkit = null
        main = null
        radio = null
        sound = null
        _state.update { it.copy(power = PowerState.OFF, activity = TunerActivity.IDLE, rds = RdsInfo.EMPTY) }
    }

    override suspend fun tune(frequencyKhz: Int) {
        val radio = radio ?: return
        operation.withLock {
            val target = clampToBand(frequencyKhz)
            awaitSearchEnd()
            if (isAt(target)) return
            ensureSource()
            ensureFm()
            if (directTuneWorks != false) {
                radio.cmd(Radio.C_FREQ, Radio.FREQ_DIRECT, toMcu(target))
                var reached = withTimeoutOrNull(timing.directTuneMs) { state.first { isAt(target) } } != null
                if (!reached) {
                    // A slow MCU may still be on its way there.
                    awaitQuiet()
                    reached = isAt(target)
                }
                if (reached) {
                    directTuneWorks = true
                    return
                }
                if (directTuneWorks == null && ++directTuneFailures >= 2) directTuneWorks = false
            }
            stepTo(radio, target)
        }
    }

    override suspend fun seek(direction: SeekDirection, wrap: Boolean): FoundStation? {
        val radio = radio ?: return null
        return operation.withLock {
            awaitSearchEnd()
            ensureSource()
            ensureFm()
            val origin = _state.value.frequencyKhz
            _state.update { it.copy(activity = TunerActivity.SEEKING) }
            try {
                searchState.value = Radio.SEARCH_STATE_NONE
                radio.cmd(if (direction == SeekDirection.UP) Radio.C_SEEK_UP else Radio.C_SEEK_DOWN)
                val started = withTimeoutOrNull(timing.seekStartMs) {
                    searchState.first { it != Radio.SEARCH_STATE_NONE }
                } != null
                if (started) {
                    withTimeoutOrNull(timing.seekMaxMs) { searchState.first { it == Radio.SEARCH_STATE_NONE } }
                    delay(timing.seekTailMs)
                } else {
                    // Firmware that does not report the search state: wait for the dial to settle.
                    awaitSettled(origin)
                }
            } finally {
                _state.update { it.copy(activity = TunerActivity.IDLE) }
            }
            // The MCU wraps around the band by itself, so [wrap] cannot be honoured here.
            _state.value.frequencyKhz.takeIf { it != origin }?.let { FoundStation(it, null) }
        }
    }

    override suspend fun setMuted(muted: Boolean) {
        if (!muted) return claimSource()
        // VOL_MUTE silences the whole head unit, so it is only sent while the radio is what plays,
        // and taken back as soon as another source gets the speakers (see onSourceChanged).
        val sound = sound
        if (sound != null && radioIsSource() && !inCall()) {
            sound.cmd(Sound.C_VOL, Sound.VOL_MUTE)
            soundMuted = true
            mutedByApp = true
        }
        refreshMuted()
    }

    override suspend fun reclaimSource() {
        if (radio == null || radioIsSource() || inCall()) return
        switchSource()
        refreshMuted()
    }

    override suspend fun setForceMono(forceMono: Boolean) {
        wantedMono = forceMono
        _state.update { it.copy(forceMono = forceMono) }
        syncStereo()
    }

    override suspend fun setSensitivity(sensitivity: Sensitivity) {
        wantedSensitivity = sensitivity
        _state.update { it.copy(sensitivity = sensitivity) }
        syncSensitivity()
    }

    override suspend fun setRdsEnabled(enabled: Boolean) {
        wantedRds = enabled
        _state.update { it.copy(rdsEnabled = enabled, rds = if (enabled) it.rds else RdsInfo.EMPTY) }
        syncRds()
    }

    private fun syncSensitivity() {
        val local = wantedSensitivity?.let { it == Sensitivity.LOCAL } ?: return
        if (mcuLocal != local) radio?.cmd(Radio.C_LOC, if (local) 1 else 0)
    }

    private fun syncRds() {
        val enabled = wantedRds ?: return
        if (mcuRds != enabled) radio?.cmd(Radio.C_RDS_ENABLE, if (enabled) 1 else 0)
    }

    /** The meaning of the stereo command is not confirmed, so it is only sent once mono was asked for. */
    private fun syncStereo() {
        val radio = radio ?: return
        if (!wantedMono && !stereoTouched) return
        radio.cmd(Radio.C_STEREO, if (wantedMono) 0 else 1)
        stereoTouched = true
    }

    /** Makes the radio the MCU's active source and unmutes it, unless a phone call has the speakers. */
    private suspend fun claimSource() {
        if (inCall()) return
        switchSource()
        sound?.cmd(Sound.C_VOL, Sound.VOL_UNMUTE)
        soundMuted = false
        mutedByApp = false
        refreshMuted()
    }

    private suspend fun switchSource() {
        val main = main ?: return
        if (appId.value.let { it == Main.APP_ID_RADIO || it == Main.APP_ID_CAR_RADIO }) return
        main.cmd(Main.C_APP_ID, Main.APP_ID_RADIO)
        if (!awaitRadioSource()) {
            // Some FYT builds call the radio source "car radio".
            main.cmd(Main.C_APP_ID, Main.APP_ID_CAR_RADIO)
            main.cmd(Main.C_APP_ID, Main.APP_ID_RADIO)
            awaitRadioSource()
        }
    }

    private suspend fun ensureSource() {
        if (!radioIsSource()) claimSource()
    }

    /** Switches back to FM when the MCU was left on AM, by the stock radio for instance. */
    private suspend fun ensureFm() {
        if (onFm.value) return
        radio?.cmd(Radio.C_BAND, Radio.BAND_SWITCH_FM)
        withTimeoutOrNull(timing.settleMs) { onFm.first { it } }
    }

    private suspend fun awaitRadioSource(): Boolean =
        withTimeoutOrNull(timing.settleMs) { appId.first(::isRadio) } != null

    /** True also when the service never reported the source, since then there is nothing to check. */
    private fun radioIsSource(): Boolean = isRadio(appId.value)

    private fun isRadio(id: Int?) = id == null || id == Main.APP_ID_RADIO || id == Main.APP_ID_CAR_RADIO

    private fun inCall(): Boolean = appId.value in PHONE_APP_IDS

    private fun isAt(frequencyKhz: Int) = frequencyKnown.value && _state.value.frequencyKhz == frequencyKhz

    /** Walks one channel at a time, waiting for the MCU to echo each step, until [target]. */
    private suspend fun stepTo(radio: SyuModule, target: Int) {
        // Never step from a frequency the MCU did not report.
        if (withTimeoutOrNull(timing.echoMs) { frequencyKnown.first { it } } == null) return
        // Let the echoes of a cancelled tune, or a seek still running, arrive before reading the dial.
        awaitQuiet()
        val span = _state.value.region.let { it.maxKhz - it.minKhz }
        repeat(MAX_STEPS) {
            val current = _state.value.frequencyKhz
            if (current == target) return
            val up = target > current
            val next = step(radio, if (up) Radio.C_FREQ_UP else Radio.C_FREQ_DOWN, current) ?: return
            if (abs(next - current) > span / 2) {
                // Went past the band edge and came out at the other end: step back and stop.
                step(radio, if (up) Radio.C_FREQ_DOWN else Radio.C_FREQ_UP, next)
                return
            }
            if (if (up) next > target else next < target) return
        }
    }

    /** Sends one step and waits for its echo, which a cancellation must not separate from it. */
    private suspend fun step(radio: SyuModule, command: Int, from: Int): Int? {
        radio.cmd(command)
        val next = withContext(NonCancellable) {
            withTimeoutOrNull(timing.echoMs) { state.first { it.frequencyKhz != from } }
        }?.frequencyKhz
        currentCoroutineContext().ensureActive()
        return next
    }

    /**
     * Lets a seek the MCU is still running (one whose request was cancelled here) finish, since
     * there is no command to stop it and the dial would keep moving under a new tune.
     */
    private suspend fun awaitSearchEnd() {
        if (searchState.value == Radio.SEARCH_STATE_NONE) return
        withTimeoutOrNull(timing.seekMaxMs) { searchState.first { it == Radio.SEARCH_STATE_NONE } }
        delay(timing.seekTailMs)
    }

    /** Waits until the MCU has stopped reporting frequency changes for a moment. */
    private suspend fun awaitQuiet() {
        withTimeoutOrNull(timing.seekMaxMs) {
            while (true) {
                val last = _state.value.frequencyKhz
                withTimeoutOrNull(timing.echoMs) { state.first { it.frequencyKhz != last } } ?: break
            }
        }
    }

    /** Waits for the dial to start moving away from [origin] and then to stay still for a while. */
    private suspend fun awaitSettled(origin: Int) {
        withTimeoutOrNull(timing.seekStartMs) { state.first { it.frequencyKhz != origin } } ?: return
        withTimeoutOrNull(timing.seekMaxMs) {
            var last = _state.value.frequencyKhz
            while (true) {
                last = withTimeoutOrNull(timing.settleMs) { state.first { it.frequencyKhz != last } }?.frequencyKhz ?: break
            }
        }
    }

    private fun onUpdate(update: SyuUpdate) {
        when (update.module) {
            Syu.MODULE_MAIN -> if (update.code == Main.U_APP_ID) onSourceChanged(update.int())
            Syu.MODULE_SOUND -> if (update.code == Sound.U_MUTE) {
                soundMuted = (update.int() ?: 0) != 0
                refreshMuted()
            }
            Syu.MODULE_RADIO -> onRadioUpdate(update)
        }
    }

    private fun onSourceChanged(id: Int?) {
        appId.value = id
        if (mutedByApp && !isRadio(id)) {
            // Another source took the speakers: it must not play into the radio's mute.
            mutedByApp = false
            soundMuted = false
            runCatching { sound?.cmd(Sound.C_VOL, Sound.VOL_UNMUTE) }
        }
        refreshMuted()
    }

    private fun onRadioUpdate(update: SyuUpdate) {
        when (update.code) {
            Radio.U_FREQ -> {
                val value = update.int() ?: return
                // FM and AM share this update; AM is in kHz and always below 5000.
                if (value < AM_MAX) {
                    onFm.value = false
                    return
                }
                val khz = fromMcu(value) ?: return
                onFm.value = true
                frequencyKnown.value = true
                _state.update { if (it.frequencyKhz == khz) it else it.copy(frequencyKhz = khz, rds = RdsInfo.EMPTY) }
            }
            Radio.U_EXTRA_FREQ_INFO -> onBandInfo(update.ints ?: return)
            Radio.U_STEREO -> _state.update { it.copy(signal = SignalInfo(stereo = (update.int() ?: 0) != 0)) }
            Radio.U_SEARCH_STATE -> searchState.value = update.int() ?: Radio.SEARCH_STATE_NONE
            Radio.U_LOC -> {
                val local = (update.int() ?: return) != 0
                mcuLocal = local
                _state.update { it.copy(sensitivity = if (local) Sensitivity.LOCAL else Sensitivity.DX) }
            }
            Radio.U_RDS_ENABLE -> {
                val enabled = (update.int() ?: return) != 0
                mcuRds = enabled
                _state.update { it.copy(rdsEnabled = enabled) }
            }
            Radio.U_RDS_CHANNEL_TEXT, Radio.U_PS_TEXT -> _state.update {
                it.copy(rds = it.rds.copy(programService = update.string()?.trim()?.ifEmpty { null }))
            }
            Radio.U_RDS_TEXT -> _state.update {
                it.copy(rds = it.rds.copy(radioText = update.string()?.trim()?.ifEmpty { null }))
            }
            Radio.U_PTY_ID -> _state.update { it.copy(rds = it.rds.copy(programType = update.int())) }
        }
    }

    /** ints = [min, max, step length, step count], in the MCU's frequency unit. */
    private fun onBandInfo(ints: IntArray) {
        val max = ints.getOrNull(1) ?: return
        fmInKhz = when {
            max >= 50_000 -> true
            max >= 2_000 -> false
            else -> return // AM band
        }
        val min = fromMcu(ints[0]) ?: return
        val maxKhz = fromMcu(max) ?: return
        val stepKhz = ints.getOrNull(2)?.let { if (fmInKhz == true) it else it * 10 }
        _state.update { it.copy(region = closestRegion(min, maxKhz, stepKhz)) }
    }

    private fun refreshMuted() {
        _state.update { it.copy(muted = soundMuted || !radioIsSource()) }
    }

    private fun clampToBand(frequencyKhz: Int) = _state.value.region.snap(frequencyKhz)

    /** MCU frequency to kHz. FM is in 10 kHz units on UIS7862/8581/7870 firmware and in kHz on older ones. */
    private fun fromMcu(value: Int): Int? = when {
        value >= 50_000 -> value
        value in 5_000..20_000 -> value * 10
        else -> null
    }

    private fun toMcu(frequencyKhz: Int): Int = if (fmInKhz == true) frequencyKhz else frequencyKhz / 10

    private fun fail(message: String) {
        val tk = toolkit
        toolkit = null
        main = null
        radio = null
        sound = null
        tk?.listener = null
        tk?.onDisconnected = null
        runCatching { tk?.close() }
        _state.update { it.copy(power = PowerState.ERROR, activity = TunerActivity.IDLE, error = message) }
    }

    companion object {
        /** The error shown when the service went away while the radio was on; the app reconnects on it. */
        const val CONNECTION_LOST = "A conexão com o serviço com.syu.ms caiu"

        private const val MAX_STEPS = 400
        private const val AM_MAX = 5_000

        private val PHONE_APP_IDS = setOf(Main.APP_ID_BTPHONE, Main.APP_ID_CAR_BTPHONE, Main.APP_ID_3GPHONE)

        private val RADIO_UPDATES = listOf(
            Radio.U_FREQ,
            Radio.U_EXTRA_FREQ_INFO,
            Radio.U_STEREO,
            Radio.U_SEARCH_STATE,
            Radio.U_LOC,
            Radio.U_RDS_ENABLE,
            Radio.U_RDS_CHANNEL_TEXT,
            Radio.U_PS_TEXT,
            Radio.U_RDS_TEXT,
            Radio.U_PTY_ID,
        )

        /** The [Region] whose grid matches the band the MCU reports. */
        fun closestRegion(minKhz: Int, maxKhz: Int, stepKhz: Int?): Region = when {
            minKhz < 80_000 && maxKhz <= 95_000 -> Region.JAPAN
            minKhz < 80_000 -> Region.BRAZIL
            stepKhz == 200 -> Region.USA
            stepKhz == 50 -> Region.EUROPE
            else -> Region.AMERICAS
        }
    }
}
