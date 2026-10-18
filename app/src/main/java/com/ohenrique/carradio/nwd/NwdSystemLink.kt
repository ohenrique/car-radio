package com.ohenrique.carradio.nwd

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.ohenrique.carradio.tuner.nwd.Nwd
import com.ohenrique.carradio.tuner.nwd.NwdEvent
import com.ohenrique.carradio.tuner.nwd.NwdFrequency
import java.io.Closeable

/**
 * The parts of the NWD radio that live outside com.nwd.radio.service: the MCU's state, which the
 * vendor mirrors in `Settings.System`, the status broadcasts its apps send, and the broadcasts that
 * ask com.nwd.kernel for the speakers. Nothing here needs a permission on Android 10, and nothing
 * here writes a setting.
 *
 * Changes reach [listener] on the main thread, from Settings observers and broadcast receivers that
 * run between [start] and [close].
 */
internal class NwdSystemLink(private val context: Context) : Closeable {

    @Volatile var listener: ((NwdEvent) -> Unit)? = null

    private val resolver = context.contentResolver
    private val audio: AudioManager? = context.getSystemService(AudioManager::class.java)
    private val main = Handler(Looper.getMainLooper())

    /** Source requests go straight to the package known to handle them, when it is there. */
    private val kernelPackage = Nwd.KERNEL_PACKAGE.takeIf { installed(context, it) }

    private val observers = mutableListOf<ContentObserver>()
    private var receiver: BroadcastReceiver? = null
    private var started = false
    @Volatile private var closed = false

    fun start() {
        synchronized(this) { register() }
    }

    private fun register() {
        if (closed || started) return
        started = true
        for ((key, event) in WATCHED_SETTINGS) {
            val observer = object : ContentObserver(main) {
                override fun onChange(selfChange: Boolean) {
                    readInt(key)?.let { emit(event(it)) }
                }
            }
            // A key the build lacks can still be watched; it just never changes.
            runCatching {
                resolver.registerContentObserver(Settings.System.getUriFor(key), false, observer)
                observers += observer
            }
        }
        val filter = IntentFilter().apply { RECEIVED_ACTIONS.forEach(::addAction) }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                eventOf(intent)?.let(::emit)
            }
        }
        // The vendor's apps send these without a permission or a package, so the receiver must be
        // exported.
        runCatching {
            ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
            this.receiver = receiver
        }
    }

    override fun close() {
        synchronized(this) {
            if (closed) return
            closed = true
            listener = null
            observers.forEach { runCatching { resolver.unregisterContentObserver(it) } }
            observers.clear()
            receiver?.let { runCatching { context.unregisterReceiver(it) } }
            receiver = null
        }
    }

    /** A `Settings.System` integer, or null when the key is missing, not a number, or unreadable. */
    fun readInt(key: String): Int? = try {
        Settings.System.getInt(resolver, key)
    } catch (e: Exception) {
        null
    }

    /** As far as Android can tell: NWD's own phone app may not set the audio mode. */
    fun isPhoneCallActive(): Boolean {
        val mode = audio?.mode ?: return false
        return mode == AudioManager.MODE_RINGTONE || mode == AudioManager.MODE_IN_CALL ||
            mode == AudioManager.MODE_IN_COMMUNICATION
    }

    /** The receiver reads the id with getByteExtra, which ignores an Integer, so it goes as a Byte. */
    fun requestSource(sourceId: Int) = deliver(
        Intent(Nwd.Action.REQUEST_CHANGE_SOURCE).putExtra(Nwd.Extra.SOURCE_ID, sourceId.toByte()),
        kernelPackage,
    )

    fun sendAppInOut(appId: Int, operation: Int, event: Int) = deliver(
        Intent(Nwd.Action.APP_IN_OUT)
            .putExtra(Nwd.Extra.APP_ID, appId)
            .putExtra(Nwd.Extra.APP_OPERATION, operation)
            .putExtra(Nwd.Extra.APP_EVENT, event),
        null,
    )

    fun sendMediaPlay(appId: Int) = deliver(Intent(Nwd.Action.MEDIA_PLAY).putExtra(Nwd.Extra.APP_ID, appId), null)

    fun setUnitMuted(muted: Boolean) = deliver(Intent(Nwd.Action.SET_MUTE).putExtra(Nwd.Extra.MUTE, muted), null)

    /**
     * Every broadcast goes out here. A [targetPackage] reaches that package's receivers, whether
     * registered in its manifest or at run time, and nobody else's; null sends it to every app that
     * listens, which the announcements with more than one consumer need.
     */
    private fun deliver(intent: Intent, targetPackage: String?) {
        if (targetPackage != null) intent.setPackage(targetPackage)
        context.sendBroadcast(intent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND))
    }

    private fun emit(event: NwdEvent) {
        if (closed) return
        runCatching { listener?.invoke(event) }
    }

    private fun eventOf(intent: Intent): NwdEvent? = when (intent.action) {
        Nwd.Action.FREQUENCE_NEW -> intent.number(Nwd.Extra.FREQUENCE)?.let { frequency ->
            // The stock clients read the band with a default of FM1, so a missing one means FM1.
            val band = intent.number(Nwd.Extra.BAND) ?: Nwd.Band.FM1
            NwdEvent.FrequencyChanged(NwdFrequency(band, intent.text(Nwd.Extra.PS_NAME), frequency))
        }
        Nwd.Action.CHANGE_SOURCE -> intent.number(Nwd.Extra.SOURCE_ID)?.let { NwdEvent.SourceChanged(it) }
        Nwd.Action.MUTE_STATE_CHANGE -> intent.number(Nwd.Extra.MUTE_STATE)?.let { NwdEvent.UnitMuteChanged(it != 0) }
        Nwd.Action.MCU_STATE_CHANGE -> intent.number(Nwd.Extra.MCU_STATE)?.let { NwdEvent.McuStateChanged(it) }
        else -> null
    }

    companion object {
        private val RECEIVED_ACTIONS = listOf(
            Nwd.Action.FREQUENCE_NEW,
            Nwd.Action.CHANGE_SOURCE,
            Nwd.Action.MUTE_STATE_CHANGE,
            Nwd.Action.MCU_STATE_CHANGE,
        )

        private val WATCHED_SETTINGS: List<Pair<String, (Int) -> NwdEvent>> = listOf(
            Nwd.Setting.CURRENT_SOURCE to { NwdEvent.SourceChanged(it) },
            Nwd.Setting.MUTE_STATE to { NwdEvent.UnitMuteChanged(it != 0) },
            Nwd.Setting.MCU_STATE to { NwdEvent.McuStateChanged(it) },
        )

        @Suppress("DEPRECATION")
        internal fun installed(context: Context, packageName: String): Boolean =
            runCatching { context.packageManager.getPackageInfo(packageName, 0) }.isSuccess

        /**
         * An extra as a number, whatever type the sender used: the other clients only show which
         * getter they call (a byte here, an int there), not what the vendor puts in. Null when it is
         * missing, or when the extras cannot be read at all.
         */
        @Suppress("DEPRECATION")
        internal fun Intent.number(key: String): Int? = when (val value = runCatching { extras?.get(key) }.getOrNull()) {
            is Number -> value.toInt()
            is String -> value.trim().toIntOrNull()
            is Boolean -> if (value) 1 else 0
            else -> null
        }

        private fun Intent.text(key: String): String? = runCatching { getStringExtra(key) }.getOrNull()
    }
}
