package com.ohenrique.carradio.tuner.nwd

import java.io.Closeable
import java.io.IOException

/**
 * The radio of NWD head units (K4811, K2501 and others), as the tuner drives it. Three parts of the
 * vendor's system are involved, and an ordinary app reaches all of them without root or special
 * permissions:
 *
 * - `com.nwd.radio.service`, whose RadioFeature Binder service tunes. On MCU units (the K4811 among
 *   them) it forwards every call to the MCU, which owns the tuner chip.
 * - `com.nwd.kernel`, which talks to the MCU and decides which source reaches the speakers. Apps ask
 *   it for a source with broadcasts, and the radio stays silent until it is the source.
 * - `Settings.System`, where the vendor mirrors the MCU's state in `mcu_*` keys.
 *
 * This is the platform-neutral view of them; the Android app implements it with raw Binder
 * transactions, broadcasts and Settings reads. The RadioFeature calls block until the service
 * answers, so the tuner only makes them off the main thread. They throw [NwdException] when the
 * service refuses a call or cannot be reached; a service that died also triggers [onDisconnected].
 *
 * Frequencies are in the service's own unit: FM in 10 kHz on the K4811 (8750 is 87.5 MHz), AM in
 * kHz. The tuner works the FM unit out from [getRadioPoint].
 */
interface NwdService : Closeable {

    /** Which implementation the service runs, see [Nwd.RadioType]. */
    fun getRadioType(): Int

    /**
     * Band limits, index 0 for FM and index 1 for AM. Elements can be null or placeholders (see
     * [NwdRadioPoint.isValid]); the list is null when the service returned no array.
     */
    fun getRadioPoint(): List<NwdRadioPoint?>?

    /** The station the tuner is on, or null when the service has none to report. */
    fun getCurrentFrequency(): NwdFrequency?

    /** See [Nwd.State]. */
    fun getRadioState(): Int

    /**
     * Tunes to [frequency] on [band]. A [presetIndex] other than [Nwd.NO_PRESET] makes the service
     * tune what it stored in that slot of its own list instead, so the tuner always passes
     * [Nwd.NO_PRESET]. Nothing comes back: the result shows up in [getCurrentFrequency] and in
     * [NwdEvent.FrequencyChanged] notices. May block while the service talks to the MCU.
     */
    fun setCurrentFrequency(frequency: Int, band: Int, presetIndex: Int)

    /**
     * The service's `seek`. Which of [seek] and [search] stops on the next station (the other moves
     * one channel), and whether `up = true` raises the frequency, is learned at run time.
     */
    fun seek(up: Boolean)

    /** The service's `search`, see [seek]. */
    fun search(up: Boolean)

    /** Moves to the next band in the service's cycle (FM1, FM2, FM3, AM1, AM2, FM1...). */
    fun changeBand()

    /** A raw tuner action, see [Nwd.Command]. */
    fun sendRadioCommand(action: Int, value: Int)

    /** LOC on (only strong local stations) or off (DX). */
    fun setNearOn(on: Boolean)

    fun isNearOn(): Boolean

    /** Whether the station being received carries a stereo pilot (the vendor's `isHasStrero`). */
    fun hasStereoPilot(): Boolean

    /** Stereo decoding when on, mono when off (the vendor's `setStreroOn`). */
    fun setStereoOn(on: Boolean)

    /** The stereo decoding setting, not the pilot (the vendor's `isStreroOn`). */
    fun isStereoOn(): Boolean

    /** RDS RadioText of the current station; null or blank when there is none. */
    fun getRtMessage(): String?

    /**
     * Subscribes this client's RadioCallback, whose notices reach [listener]. The service does not
     * deliver them reliably to every client, so the tuner also polls the getters.
     */
    fun registerCallback()

    /** Removes the callback [registerCallback] added. */
    fun unregisterCallback()

    /** `mcu_current_source` (see [Nwd.Source]), or null when it cannot be read. */
    fun currentSource(): Int?

    /** `mcu_mute_state`: whether the whole head unit is muted, or null when it cannot be read. */
    fun isUnitMuted(): Boolean?

    /** `mcu_state` (see [Nwd.McuState]), or null when it cannot be read. */
    fun mcuState(): Int?

    /** True while a phone call holds the speakers, as far as Android can tell. */
    fun isPhoneCallActive(): Boolean

