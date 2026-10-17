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
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

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
    private var toolkit: SyuToolkit? = null
    private var main: SyuModule? = null
    private var radio: SyuModule? = null
    private var sound: SyuModule? = null

    // Written from Binder threads.
    private val appId = MutableStateFlow<Int?>(null)
    @Volatile private var soundMuted = false
    @Volatile private var fmInKhz: Boolean? = null
    @Volatile private var onAmBand = false
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
            toolkit.onDisconnected = {
                _state.update { it.copy(power = PowerState.ERROR, error = "A conexão com o serviço com.syu.ms caiu") }
            }
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
            if (onAmBand) radio?.cmd(Radio.C_BAND, Radio.BAND_SWITCH_FM)
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
            if (frequencyKnown.value && target == _state.value.frequencyKhz) return
            ensureSource()
            if (directTuneWorks != false) {
                radio.cmd(Radio.C_FREQ, Radio.FREQ_DIRECT, toMcu(target))
                val reached = withTimeoutOrNull(timing.directTuneMs) { state.first { it.frequencyKhz == target } } != null
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
            ensureSource()
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
        if (muted) {
            // Only mute while the radio is what is playing; otherwise this would mute another source.
            if (radioIsSource()) sound?.cmd(Sound.C_VOL, Sound.VOL_MUTE)
            soundMuted = true
            refreshMuted()
        } else {
            claimSource()
        }
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

    /** Makes the radio the MCU's active source and unmutes it. */
    private suspend fun claimSource() {
        val main = main
        main?.cmd(Main.C_APP_ID, Main.APP_ID_RADIO)
        sound?.cmd(Sound.C_VOL, Sound.VOL_UNMUTE)
        soundMuted = false
        if (main != null && !awaitRadioSource()) {
            // Some FYT builds call the radio source "car radio".
            main.cmd(Main.C_APP_ID, Main.APP_ID_CAR_RADIO)
            main.cmd(Main.C_APP_ID, Main.APP_ID_RADIO)
            awaitRadioSource()
        }
        refreshMuted()
    }

    private suspend fun ensureSource() {
        if (!radioIsSource()) claimSource()
    }

    private suspend fun awaitRadioSource(): Boolean =
        withTimeoutOrNull(timing.settleMs) { appId.first(::isRadio) } != null

    /** True also when the service never reported the source, since then there is nothing to check. */
    private fun radioIsSource(): Boolean = isRadio(appId.value)

    private fun isRadio(id: Int?) = id == null || id == Main.APP_ID_RADIO || id == Main.APP_ID_CAR_RADIO

    /** Walks one channel at a time, waiting for the MCU to echo each step, until [target]. */
    private suspend fun stepTo(radio: SyuModule, target: Int) {
        repeat(MAX_STEPS) {
            val current = _state.value.frequencyKhz
            if (current == target) return
            val up = target > current
            radio.cmd(if (up) Radio.C_FREQ_UP else Radio.C_FREQ_DOWN)
            val next = withTimeoutOrNull(timing.echoMs) { state.first { it.frequencyKhz != current } }?.frequencyKhz
                ?: return
            val wrapped = if (up) next < current else next > current
            if (wrapped) {
                // Went past the band edge and came out at the other end: step back and stop.
                radio.cmd(if (up) Radio.C_FREQ_DOWN else Radio.C_FREQ_UP)
                return
            }
            if (if (up) next > target else next < target) return
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
            Syu.MODULE_MAIN -> if (update.code == Main.U_APP_ID) {
                appId.value = update.int()
                refreshMuted()
            }
            Syu.MODULE_SOUND -> if (update.code == Sound.U_MUTE) {
                soundMuted = (update.int() ?: 0) != 0
                refreshMuted()
            }
            Syu.MODULE_RADIO -> onRadioUpdate(update)
        }
    }

    private fun onRadioUpdate(update: SyuUpdate) {
        when (update.code) {
            Radio.U_FREQ -> {
                val khz = update.int()?.let(::fromMcu) ?: return
                _state.update { if (it.frequencyKhz == khz) it else it.copy(frequencyKhz = khz, rds = RdsInfo.EMPTY) }
                frequencyKnown.value = true
            }
            Radio.U_BAND -> onAmBand = (update.int() ?: Radio.BAND_FM_INDEX_BEGIN) < Radio.BAND_FM_INDEX_BEGIN
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
        else -> null // AM, in kHz
    }

    private fun toMcu(frequencyKhz: Int): Int = if (fmInKhz == true) frequencyKhz else frequencyKhz / 10

    private fun fail(message: String) {
        val tk = toolkit
        toolkit = null
        main = null
        radio = null
        sound = null
        runCatching { tk?.close() }
        _state.update { it.copy(power = PowerState.ERROR, error = message) }
    }

    companion object {
        private const val MAX_STEPS = 400

        private val RADIO_UPDATES = listOf(
            Radio.U_BAND,
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
