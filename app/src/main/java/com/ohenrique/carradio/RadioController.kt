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
import com.ohenrique.carradio.tuner.qn8035.Qn8035Config
import com.ohenrique.carradio.tuner.qn8035.Qn8035Tuner
import com.ohenrique.carradio.tuner.scanBand
import com.ohenrique.carradio.tuner.sim.SimulatedTuner
import com.ohenrique.carradio.tuner.strongest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
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
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class RadioController(
    private val store: RadioStore,
    private val scope: CoroutineScope,
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
                if (presets.value.none { it?.frequencyKhz == khz && it.name != name }) return@onEach
                store.updatePresets { list -> list.map { if (it?.frequencyKhz == khz) it.copy(name = name) else it } }
            }
            .launchIn(scope)
    }

    /** Powers the tuner on with the saved settings, unless it is already running. */
    fun start() {
        scope.launch {
            lifecycle.withLock {
                val settings = store.settings.first()
                if (state.value.power == PowerState.ON && settings == activeSettings) return@withLock
                restart(settings)
            }
        }
    }

    fun stop() {
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
                    tuner.setSensitivity(settings.sensitivity)
                    tuner.setForceMono(settings.forceMono)
                    tuner.setRdsEnabled(settings.rdsEnabled)
                    activeSettings = settings
                } else {
                    restart(settings)
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

    fun toggleSensitivity() = updateSettings {
        it.copy(sensitivity = if (it.sensitivity == Sensitivity.DX) Sensitivity.LOCAL else Sensitivity.DX)
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
            if (state.value.power != PowerState.ON) return@launch
            runCatching { block(_tuner.value) }
        }
    }

    private suspend fun restart(settings: RadioSettings) {
        operation?.cancel()
        pendingKhz = null
        _tuner.value.powerOff()
        val tuner = createTuner(settings)
        _tuner.value = tuner
        tuner.setSensitivity(settings.sensitivity)
        tuner.setForceMono(settings.forceMono)
        tuner.setRdsEnabled(settings.rdsEnabled)
        val frequency = store.lastFrequencyKhz() ?: settings.region.defaultFrequencyKhz
        tuner.powerOn(settings.region, frequency)
        activeSettings = settings
    }

    private fun needsRestart(old: RadioSettings, new: RadioSettings) =
        old.backend != new.backend || old.i2cBus != new.i2cBus || old.i2cAddress != new.i2cAddress ||
            old.clockHz != new.clockHz || old.region != new.region

    private fun createTuner(settings: RadioSettings): Tuner = when (settings.backend) {
        TunerBackend.SIMULATOR -> SimulatedTuner()
        TunerBackend.QN8035 -> Qn8035Tuner(
            Qn8035Config(
                i2cBusNumber = settings.i2cBus,
                i2cAddress = settings.i2cAddress,
                clockHz = settings.clockHz,
            ),
        )
    }
}
