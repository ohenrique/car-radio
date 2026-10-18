package com.ohenrique.carradio.nwd

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.DeadObjectException
import android.os.IBinder
import android.os.Parcel
import android.os.RemoteException
import com.ohenrique.carradio.tuner.nwd.Nwd
import com.ohenrique.carradio.tuner.nwd.NwdEvent
import com.ohenrique.carradio.tuner.nwd.NwdException
import com.ohenrique.carradio.tuner.nwd.NwdFrequency
import com.ohenrique.carradio.tuner.nwd.NwdRadioPoint
import com.ohenrique.carradio.tuner.nwd.NwdService
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Talks to `com.nwd.radio.service` with raw Binder transactions, laid out as its RadioFeature and
 * RadioCallback AIDL interfaces expect, so the app needs neither the vendor's classes nor any
 * permission. The MCU's source, mute and sleep state, and the broadcasts that switch the source,
 * go through [NwdSystemLink].
 *
 * The AIDL declares no RadioFeature call one-way, so every call waits for the service's reply and
 * must be made off the main thread.
 */
class NwdServiceClient private constructor(
    context: Context,
    private val connection: Connection,
    private val radio: IBinder,
) : NwdService {

    // Read from Binder threads.
    @Volatile override var listener: ((NwdEvent) -> Unit)? = null
    @Volatile override var onDisconnected: (() -> Unit)? = null

    /** Every RadioCallback notice as it came, code and decoded call, for the diagnostics screen. */
    @Volatile var onNotice: ((Int, String) -> Unit)? = null

    private val system = NwdSystemLink(context)
    private val callback = Callback()
    private val death = IBinder.DeathRecipient { disconnected() }
    private val deathReported = AtomicBoolean(false)
    @Volatile private var closed = false

    init {
        // A binder that is already dead fails its first call, which reports the death then.
        runCatching { radio.linkToDeath(death, 0) }
        connection.client = this
        system.listener = { event -> if (!closed) listener?.invoke(event) }
        system.start()
    }

    // RadioFeature.

    override fun getRadioType(): Int = call(Nwd.Feature.GET_RADIO_TYPE) { readInt() }

    override fun getRadioPoint(): List<NwdRadioPoint?>? = call(Nwd.Feature.GET_RADIO_POINT) { readRadioPoints() }

    override fun getCurrentFrequency(): NwdFrequency? =
        call(Nwd.Feature.GET_CURRENT_FREQUENCY) { if (readInt() == 0) null else readFrequency() }

    override fun getRadioState(): Int = call(Nwd.Feature.GET_RADIO_STATE) { readByte().toInt() }

    override fun setCurrentFrequency(frequency: Int, band: Int, presetIndex: Int) =
        command(Nwd.Feature.SET_CURRENT_FREQUENCY, TUNE_FLAGS) {
            writeInt(frequency)
            writeByte(band.toByte())
            writeInt(presetIndex)
        }

    override fun seek(up: Boolean) = command(Nwd.Feature.SEEK) { writeBool(up) }

    override fun search(up: Boolean) = command(Nwd.Feature.SEARCH) { writeBool(up) }

    override fun changeBand() = command(Nwd.Feature.CHANGE_BAND)

    override fun sendRadioCommand(action: Int, value: Int) = command(Nwd.Feature.SEND_RADIO_COMMAND) {
        writeByte(action.toByte())
        writeByte(value.toByte())
    }

    override fun setNearOn(on: Boolean) = command(Nwd.Feature.SET_NEAR_ON) { writeBool(on) }

    override fun isNearOn(): Boolean = call(Nwd.Feature.IS_NEAR_ON) { readBool() }

    override fun hasStereoPilot(): Boolean = call(Nwd.Feature.IS_HAS_STRERO) { readBool() }

    override fun setStereoOn(on: Boolean) = command(Nwd.Feature.SET_STRERO_ON) { writeBool(on) }

    override fun isStereoOn(): Boolean = call(Nwd.Feature.IS_STRERO_ON) { readBool() }

    override fun getRtMessage(): String? = call(Nwd.Feature.GET_RT_MESSAGE) { readString() }

    override fun registerCallback() = command(Nwd.Feature.REGIST_CALLBACK) { writeStrongBinder(callback) }

    override fun unregisterCallback() = command(Nwd.Feature.UNREGIST_CALLBACK) { writeStrongBinder(callback) }

    // Read by the diagnostics screen only.

    /** The interface the service's binder says it implements. */
    fun interfaceDescriptor(): String? = try {
        radio.interfaceDescriptor
    } catch (e: RemoteException) {
        throw NwdException(NO_ANSWER, e)
    }

    fun isRadioBackServiceOn(): Boolean = call(Nwd.Feature.IS_RADIO_BACK_SERVICE_ON) { readBool() }

    fun getRdsState(flag: Int): Boolean = call(Nwd.Feature.GET_RDS_STATE, write = { writeInt(flag) }) { readBool() }

    fun getPtyType(): Int = call(Nwd.Feature.GET_PTY_TYPE) { readByte().toInt() }

    /** The service's own presets. */
    fun getPrefabFrequency(): List<NwdFrequency?>? = call(Nwd.Feature.GET_PREFAB_FREQUENCY) { readTypedList { readFrequency() } }

    fun getCurrentScanState(): Int = call(Nwd.Feature.GET_CURRENT_SCAN_STATE) { readInt() }

    // The MCU, through Settings.System and broadcasts.

    override fun currentSource(): Int? = whileOpen { system.readInt(Nwd.Setting.CURRENT_SOURCE) }

    override fun isUnitMuted(): Boolean? = whileOpen { system.readInt(Nwd.Setting.MUTE_STATE)?.let { it != 0 } }

    override fun mcuState(): Int? = whileOpen { system.readInt(Nwd.Setting.MCU_STATE) }

    override fun isPhoneCallActive(): Boolean = whileOpen { system.isPhoneCallActive() }

    override fun requestSource(sourceId: Int) = whileOpen { system.requestSource(sourceId) }

    override fun sendAppInOut(appId: Int, operation: Int, event: Int) =
        whileOpen { system.sendAppInOut(appId, operation, event) }

    override fun sendMediaPlay(appId: Int) = whileOpen { system.sendMediaPlay(appId) }

    override fun setUnitMuted(muted: Boolean) = whileOpen { system.setUnitMuted(muted) }

    override fun close() {
        if (closed) return
        closed = true
        system.close()
        runCatching { radio.unlinkToDeath(death, 0) }
        connection.unbind()
    }

    /** Tells the tuner once, however many of Android's death notices arrive. */
    private fun disconnected() {
        if (closed) return
        val handler = onDisconnected ?: return
        if (deathReported.compareAndSet(false, true)) runCatching { handler() }
    }

    private inline fun <T> whileOpen(block: () -> T): T {
        if (closed) throw NwdException(CLOSED)
        return try {
            block()
        } catch (e: RuntimeException) {
            throw NwdException("O Android recusou o pedido à central: ${e.message ?: e}", e)
        }
    }

    private inline fun command(code: Int, flags: Int = 0, write: Parcel.() -> Unit = {}) = call(code, flags, write) {}

    /** Runs one RadioFeature transaction; [read] gets the reply after its exception header. */
    private inline fun <T> call(code: Int, flags: Int = 0, write: Parcel.() -> Unit = {}, read: Parcel.() -> T): T {
        if (closed) throw NwdException(CLOSED)
        val oneway = flags and IBinder.FLAG_ONEWAY != 0
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(Nwd.Feature.DESCRIPTOR)
            data.write()
            // False means the service has no such call: its reply would be empty, not zeros.
            if (!radio.transact(code, data, if (oneway) null else reply, flags)) {
                throw NwdException("$REFUSED: código $code desconhecido")
            }
            if (!oneway) reply.readException()
            return reply.read()
        } catch (e: DeadObjectException) {
            disconnected()
            throw NwdException(NO_ANSWER, e)
        } catch (e: RemoteException) {
            throw NwdException(NO_ANSWER, e)
        } catch (e: RuntimeException) {
            throw NwdException("$REFUSED: ${e.message ?: e}", e)
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    /**
     * RadioCallback. The service waits for the reply to each notice, so every one is acknowledged
     * at once, and nothing in here calls the service back.
     */
    private inner class Callback : Binder() {
        init {
            attachInterface(null, Nwd.Callback.DESCRIPTOR)
        }

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == IBinder.INTERFACE_TRANSACTION) {
                reply?.writeString(Nwd.Callback.DESCRIPTOR)
                return true
            }
            if (code !in IBinder.FIRST_CALL_TRANSACTION..Nwd.Callback.LAST) return super.onTransact(code, data, reply, flags)
            data.enforceInterface(Nwd.Callback.DESCRIPTOR)
            val notice = runCatching { decode(code, data) }.getOrNull()
            reply?.writeNoException()
            if (notice != null && !closed) {
                onNotice?.let { runCatching { it(code, notice.call) } }
                notice.event?.let { event -> runCatching { listener?.invoke(event) } }
            }
            return true
        }
    }

    private class Notice(val call: String, val event: NwdEvent? = null)

    private fun decode(code: Int, data: Parcel): Notice = when (code) {
        Nwd.Callback.NOTIFY_STATE -> data.readByte().toInt().let { Notice("notifyState($it)", NwdEvent.StateChanged(it)) }
        Nwd.Callback.NOTIFY_CURRENT_FREQUENCY -> {
            // Not the order of the Frequency parcelable. The preset index is the service's own.
            val band = data.readByte().toInt()
            val frequency = data.readInt()
            val name = data.readString()
            val preset = data.readInt()
            Notice(
                "notifyCurrentFrequency($band, $frequency, ${quoted(name)}, $preset)",
                NwdEvent.FrequencyChanged(NwdFrequency(band, name, frequency)),
            )
        }
        Nwd.Callback.NOTIFY_NEAR_ON -> data.readBool().let { Notice("notifyNearOn($it)", NwdEvent.NearOnChanged(it)) }
        Nwd.Callback.NOTIFY_STEREO -> data.readBool().let { Notice("notifyStereo($it)", NwdEvent.StereoPilotChanged(it)) }
        // The stereo setting, not the pilot.
        Nwd.Callback.NOTIFY_STEREO_ON -> Notice("notifyStereoOn(${data.readBool()})")
        Nwd.Callback.NOTIFY_RDS_STATE_CHANGE -> Notice("notifyRDSStateChange()")
        Nwd.Callback.NOTIFY_CURRENT_PTY_TYPE -> Notice("notifyCurrentPTYType(${data.readByte()})")
        // The service's presets are not used; they are only read for the diagnostics screen.
        Nwd.Callback.NOTIFY_PREFAB_FREQUENCY -> Notice(
            if (onNotice == null) "notifyPrefabFrequency(…)" else "notifyPrefabFrequency(${describe(data.readTypedList { readFrequency() })})",
        )
        Nwd.Callback.NOTIFY_PREFAB_PTY_TYPE -> Notice("notifyPrefabPTYType(${data.readByte()})")
        Nwd.Callback.NOTIFY_RADIO_POINT -> data.readRadioPoints().let { points ->
            Notice("notifyRadioPoint(${describe(points)})", points?.let { NwdEvent.RadioPointChanged(it) })
        }
        Nwd.Callback.NOTIFY_CURRENT_IS_TA -> Notice("notifyCurrentIsTA(${data.readBool()})")
        Nwd.Callback.NOTIFY_RDS_SHOW_STATE -> Notice("notifyRdsShowState(${data.readBool()})")
        Nwd.Callback.NOTIFY_RT_MESSAGE -> data.readString().let { Notice("notifyRtMessage(${quoted(it)})", NwdEvent.RadioTextChanged(it)) }
        Nwd.Callback.NOTIFY_RADIO_SCAN_STATE -> data.readInt().let { Notice("notifyRadioScanState($it)", NwdEvent.ScanStateChanged(it)) }
        else -> Notice("código $code")
    }

    /** The binding, which outlives the wait for it: it tells the client when the service goes away. */
    private class Connection(private val app: Context) : ServiceConnection {
        @Volatile var client: NwdServiceClient? = null
        @Volatile private var waiting: CancellableContinuation<IBinder>? = null
        private val unbound = AtomicBoolean(false)

        suspend fun bind(intents: List<Intent>, warmUp: Boolean): IBinder = suspendCancellableCoroutine { cont ->
            waiting = cont
            var denied: SecurityException? = null
            val bound = intents.any { intent ->
                // The service can be cold after a reboot, so the stock clients start it first.
                // Android refuses that from the background, which only costs the warm-up.
                if (warmUp) runCatching { app.startService(intent) }
                try {
                    app.bindService(intent, this, Context.BIND_AUTO_CREATE).also { ok ->
                        // A failed bind still has to be unbound before trying again.
                        if (!ok) runCatching { app.unbindService(this) }
                    }
                } catch (e: SecurityException) {
                    denied = e
                    false
                }
            }
            if (!bound) {
                waiting = null
                unbound.set(true)
                cont.resumeWithException(denied?.let { NwdException(DENIED, it) } ?: NwdException(NOT_FOUND))
                return@suspendCancellableCoroutine
            }
            cont.invokeOnCancellation { if (client == null) unbind() }
        }

        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            val cont = waiting
            waiting = null
            if (cont != null && cont.isActive) {
                cont.resume(service)
            } else {
                // Connected again after the service restarted. The client was told it died, and
                // the app binds afresh with a new one.
                client?.disconnected()
                unbind()
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            client?.disconnected()
        }

        override fun onBindingDied(name: ComponentName) {
            fail(NwdException(NO_ANSWER))
            client?.disconnected()
        }

        override fun onNullBinding(name: ComponentName) {
            fail(NwdException(NO_BINDER))
        }

        fun unbind() {
            if (unbound.compareAndSet(false, true)) runCatching { app.unbindService(this) }
        }

        private fun fail(error: NwdException) {
            val cont = waiting
            waiting = null
            unbind()
            if (cont != null && cont.isActive) cont.resumeWithException(error)
        }
    }

    companion object {
        private const val NOT_FOUND =
            "Não encontrei o serviço de rádio com.nwd.radio.service. Essa central é NWD? Se não for, troque o sintonizador nos ajustes."
        private const val DENIED = "O Android não deixou o app se ligar ao com.nwd.radio.service"
        private const val NO_BINDER = "O com.nwd.radio.service não entregou o serviço de rádio"
        private const val NO_ANSWER = "O com.nwd.radio.service não respondeu"
        private const val CLOSED = "A conexão com o com.nwd.radio.service foi fechada"
        private const val REFUSED = "O com.nwd.radio.service recusou o comando"

        /**
         * A tune waits for the reply like every other call, so a slow MCU holds the next tune back
         * instead of queueing it. [IBinder.FLAG_ONEWAY] here would return at once, as Radio+ sends
         * it on MCU units.
         */
        private const val TUNE_FLAGS = 0

        /**
         * Binds `com.nwd.radio.service` and returns once it is connected and turned out to be the
         * RadioFeature service. [warmUp] starts the service first, as the stock clients do; the
         * diagnostics screen leaves it out so it changes nothing.
         */
        suspend fun connect(context: Context, warmUp: Boolean = true): NwdServiceClient {
            val app = context.applicationContext
            val connection = Connection(app)
            try {
                val binder = connection.bind(serviceIntents(app.packageManager), warmUp)
                val descriptor = descriptorOf(binder)
                if (descriptor != Nwd.Feature.DESCRIPTOR) {
                    throw NwdException("O com.nwd.radio.service não é o rádio esperado (descritor $descriptor)")
                }
                return NwdServiceClient(app, connection, binder)
            } catch (e: Throwable) {
                connection.unbind()
                throw e
            }
        }

        /** The services that answer the radio service's action, the one in com.nwd.radio.service first. */
        fun radioServices(pm: PackageManager): List<ServiceInfo> {
            @Suppress("DEPRECATION")
            val found = runCatching { pm.queryIntentServices(Intent(Nwd.RADIO_SERVICE_ACTION), 0) }.getOrNull().orEmpty()
            return found.mapNotNull { it.serviceInfo }.sortedByDescending { it.packageName == Nwd.RADIO_SERVICE_PACKAGE }
        }

        /** Whether this head unit has the NWD radio service, found by its action or by its class. */
        fun isInstalled(context: Context): Boolean {
            val pm = context.packageManager
            if (radioServices(pm).isNotEmpty()) return true
            @Suppress("DEPRECATION")
            val info = runCatching { pm.getPackageInfo(Nwd.RADIO_SERVICE_PACKAGE, PackageManager.GET_SERVICES) }.getOrNull()
            return info?.services?.any { it.name == Nwd.RADIO_SERVICE_CLASS } == true
        }

        /**
         * The action within the component that resolves it, then the service class by name. The
         * action stays in both, in case the service hands out its binder by action.
         */
        private fun serviceIntents(pm: PackageManager): List<Intent> =
            (radioServices(pm).map { ComponentName(it.packageName, it.name) } + ComponentName(Nwd.RADIO_SERVICE_PACKAGE, Nwd.RADIO_SERVICE_CLASS))
                .distinct()
                .map { Intent(Nwd.RADIO_SERVICE_ACTION).setComponent(it) }

        /**
         * Asks the binder which interface it implements. A hung service never answers and a Binder
         * call cannot be interrupted, so the call runs on its own and a cancelled connect does not
         * wait for it.
         */
        private suspend fun descriptorOf(binder: IBinder): String? = try {
            CoroutineScope(Dispatchers.IO).async { binder.interfaceDescriptor }.await()
        } catch (e: RemoteException) {
            throw NwdException(NO_ANSWER, e)
        }
    }
}