    /** Asks `com.nwd.kernel` to switch the MCU to [sourceId] ([Nwd.Action.REQUEST_CHANGE_SOURCE]). */
    fun requestSource(sourceId: Int)

    /** Announces that the app [appId] comes in or goes out ([Nwd.Action.APP_IN_OUT]). */
    fun sendAppInOut(appId: Int, operation: Int, event: Int)

    /** Tells the system the app [appId] starts playing ([Nwd.Action.MEDIA_PLAY]). */
    fun sendMediaPlay(appId: Int)

    /** Mutes or unmutes the whole head unit, every source included ([Nwd.Action.SET_MUTE]). */
    fun setUnitMuted(muted: Boolean)

    /** Receives callbacks, status broadcasts and Settings changes, on any thread. */
    var listener: ((NwdEvent) -> Unit)?

    /** Called when the service dies or the connection drops. */
    var onDisconnected: (() -> Unit)?
}

/**
 * The vendor's `Frequency` parcelable. On the wire the fields come as byte band, String psName,
 * int frequency. [frequency] is in the service's unit (see [NwdService]) and [programService] is the
 * RDS station name as the service has it: often blank, padded, or the text "null".
 */
data class NwdFrequency(val band: Int, val programService: String?, val frequency: Int)

/** The vendor's `RadioPoint` parcelable: a band's limits and channel step, in the service's unit. */
data class NwdRadioPoint(val min: Int, val max: Int, val step: Int) {
    /** False for the empty entries some services fill unused bands with. */
    val isValid: Boolean get() = min > 0 && max > min && step > 0
}

/** Something the head unit reported without being asked. */
sealed interface NwdEvent {
    /** RadioCallback `notifyState`, see [Nwd.State]. */
    data class StateChanged(val state: Int) : NwdEvent

    /**
     * RadioCallback `notifyCurrentFrequency`, or the [Nwd.Action.FREQUENCE_NEW] broadcast. A null
     * [NwdFrequency.programService] means the notice carried no name, not that the station has none.
     */
    data class FrequencyChanged(val frequency: NwdFrequency) : NwdEvent

    /** RadioCallback `notifyNearOn`. */
    data class NearOnChanged(val on: Boolean) : NwdEvent

    /** RadioCallback `notifyStereo`: the stereo pilot. */
    data class StereoPilotChanged(val stereo: Boolean) : NwdEvent

    /** RadioCallback `notifyRtMessage`. */
    data class RadioTextChanged(val text: String?) : NwdEvent

    /** RadioCallback `notifyRadioScanState`: non-zero while the service scans. */
    data class ScanStateChanged(val state: Int) : NwdEvent

    /** RadioCallback `notifyRadioPoint`, laid out like [NwdService.getRadioPoint]. */
    data class RadioPointChanged(val points: List<NwdRadioPoint?>) : NwdEvent

    /** `mcu_current_source` changed, or the [Nwd.Action.CHANGE_SOURCE] broadcast. */
    data class SourceChanged(val source: Int) : NwdEvent

    /** `mcu_mute_state` changed, or the [Nwd.Action.MUTE_STATE_CHANGE] broadcast. */
    data class UnitMuteChanged(val muted: Boolean) : NwdEvent

    /** `mcu_state` changed, or the [Nwd.Action.MCU_STATE_CHANGE] broadcast. */
    data class McuStateChanged(val state: Int) : NwdEvent
}

class NwdException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Names and codes of the NWD radio protocol, gathered from open NWD clients (gdm-launcher-k4811,
 * calm-launcher, Carnyx, OpenRadioFM, Radio+). The README says which of them backs each fact.
 */
object Nwd {
    const val RADIO_SERVICE_PACKAGE = "com.nwd.radio.service"
    const val RADIO_SERVICE_ACTION = "com.nwd.radio.service.ACTION_RADIO_SERVICE"
    /** The service class, for binding by name when [RADIO_SERVICE_ACTION] does not resolve. */
    const val RADIO_SERVICE_CLASS = "com.nwd.radio.service.RadioService"

    /** Handles [Action.REQUEST_CHANGE_SOURCE]. */
    const val KERNEL_PACKAGE = "com.nwd.kernel"

    /** The stock radio screen. It is not the tuner service. */
    const val RADIO_APP_PACKAGE = "com.nwd.radio"

