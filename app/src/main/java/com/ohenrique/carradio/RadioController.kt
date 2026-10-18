package com.ohenrique.carradio

import com.ohenrique.carradio.data.PRESET_COUNT
import com.ohenrique.carradio.data.Preset
import com.ohenrique.carradio.data.RadioSettings
import com.ohenrique.carradio.data.RadioStore
import com.ohenrique.carradio.data.TunerBackend
import com.ohenrique.carradio.tuner.PowerState
import com.ohenrique.carradio.tuner.SeekDirection
import com.ohenrique.carradio.tuner.Sensitivity
import com.ohenrique.carradio.tuner.Tuner
import com.ohenrique.carradio.tuner.TunerActivity
import com.ohenrique.carradio.tuner.TunerState
import com.ohenrique.carradio.tuner.UnavailableTuner
import com.ohenrique.carradio.tuner.fyt.FytTuner
import com.ohenrique.carradio.tuner.fyt.SyuToolkit
import com.ohenrique.carradio.tuner.nwd.NwdService
import com.ohenrique.carradio.tuner.nwd.NwdTuner
import com.ohenrique.carradio.tuner.qn8035.Qn8035Config
import com.ohenrique.carradio.tuner.qn8035.Qn8035Tuner
import com.ohenrique.carradio.tuner.scanBand
import com.ohenrique.carradio.tuner.sim.SimulatedTuner
import com.ohenrique.carradio.tuner.strongest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The one place that owns the tuner. The screen and the media session (steering wheel keys,
 * notification) both drive the radio through it, so they always agree on what is playing.
 *
 * @param detectBackend the head unit's own radio, for [TunerBackend.AUTO]: NWD, FYT, or null for none.
 * @param releaseAudioFocus gives up Android's audio focus at once, before a tuner that manages the
 *   head unit's audio source starts.
 * @param trace the tuners' decisions, for the diagnostics screen.
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class RadioController(
    private val store: RadioStore,
    private val scope: CoroutineScope,
    private val connectFyt: suspend () -> SyuToolkit,
    private val connectNwd: suspend () -> NwdService,
    private val detectBackend: () -> TunerBackend?,
    private val releaseAudioFocus: () -> Unit,
    private val trace: (String) -> Unit,
) {
    private val _tuner = MutableStateFlow<Tuner>(SimulatedTuner())
    val tuner: StateFlow<Tuner> = _tuner.asStateFlow()

    val state: StateFlow<TunerState> = _tuner
        .flatMapLatest { it.state }
        .stateIn(scope, SharingStarted.Eagerly, TunerState())

    val presets: StateFlow<List<Preset?>> = store.presets
        .stateIn(scope, SharingStarted.Eagerly, List(PRESET_COUNT) { null })

    val settings: StateFlow<RadioSettings?> = store.settings
        .stateIn(scope, SharingStarted.Eagerly, null)

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private val lifecycle = Mutex()
    private var operation: Job? = null
    private var activeSettings: RadioSettings? = null
    private var reconnecting: Job? = null
    /** The mute [reconnecting] carries over, for a restart that replaces it. */
    private var reconnectMuteHeld = false

    /** Frequency of a tune that was asked for but may not have reached the tuner yet. */
    private var pendingKhz: Int? = null

    init {
        // Remember the station, once the dial has settled.
        state.filter { it.power == PowerState.ON && it.activity == TunerActivity.IDLE && !_scanning.value }
            .map { it.frequencyKhz }
            .distinctUntilChanged()
            .debounce(1_000)
            .onEach { store.saveLastFrequency(it) }
            .launchIn(scope)

        // Give saved presets the station name RDS reports for them.
        state.map { it.frequencyKhz to it.rds.programService }
            .distinctUntilChanged()
            .onEach { (khz, name) ->
                if (name == null) return@onEach
                if (presets.value.none { it != null && it.frequencyKhz == khz && it.name != name }) return@onEach
                store.updatePresets { list -> list.map { if (it != null && it.frequencyKhz == khz) it.copy(name = name) else it } }
            }
            .launchIn(scope)

        // The head unit's radio service can restart (a system update, a crash); bind it again when
        // it does, and keep trying while it is not back. Only a radio the user was hearing takes
        // the speakers back: a restart while another source plays must not switch the head unit
        // to the radio.
        state.filter { it.power == PowerState.ERROR && it.connectionLost }
            .onEach { lost ->
                // A loss while reconnecting is that reconnect's to retry.
                if (reconnecting?.isActive == true) return@onEach
                // Read before the dead tuner is powered off, which forgets it.
                val unitMuteHeld = (_tuner.value as? NwdTuner)?.holdsUnitMute == true
                trace("Conexão com o serviço de rádio perdida; religando em $RECONNECT_DELAY_MS ms")
                launchReconnect(takeSpeakers = !lost.muted, unitMuteHeld)
            }
            .launchIn(scope)
    }

    private fun launchReconnect(takeSpeakers: Boolean, unitMuteHeld: Boolean) {
        // One loop at a time: a loss seen while a failed restart starts its own loop is that loop's.
        reconnecting?.cancel()
        reconnectMuteHeld = unitMuteHeld
        reconnecting = scope.launch { reconnect(takeSpeakers, unitMuteHeld) }
    }

    /**
     * Binds the radio again after its connection was lost: [RECONNECT_DELAY_MS] later, then twice
     * as long after every attempt that failed, up to [MAX_RECONNECT_DELAY_MS], until the radio is
     * back or the user stops it. Only the first attempt uses [takeSpeakers]: by a later one the
     * user may have picked another source, which a reconnect must not switch away from (a radio
     * still the source plays again anyway). Every attempt keeps [unitMuteHeld], which a tuner that
     * failed to start does not know.
     */
    private suspend fun reconnect(takeSpeakers: Boolean, unitMuteHeld: Boolean) {
        var wait = RECONNECT_DELAY_MS
        var claim = takeSpeakers
        while (true) {
            delay(wait)
            val done = lifecycle.withLock {
                // Started, stopped or reconfigured meanwhile.
                if (_tuner.value.state.value.power != PowerState.ERROR) return@withLock true
                restart(store.settings.first(), claim, unitMuteHeld)
                _tuner.value.state.value.power == PowerState.ON
            }
            if (done) return
            claim = false
            wait = (wait * 2).coerceAtMost(MAX_RECONNECT_DELAY_MS)
            trace("O rádio não voltou; nova tentativa em $wait ms")
        }
    }

    /**
     * Powers the tuner on with the saved settings, unless it is already running. Opening the app
     * also takes the speakers back when the head unit had switched to another source; with
     * [takeSpeakers] false, as on an automatic reconnect, the source is left alone.
     */
    fun start(takeSpeakers: Boolean = true) {
        scope.launch {
            lifecycle.withLock {
                // Under the lock, so a reconnect attempt is never cut off in the middle of its claim.
                val wasReconnecting = reconnecting?.isActive == true
                reconnecting?.cancel()
                val settings = store.settings.first()
                if (_tuner.value.state.value.power == PowerState.ON && settings == activeSettings) {
                    if (takeSpeakers) runCatching { _tuner.value.reclaimSource() }
                    return@withLock
                }
                restartKeepingReconnect(wasReconnecting, settings, takeSpeakers)
            }
        }
    }

    fun stop() {
        reconnecting?.cancel()
        scope.launch {
            lifecycle.withLock {
                operation?.cancel()
                _tuner.value.powerOff()
                activeSettings = null
            }
        }
    }

    fun updateSettings(transform: (RadioSettings) -> RadioSettings) {
        scope.launch {
            store.updateSettings(transform)
            val settings = store.settings.first()
            lifecycle.withLock {
                val active = activeSettings
                if (active != null && !needsRestart(active, settings)) {
                    val tuner = _tuner.value
                    runCatching {
                        tuner.setSensitivity(settings.sensitivity)
                        tuner.setForceMono(settings.forceMono)
                        tuner.setRdsEnabled(settings.rdsEnabled)
                    }
                    activeSettings = settings
                } else {
                    val wasReconnecting = reconnecting?.isActive == true
                    reconnecting?.cancel()
                    restartKeepingReconnect(wasReconnecting, settings, takeSpeakers = true)
                }
            }
        }
    }

    fun tune(frequencyKhz: Int) {
        pendingKhz = frequencyKhz
        launchOperation {
            it.tune(frequencyKhz)
            if (pendingKhz == frequencyKhz) pendingKhz = null
        }
    }

    /** Moves [steps] channels from where the dial is heading, so fast repeated taps add up. */
    fun step(steps: Int) = tune(state.value.region.step(pendingKhz ?: state.value.frequencyKhz, steps))

    fun seek(direction: SeekDirection) {
        pendingKhz = null
        launchOperation { it.seek(direction) }
    }

    fun selectPreset(index: Int) {
        presets.value.getOrNull(index)?.let { tune(it.frequencyKhz) }
    }

    fun savePreset(index: Int) {
        val current = state.value
        scope.launch {
            store.updatePresets { list ->
                list.toMutableList().also { it[index] = Preset(current.frequencyKhz, current.rds.programService) }
            }
        }
    }

    fun clearPreset(index: Int) {
        scope.launch { store.updatePresets { list -> list.toMutableList().also { it[index] = null } } }
    }

    /** Next or previous saved preset, wrapping around; seeks when nothing is saved. */
    fun nextPreset(delta: Int) {
        val saved = presets.value.mapIndexedNotNull { i, p -> p?.let { i to it } }
        if (saved.isEmpty()) {
            seek(if (delta > 0) SeekDirection.UP else SeekDirection.DOWN)
            return
        }
        val currentIndex = saved.indexOfFirst { it.second.frequencyKhz == state.value.frequencyKhz }
        val next = when {
            currentIndex >= 0 -> Math.floorMod(currentIndex + delta, saved.size)
            delta > 0 -> saved.indexOfFirst { it.second.frequencyKhz > state.value.frequencyKhz }.takeIf { it >= 0 } ?: 0
            else -> saved.indexOfLast { it.second.frequencyKhz < state.value.frequencyKhz }.takeIf { it >= 0 } ?: saved.lastIndex
        }
        tune(saved[next].second.frequencyKhz)
    }

    fun setMuted(muted: Boolean) {
        scope.launch { runCatching { _tuner.value.setMuted(muted) } }
    }

    /** Flips what the tuner is actually using, which on head units with an MCU may differ from the saved setting. */
    fun toggleSensitivity() {
        val next = if (state.value.sensitivity == Sensitivity.DX) Sensitivity.LOCAL else Sensitivity.DX
        updateSettings { it.copy(sensitivity = next) }
    }

    /** Scans the whole band and fills the presets with the strongest stations, like a stock radio. */
    fun scanAndStore() {
        pendingKhz = null
        launchOperation { tuner ->
            _scanning.value = true
            try {
                val found = tuner.scanBand()
                if (found.isNotEmpty()) {
                    val best = found.strongest(PRESET_COUNT)
                    store.updatePresets { List(PRESET_COUNT) { i -> best.getOrNull(i)?.let { Preset(it.frequencyKhz) } } }
                }
            } finally {
                _scanning.value = false
            }
        }
    }

    /** Stops a running seek or scan. */
    fun cancelOperation() {
        operation?.cancel()
    }

    private fun launchOperation(block: suspend (Tuner) -> Unit) {
        val previous = operation
        operation = scope.launch {
            previous?.cancel()
            previous?.join()
            if (state.value.power != PowerState.ON) {
                // Dropped: the next step starts from what the dial shows, not from this tap.
                pendingKhz = null
                return@launch
            }
            runCatching { block(_tuner.value) }
        }
    }

    /**
     * @param unitMuteHeld the head unit is muted by this app through a tuner that lost its
     *   connection, so the new one must lift that mute when another source plays.
     */
    private suspend fun restart(settings: RadioSettings, takeSpeakers: Boolean = true, unitMuteHeld: Boolean = false) {
        operation?.cancel()
        pendingKhz = null
        // A tuner that lost its service cannot lift its mute when powered off, and then forgets it.
        val lost = (_tuner.value as? NwdTuner)?.takeIf { it.state.value.power == PowerState.ERROR }
        val muteHeld = unitMuteHeld || lost?.holdsUnitMute == true
        _tuner.value.powerOff()
        val tuner = createTuner(settings, takeSpeakers, muteHeld)
        // NWD's system stops an app that still holds the focus when the MCU switches away from
        // Android, so a focus taken for a previous tuner goes before this one can switch it.
        if (tuner.managesAudioSource) releaseAudioFocus()
        _tuner.value = tuner
        tuner.setSensitivity(settings.sensitivity)
        tuner.setForceMono(settings.forceMono)
        tuner.setRdsEnabled(settings.rdsEnabled)
        val frequency = store.lastFrequencyKhz() ?: settings.region.defaultFrequencyKhz
        tuner.powerOn(settings.region, frequency)
        activeSettings = settings
    }

    /**
     * A restart that replaced a running reconnect keeps its mute, and when it fails too, as while
     * the service is still down, the retries go on, without taking the speakers.
     */
    private suspend fun restartKeepingReconnect(wasReconnecting: Boolean, settings: RadioSettings, takeSpeakers: Boolean) {
        val muteHeld = wasReconnecting && reconnectMuteHeld
        restart(settings, takeSpeakers, muteHeld)
        if (wasReconnecting && _tuner.value.state.value.power == PowerState.ERROR) {
            trace("O rádio não voltou; nova tentativa em $RECONNECT_DELAY_MS ms")
            launchReconnect(takeSpeakers = false, muteHeld)
        }
    }

    private fun needsRestart(old: RadioSettings, new: RadioSettings) =
        old.backend != new.backend || old.i2cBus != new.i2cBus || old.i2cAddress != new.i2cAddress ||
            old.clockHz != new.clockHz || old.region != new.region

    private fun createTuner(settings: RadioSettings, takeSpeakers: Boolean, unitMuteHeld: Boolean): Tuner = when (settings.backend) {
        // Detected again on every start, so a reconnect finds the radio the unit has now.
        TunerBackend.AUTO -> {
            val found = detectBackend()?.takeIf { it == TunerBackend.NWD || it == TunerBackend.FYT }
            trace("Sintonizador automático: ${found ?: "nenhum rádio reconhecido"}")
            if (found != null) {
                createTuner(settings.copy(backend = found), takeSpeakers, unitMuteHeld)
            } else {
                UnavailableTuner("Nenhum rádio reconhecido", NO_RADIO_FOUND)
            }
        }
        TunerBackend.NWD -> NwdTuner(connectNwd, takeSpeakers = takeSpeakers, inheritedUnitMute = unitMuteHeld, trace = trace)
        TunerBackend.FYT -> FytTuner(connectFyt)
        TunerBackend.SIMULATOR -> SimulatedTuner()
        TunerBackend.QN8035 -> Qn8035Tuner(
            Qn8035Config(
                i2cBusNumber = settings.i2cBus,
                i2cAddress = settings.i2cAddress,
                clockHz = settings.clockHz,
            ),
        )
    }

    private companion object {
        const val RECONNECT_DELAY_MS = 2_000L
        const val MAX_RECONNECT_DELAY_MS = 60_000L

        const val NO_RADIO_FOUND = "Não reconheci o rádio desta central: não há o serviço NWD (com.nwd.radio.service) " +
            "nem o FYT (com.syu.ms). Escolha o sintonizador nos ajustes ou veja o Diagnóstico."
    }
}
