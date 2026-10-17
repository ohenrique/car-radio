package com.ohenrique.carradio.tuner

import kotlinx.coroutines.flow.StateFlow

/**
 * Hardware-independent FM tuner.
 *
 * Every head unit platform (a tuner chip on I2C, an MCU service, a simulator) implements this
 * interface, so the UI never knows which one it is talking to. All frequencies are in kHz.
 */
interface Tuner {
    /** Short human readable name, shown in the settings screen. */
    val name: String

    val state: StateFlow<TunerState>

    /**
     * True when the tuner itself decides whether the radio reaches the speakers (an MCU audio
     * source switch), so the app must not mute it on Android audio focus changes.
     */
    val managesAudioSource: Boolean get() = false

    /** Powers the tuner on and tunes to [frequencyKhz] (snapped to the band). */
    suspend fun powerOn(region: Region, frequencyKhz: Int)

    suspend fun powerOff()

    /** Tunes to [frequencyKhz], snapped to the current band grid. */
    suspend fun tune(frequencyKhz: Int)

    /**
     * Searches for the next receivable station in [direction], wrapping around the band edge
     * when [wrap] is true. Returns the station found, or null when nothing was found (the tuner
     * then goes back to the frequency it was on).
     */
    suspend fun seek(direction: SeekDirection, wrap: Boolean = true): FoundStation?

    suspend fun setMuted(muted: Boolean)

    suspend fun setForceMono(forceMono: Boolean)

    suspend fun setSensitivity(sensitivity: Sensitivity)

    suspend fun setRdsEnabled(enabled: Boolean)
}

enum class SeekDirection { UP, DOWN }

/** LOC only stops on strong local stations, DX also stops on weak distant ones. */
enum class Sensitivity { LOCAL, DX }

enum class PowerState { OFF, STARTING, ON, ERROR }

enum class TunerActivity { IDLE, SEEKING }

data class FoundStation(val frequencyKhz: Int, val rssiDbuv: Int?)

data class SignalInfo(
    /** Received signal strength in dBµV, when the hardware reports it. */
    val rssiDbuv: Int? = null,
    val snrDb: Int? = null,
    val stereo: Boolean = false,
) {
    /** Signal quality from 0 to 4, for a bars indicator. */
    val bars: Int
        get() = when (val rssi = rssiDbuv) {
            null -> 0
            else -> when {
                rssi >= 45 -> 4
                rssi >= 35 -> 3
                rssi >= 25 -> 2
                rssi >= 15 -> 1
                else -> 0
            }
        }
}

data class RdsInfo(
    val programId: Int? = null,
    /** Program Service name: the 8 character station name. */
    val programService: String? = null,
    val radioText: String? = null,
    val programType: Int? = null,
) {
    companion object {
        val EMPTY = RdsInfo()
    }
}

data class TunerState(
    val power: PowerState = PowerState.OFF,
    val region: Region = Region.BRAZIL,
    val frequencyKhz: Int = Region.BRAZIL.defaultFrequencyKhz,
    val activity: TunerActivity = TunerActivity.IDLE,
    val signal: SignalInfo = SignalInfo(),
    val rds: RdsInfo = RdsInfo.EMPTY,
    val muted: Boolean = false,
    val forceMono: Boolean = false,
    val sensitivity: Sensitivity = Sensitivity.DX,
    val rdsEnabled: Boolean = true,
    val error: String? = null,
)