    /** The [NwdService.setCurrentFrequency] preset index that means "no preset". */
    const val NO_PRESET = 0

    /** RadioFeature transaction codes: the AIDL methods in declaration order, from 1. */
    object Feature {
        const val DESCRIPTOR = "com.nwd.radio.service.RadioFeature"

        /** (int frequency, byte band, int presetIndex) → void. */
        const val SET_CURRENT_FREQUENCY = 1
        /** () → Frequency. */
        const val GET_CURRENT_FREQUENCY = 2
        /** (boolean up) → void. */
        const val SEEK = 3
        /** (boolean up) → void. */
        const val SEARCH = 4
        /** () → void. */
        const val CHANGE_BAND = 5
        /** (boolean on) → void. */
        const val SET_NEAR_ON = 8
        /** () → boolean. */
        const val IS_NEAR_ON = 9
        /** () → boolean: stereo pilot present. */
        const val IS_HAS_STRERO = 10
        /** (boolean on) → void: stereo or mono. */
        const val SET_STRERO_ON = 11
        /** () → boolean. */
        const val IS_STRERO_ON = 12
        /** () → boolean. Diagnostics only. */
        const val IS_RADIO_BACK_SERVICE_ON = 14
        /** (int flag) → boolean. Diagnostics only. */
        const val GET_RDS_STATE = 16
        /** () → byte. Diagnostics only. */
        const val GET_PTY_TYPE = 18
        /** () → Frequency[]: the service's own presets. Diagnostics only. */
        const val GET_PREFAB_FREQUENCY = 21
        /** () → RadioPoint[]. */
        const val GET_RADIO_POINT = 22
        /** () → byte. */
        const val GET_RADIO_STATE = 23
        /** (RadioCallback) → void, the callback as a strong binder. */
        const val REGIST_CALLBACK = 24
        /** (RadioCallback) → void, the same binder that was registered. */
        const val UNREGIST_CALLBACK = 25
        /** (byte action, byte value) → void. */
        const val SEND_RADIO_COMMAND = 27
        /** () → String. */
        const val GET_RT_MESSAGE = 28
        /** () → int. */
        const val GET_RADIO_TYPE = 29
        /** () → int. Diagnostics only. */
        const val GET_CURRENT_SCAN_STATE = 30
    }

    /**
     * RadioCallback transaction codes. None is one-way: the service waits for the reply, so the
     * client acknowledges every code at once and never calls the service back from inside one.
     */
    object Callback {
        const val DESCRIPTOR = "com.nwd.radio.service.RadioCallback"

        /** (byte state). */
        const val NOTIFY_STATE = 1
        /** (byte band, int frequency, String psName, int presetIndex): a different order than Frequency. */
        const val NOTIFY_CURRENT_FREQUENCY = 2
        /** (boolean on). */
        const val NOTIFY_NEAR_ON = 3
        /** (boolean stereo): the pilot. */
        const val NOTIFY_STEREO = 4
        /** (boolean on): the stereo setting. */
        const val NOTIFY_STEREO_ON = 5
        /** No arguments. */
        const val NOTIFY_RDS_STATE_CHANGE = 6
        /** (byte pty). */
        const val NOTIFY_CURRENT_PTY_TYPE = 7
        /** (Frequency[]). */
        const val NOTIFY_PREFAB_FREQUENCY = 8
        /** (byte pty). */
        const val NOTIFY_PREFAB_PTY_TYPE = 9
        /** (RadioPoint[]). */
        const val NOTIFY_RADIO_POINT = 10
        /** (boolean ta). */
        const val NOTIFY_CURRENT_IS_TA = 11
        /** (boolean show). */
        const val NOTIFY_RDS_SHOW_STATE = 12
        /** (String text). */
        const val NOTIFY_RT_MESSAGE = 13
        /** (int state). */
        const val NOTIFY_RADIO_SCAN_STATE = 14

        const val LAST = NOTIFY_RADIO_SCAN_STATE
    }

    /** Band bytes. Each FM band is the same FM range with its own preset bank. */
    object Band {
        const val FM1 = 0
        const val FM2 = 1
        const val FM3 = 2
        const val AM1 = 3
        const val AM2 = 4
        /** Reported as AM by some MCU services. */
        private const val AM3 = 5

