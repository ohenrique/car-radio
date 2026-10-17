package com.ohenrique.carradio.tuner.fyt

import com.ohenrique.carradio.tuner.fyt.Syu.Main
import com.ohenrique.carradio.tuner.fyt.Syu.Radio
import com.ohenrique.carradio.tuner.fyt.Syu.Sound
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The com.syu.ms service and the MCU behind it, as far as the radio is concerned. Frequencies are
 * in the MCU's own unit ([unit] kHz per count: 10 on UIS7862/8581, 1 on older firmware).
 *
 * Updates are delivered on the caller's thread, right away, unless [seekScope] is given: then a
 * seek runs in the background one channel per [seekStepMs], like the real MCU.
 */
class FakeMcu(
    var frequency: Int = 9_810,
    val stations: Set<Int> = setOf(8_910, 9_610, 9_850, 10_630),
    val min: Int = 8_750,
    val max: Int = 10_800,
    val step: Int = 10,
    var appId: Int = 3,
    val acceptedAppIds: Set<Int> = setOf(Main.APP_ID_RADIO),
    val reportsAppId: Boolean = true,
    val directTune: Boolean = true,
    val reportsSearchState: Boolean = true,
    val seekScope: CoroutineScope? = null,
    val seekStepMs: Long = 20,
    var local: Boolean = false,
    var rds: Boolean = true,
) : SyuToolkit {
    val commands = mutableListOf<Triple<Int, Int, List<Int>>>()
    var muted = true
    var closed = false
    private val registered = mutableSetOf<Pair<Int, Int>>()

    override var listener: ((SyuUpdate) -> Unit)? = null
    override var onDisconnected: (() -> Unit)? = null

    override fun module(code: Int): SyuModule? = when (code) {
        Syu.MODULE_MAIN, Syu.MODULE_RADIO, Syu.MODULE_SOUND -> Module(code)
        else -> null
    }

    override fun close() {
        closed = true
    }

    fun commandsTo(module: Int, command: Int) = commands.filter { it.first == module && it.second == command }.map { it.third }

    /** Another app takes the speakers, like the music player would. */
    fun switchSource(id: Int) {
        appId = id
        push(Syu.MODULE_MAIN, Main.U_APP_ID, appId)
    }

    private inner class Module(override val code: Int) : SyuModule {
        override fun cmd(command: Int, vararg ints: Int) {
            commands += Triple(code, command, ints.toList())
            when (code) {
                Syu.MODULE_MAIN -> if (command == Main.C_APP_ID) {
                    val id = ints.first()
                    if (id in acceptedAppIds) switchSource(id)
                }
                Syu.MODULE_SOUND -> if (command == Sound.C_VOL) when (ints.first()) {
                    Sound.VOL_MUTE -> applyMute(true)
                    Sound.VOL_UNMUTE -> applyMute(false)
                }
                Syu.MODULE_RADIO -> radioCmd(command, ints)
            }
        }

        override fun register(updateCode: Int) {
            registered += code to updateCode
            current(code, updateCode)
        }

        override fun unregister(updateCode: Int) {
            registered -= code to updateCode
        }
    }

    private fun radioCmd(command: Int, ints: IntArray) {
        when (command) {
            Radio.C_FREQ_UP -> moveTo(next(frequency, 1))
            Radio.C_FREQ_DOWN -> moveTo(next(frequency, -1))
            Radio.C_FREQ -> if (directTune && ints.size == 2 && ints[0] == Radio.FREQ_DIRECT) moveTo(ints[1])
            Radio.C_SEEK_UP -> seek(1)
            Radio.C_SEEK_DOWN -> seek(-1)
            Radio.C_LOC -> {
                local = ints.first() != 0
                push(Syu.MODULE_RADIO, Radio.U_LOC, if (local) 1 else 0)
            }
            Radio.C_RDS_ENABLE -> {
                rds = ints.first() != 0
                push(Syu.MODULE_RADIO, Radio.U_RDS_ENABLE, if (rds) 1 else 0)
            }
        }
    }

    private fun seek(delta: Int) {
        val run: suspend () -> Unit = {
            val origin = frequency
            if (reportsSearchState) push(Syu.MODULE_RADIO, Radio.U_SEARCH_STATE, if (delta > 0) 2 else 3)
            var f = origin
            var found = false
            for (i in 0 until (max - min) / step) {
                if (seekScope != null) delay(seekStepMs)
                f = next(f, delta)
                moveTo(f)
                if (f in stations) {
                    found = true
                    break
                }
            }
            if (!found) moveTo(origin)
            if (reportsSearchState) push(Syu.MODULE_RADIO, Radio.U_SEARCH_STATE, Radio.SEARCH_STATE_NONE)
        }
        if (seekScope != null) seekScope.launch { run() } else kotlinx.coroutines.runBlocking { run() }
    }

    private fun next(f: Int, delta: Int): Int {
        val n = f + delta * step
        return when {
            n > max -> min
            n < min -> max
            else -> n
        }
    }

    private fun moveTo(f: Int) {
        frequency = f
        push(Syu.MODULE_RADIO, Radio.U_FREQ, f)
    }

    private fun applyMute(value: Boolean) {
        muted = value
        push(Syu.MODULE_SOUND, Sound.U_MUTE, if (value) 1 else 0)
    }

    private fun current(module: Int, code: Int) {
        when (module) {
            Syu.MODULE_MAIN -> if (code == Main.U_APP_ID && reportsAppId) push(module, code, appId)
            Syu.MODULE_SOUND -> if (code == Sound.U_MUTE) push(module, code, if (muted) 1 else 0)
            Syu.MODULE_RADIO -> when (code) {
                Radio.U_BAND -> push(module, code, Radio.BAND_FM_INDEX_BEGIN)
                Radio.U_FREQ -> push(module, code, frequency)
                Radio.U_EXTRA_FREQ_INFO -> push(module, code, min, max, step, (max - min) / step)
                Radio.U_LOC -> push(module, code, if (local) 1 else 0)
                Radio.U_RDS_ENABLE -> push(module, code, if (rds) 1 else 0)
            }
        }
    }

    private fun push(module: Int, code: Int, vararg ints: Int, strings: Array<String?>? = null) {
        if (module to code !in registered) return
        listener?.invoke(SyuUpdate(module, code, ints, strings))
    }

    fun pushText(code: Int, text: String) {
        if (Syu.MODULE_RADIO to code !in registered) return
        listener?.invoke(SyuUpdate(Syu.MODULE_RADIO, code, null, arrayOf(text)))
    }
}
