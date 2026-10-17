package com.ohenrique.carradio.tuner.fyt

import java.io.Closeable
import java.io.IOException

/**
 * The FYT system service `com.syu.ms` (McuService), which owns the MCU and through it the tuner
 * chip, the audio source switch and the amplifier. Apps bind it with action `com.syu.ms.toolkit`
 * and get one binder per module (main, radio, sound...).
 *
 * This is the platform-neutral view of it; the Android app implements it with raw Binder calls.
 */
interface SyuToolkit : Closeable {
    /** Returns the module with [code] (see [Syu]), or null when the service does not offer it. */
    fun module(code: Int): SyuModule?

    /** Receives every update pushed by the modules this client registered with. */
    var listener: ((SyuUpdate) -> Unit)?

    /** Called when the service dies or the connection drops. */
    var onDisconnected: (() -> Unit)?
}

interface SyuModule {
    val code: Int

    /** Sends a command; the service answers, if at all, through an update. */
    fun cmd(command: Int, vararg ints: Int)

    /** Subscribes to [updateCode]; the service pushes the current value at once and then every change. */
    fun register(updateCode: Int)

    fun unregister(updateCode: Int)
}

class SyuUpdate(
    val module: Int,
    val code: Int,
    val ints: IntArray?,
    val strings: Array<String?>?,
) {
    fun int(index: Int = 0): Int? = ints?.getOrNull(index)

    fun string(index: Int = 0): String? = strings?.getOrNull(index)
}

class SyuException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Codes of the com.syu.ms protocol, from the FYT 7862/8581 launcher's com.syu.ipc classes
 * (FinalMainServer, FinalMain, FinalRadio, FinalSound) as reverse engineered in
 * github.com/vasyl91/FYT-Launcher-Mod, cross-checked with github.com/PimpinPumpkin/FytRadio.
 */
object Syu {
    const val PACKAGE = "com.syu.ms"
    const val TOOLKIT_ACTION = "com.syu.ms.toolkit"
    /** The service class inside [PACKAGE], for firmware that does not resolve [TOOLKIT_ACTION]. */
    const val TOOLKIT_SERVICE = "app.ToolkitService"

    const val MODULE_MAIN = 0
    const val MODULE_RADIO = 1
    const val MODULE_SOUND = 4

    object Main {
        /** cmd: ints = [app id]; makes the MCU route that source to the speakers. */
        const val C_APP_ID = 0
        const val U_APP_ID = 0

        const val APP_ID_NULL = 0
        const val APP_ID_RADIO = 1
        /** Used instead of [APP_ID_RADIO] by some FYT builds. */
        const val APP_ID_CAR_RADIO = 11

        // Phone calls, which own the speakers until they end.
        const val APP_ID_BTPHONE = 2
        const val APP_ID_CAR_BTPHONE = 12
        const val APP_ID_3GPHONE = 15
    }

    object Radio {
        const val C_FREQ_UP = 3
        const val C_FREQ_DOWN = 4
        const val C_SEEK_UP = 5
        const val C_SEEK_DOWN = 6
        const val C_BAND = 11
        const val C_FREQ = 13
        const val C_RDS_ENABLE = 16
        const val C_STEREO = 17
        const val C_LOC = 18

        const val BAND_SWITCH_FM = -3
        /** FinalRadio's modes for [C_FREQ]. */
        const val FREQ_DIRECT = 1

        /** ints[0] = frequency: FM in 10 kHz units (kHz on older firmware), AM in kHz. */
        const val U_FREQ = 1
        const val U_PTY_ID = 5
        const val U_RDS_TEXT = 13
        const val U_RDS_CHANNEL_TEXT = 14
        const val U_RDS_ENABLE = 15
        const val U_EXTRA_FREQ_INFO = 16
        const val U_STEREO = 21
        const val U_SEARCH_STATE = 22
        const val U_LOC = 23
        const val U_PS_TEXT = 26

        const val SEARCH_STATE_NONE = 0
    }

    object Sound {
        const val C_VOL = 0
        const val U_MUTE = 3

        const val VOL_MUTE = -3
        const val VOL_UNMUTE = -4
    }
}