        fun isFm(band: Int): Boolean = band in FM1..FM3

        fun isAm(band: Int): Boolean = band in AM1..AM3
    }

    /** [NwdService.getRadioType] values. 3 is also used by MediaTek builds. */
    object RadioType {
        /** The MCU owns the tuner (the K4811 build has this one). */
        const val MCU = 0
        const val ARM = 1
        const val ALLWINNER = 2
        /** The Unisoc (SPRD) SoC's own FM receiver. */
        const val SOC = 3
    }

    /** [NwdService.getRadioState] values. Clients disagree on what 0 and 3 mean, but not on 1 or on 2 to 4 being busy. */
    object State {
        /** Not powered or not ready yet. */
        const val CLOSED = 0
        /** Playing, ready for commands. */
        const val NORMAL = 1

        /** Seeking, scanning or previewing stations. */
        fun isBusy(state: Int): Boolean = state in 2..4
    }

    /** [NwdService.sendRadioCommand] actions. */
    object Command {
        /** Selects a band directly: action `SELECT_BAND + band`, value [ON]. */
        const val SELECT_BAND = 14
        const val ON = 1
    }

    /** MCU source ids, as in `mcu_current_source` and `extra_source_id`. */
    object Source {
        const val ANDROID = 0
        const val RADIO = 4
        const val AUX = 7
        const val DVR = 8
    }

    /** App ids and operations of [Action.APP_IN_OUT] and [Action.MEDIA_PLAY]. Not the same numbers as [Source]. */
    object App {
        const val RADIO = 8
        const val OPERATION_IN = 1
        const val EVENT_NONE = 0
    }

    /** `mcu_state` and `extra_mcu_state` values: anything else means going to sleep or off. */
    object McuState {
        const val AWAKE = 1
    }

    object Action {
        /** Sent. Extras [Extra.APP_ID], [Extra.APP_OPERATION], [Extra.APP_EVENT], all int. */
        const val APP_IN_OUT = "com.nwd.action.ACTION_APP_IN_OUT"
        /** Sent. Extra [Extra.APP_ID], int. */
        const val MEDIA_PLAY = "com.nwd.ACTION_MEDIA_PLAY"
        /** Sent. Extra [Extra.SOURCE_ID], byte: the receiver reads a byte and ignores an int. */
        const val REQUEST_CHANGE_SOURCE = "com.nwd.action.ACTION_REQUEST_CHANGE_SOURCE"
        /** Sent. Extra [Extra.MUTE], boolean. */
        const val SET_MUTE = "com.nwd.action.ACTION_SET_MUTE"

        /** Received. Extra [Extra.SOURCE_ID], a byte as far as known. */
        const val CHANGE_SOURCE = "com.nwd.action.ACTION_CHANGE_SOURCE"
        /** Received. Extras [Extra.FREQUENCE] int, [Extra.BAND] byte, sometimes [Extra.PS_NAME] String. */
        const val FREQUENCE_NEW = "com.nwd.action.ACTION_SEND_RADIO_FREQUENCE_NEW"
        /** Received. Extra [Extra.MUTE_STATE], byte, 1 = muted. */
        const val MUTE_STATE_CHANGE = "com.nwd.ACTION_MUTE_STATE_CHANGE"
        /** Received. Extra [Extra.MCU_STATE], byte, see [McuState]. */
        const val MCU_STATE_CHANGE = "com.nwd.action.ACTION_MCU_STATE_CHANGE"
    }

    object Extra {
        const val APP_ID = "extra_app_id"
        const val APP_OPERATION = "extra_app_operation"
        const val APP_EVENT = "extra_app_event"
        const val SOURCE_ID = "extra_source_id"
        const val FREQUENCE = "extra_frequence"
        const val BAND = "extra_band"
        const val PS_NAME = "extra_ps_name"
        const val MUTE = "extra_mute"
        const val MUTE_STATE = "extra_mute_state"
        const val MCU_STATE = "extra_mcu_state"
    }

    /** `Settings.System` keys the vendor keeps the MCU's state in. Readable by any app. */
    object Setting {
        const val CURRENT_SOURCE = "mcu_current_source"
        /** Non-zero while the whole unit is muted. */
        const val MUTE_STATE = "mcu_mute_state"
        const val MCU_STATE = "mcu_state"
    }
}