/** [text] in quotes with control characters escaped, or `null`: exactly what the service sent. */
internal fun quoted(text: String?): String {
    text ?: return "null"
    return buildString {
        append('"')
        text.forEach { c ->
            when {
                c == '"' || c == '\\' -> append('\\').append(c)
                c.isISOControl() || c == '�' -> append("\\u%04x".format(c.code))
                else -> append(c)
            }
        }
        append('"')
    }
}

private fun describe(list: List<Any?>?): String = list?.joinToString(", ", "[", "]") ?: "null"

private fun Parcel.writeBool(value: Boolean) = writeInt(if (value) 1 else 0)

private fun Parcel.readBool(): Boolean = readInt() != 0

/** The vendor's Frequency parcelable: byte band, String psName, int frequency. */
private fun Parcel.readFrequency(): NwdFrequency {
    val band = readByte().toInt()
    val name = readString()
    return NwdFrequency(band, name, readInt())
}

/** RadioPoint[]: int min, int max, int step each. */
private fun Parcel.readRadioPoints(): List<NwdRadioPoint?>? = readTypedList { NwdRadioPoint(readInt(), readInt(), readInt()) }

/** An AIDL typed array: its length (-1 for null), then each element behind an int non-null flag. */
private inline fun <T> Parcel.readTypedList(read: Parcel.() -> T): List<T?>? {
    val count = readInt()
    if (count < 0) return null
    // Every element takes at least its flag's 4 bytes, so a longer count is not an array.
    if (count > dataAvail() / 4) throw NwdException("O com.nwd.radio.service mandou uma lista inválida ($count itens)")
    return List(count) { if (readInt() == 0) null else read() }
}
