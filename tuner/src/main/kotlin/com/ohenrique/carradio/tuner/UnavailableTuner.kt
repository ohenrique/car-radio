package com.ohenrique.carradio.tuner

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Stands in when the head unit has no radio the app knows how to drive: powering on only reports
 * [message] as the error, so the screen can say what is missing. Everything else does nothing.
 */
class UnavailableTuner(override val name: String, private val message: String) : Tuner {

    override val reportsSignalStrength = false

    private val _state = MutableStateFlow(TunerState())
    override val state: StateFlow<TunerState> = _state.asStateFlow()

    override suspend fun powerOn(region: Region, frequencyKhz: Int) {
        _state.update {
            it.copy(power = PowerState.ERROR, region = region, frequencyKhz = region.snap(frequencyKhz), error = message)
        }
    }

    override suspend fun powerOff() {
        _state.update { it.copy(power = PowerState.OFF) }
    }

    override suspend fun tune(frequencyKhz: Int) {}

    override suspend fun seek(direction: SeekDirection, wrap: Boolean): FoundStation? = null

    override suspend fun setMuted(muted: Boolean) {}

    override suspend fun setForceMono(forceMono: Boolean) {}

    override suspend fun setSensitivity(sensitivity: Sensitivity) {}

    override suspend fun setRdsEnabled(enabled: Boolean) {}
}
