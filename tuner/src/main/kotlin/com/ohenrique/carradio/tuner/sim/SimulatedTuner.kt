package com.ohenrique.carradio.tuner.sim

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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * A pretend tuner with a handful of fictional stations, so the app can be used and tested
 * away from the car.
 */
class SimulatedTuner(
    private val stations: List<SimulatedStation> = DEFAULT_STATIONS,
    private val stepDelayMs: Long = 15,
) : Tuner {

    override val name = "Simulador"

    private val _state = MutableStateFlow(TunerState())
    override val state: StateFlow<TunerState> = _state.asStateFlow()

    override suspend fun powerOn(region: Region, frequencyKhz: Int) {
        _state.update { it.copy(power = PowerState.ON, region = region, error = null) }
        tune(frequencyKhz)
    }

    override suspend fun powerOff() {
        _state.update { it.copy(power = PowerState.OFF, signal = SignalInfo(), rds = RdsInfo.EMPTY) }
    }

    override suspend fun tune(frequencyKhz: Int) {
        val khz = _state.value.region.snap(frequencyKhz)
        val station = stationAt(khz)
        _state.update {
            it.copy(
                frequencyKhz = khz,
                signal = SignalInfo(
                    rssiDbuv = station?.rssiDbuv ?: 8,
                    snrDb = if (station != null) 25 else 3,
                    stereo = station != null && !it.forceMono,
                ),
                rds = if (station != null && it.rdsEnabled) {
                    RdsInfo(programService = station.name, radioText = station.radioText)
                } else {
                    RdsInfo.EMPTY
                },
            )
        }
    }

    override suspend fun seek(direction: SeekDirection, wrap: Boolean): FoundStation? {
        val region = _state.value.region
        val origin = _state.value.frequencyKhz
        val channels = (region.maxKhz - region.minKhz) / region.stepKhz
        val delta = if (direction == SeekDirection.UP) 1 else -1
        _state.update { it.copy(activity = TunerActivity.SEEKING) }
        try {
            var khz = origin
            repeat(channels) {
                val next = khz + delta * region.stepKhz
                if (next !in region && !wrap) return null.also { tune(origin) }
                khz = region.step(khz, delta)
                delay(stepDelayMs)
                _state.update { s -> s.copy(frequencyKhz = khz) }
                val station = stationAt(khz)
                if (station != null && station.rssiDbuv >= threshold()) {
                    tune(khz)
                    return FoundStation(khz, station.rssiDbuv)
                }
            }
            tune(origin)
            return null
        } finally {
            _state.update { it.copy(activity = TunerActivity.IDLE) }
        }
    }

    override suspend fun setMuted(muted: Boolean) = _state.update { it.copy(muted = muted) }

    override suspend fun setForceMono(forceMono: Boolean) {
        _state.update { it.copy(forceMono = forceMono) }
        tune(_state.value.frequencyKhz)
    }

    override suspend fun setSensitivity(sensitivity: Sensitivity) = _state.update { it.copy(sensitivity = sensitivity) }

    override suspend fun setRdsEnabled(enabled: Boolean) {
        _state.update { it.copy(rdsEnabled = enabled) }
        tune(_state.value.frequencyKhz)
    }

    private fun threshold() = if (_state.value.sensitivity == Sensitivity.LOCAL) 30 else 12

    private fun stationAt(khz: Int) = stations.firstOrNull { it.frequencyKhz == khz }

    companion object {
        val DEFAULT_STATIONS = listOf(
            SimulatedStation(76_500, "FM EST", 18, "Faixa estendida"),
            SimulatedStation(89_100, "ROCK 89", 44, "Clássicos do rock o dia todo"),
            SimulatedStation(90_500, "NOTICIA", 38, "Notícias e trânsito a cada 15 minutos"),
            SimulatedStation(94_700, "SUAVE", 26, null),
            SimulatedStation(96_100, "POP 96", 47, "Tocando agora: sucesso do momento"),
            SimulatedStation(98_500, "MPB FM", 33, "O melhor da música brasileira"),
            SimulatedStation(101_700, "JAZZ", 21, null),
            SimulatedStation(104_700, "ESPORTE", 40, "Futebol ao vivo"),
            SimulatedStation(106_300, "DANCE", 15, null),
        )
    }
}

data class SimulatedStation(
    val frequencyKhz: Int,
    val name: String,
    val rssiDbuv: Int,
    val radioText: String?,
)
