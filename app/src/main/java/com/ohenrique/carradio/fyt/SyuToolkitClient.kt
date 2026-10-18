package com.ohenrique.carradio.fyt

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.RemoteException
import com.ohenrique.carradio.tuner.fyt.Syu
import com.ohenrique.carradio.tuner.fyt.SyuException
import com.ohenrique.carradio.tuner.fyt.SyuModule
import com.ohenrique.carradio.tuner.fyt.SyuToolkit
import com.ohenrique.carradio.tuner.fyt.SyuUpdate
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Talks to `com.syu.ms` with raw Binder transactions, laid out as its AIDL interfaces
 * (com.syu.ipc.IRemoteToolkit, IRemoteModule, IModuleCallback) expect, so the app needs neither
 * the vendor's classes nor any permission.
 */
class SyuToolkitClient private constructor(
    private val context: Context,
    private val connection: ServiceConnection,
    private val toolkit: IBinder,
) : SyuToolkit {

    override var listener: ((SyuUpdate) -> Unit)? = null
    override var onDisconnected: (() -> Unit)? = null

    private val callbacks = mutableMapOf<Int, Callback>()
    @Volatile private var closed = false

    private val death = IBinder.DeathRecipient { disconnected() }

    init {
        runCatching { toolkit.linkToDeath(death, 0) }
    }

    override fun module(code: Int): SyuModule? {
        val binder = call(toolkit, TOOLKIT, TXN_GET_REMOTE_MODULE, write = { writeInt(code) }, read = { readStrongBinder() })
            ?: return null
        return RemoteModule(code, binder)
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { toolkit.unlinkToDeath(death, 0) }
        runCatching { context.unbindService(connection) }
    }

    private fun disconnected() {
        if (closed) return
        onDisconnected?.invoke()
    }

    private fun callbackFor(module: Int): Callback = synchronized(callbacks) {
        callbacks.getOrPut(module) { Callback(module) }
    }

    private inner class RemoteModule(override val code: Int, private val binder: IBinder) : SyuModule {
        override fun cmd(command: Int, vararg ints: Int) {
            send(binder, MODULE, TXN_CMD) {
                writeInt(command)
                writeIntArray(ints)
                writeFloatArray(null)
                writeStringArray(null)
            }
        }

        override fun register(updateCode: Int) {
            send(binder, MODULE, TXN_REGISTER) {
                writeStrongBinder(callbackFor(code))
                writeInt(updateCode)
                // 1: send the current value right away, then every change.
                writeInt(1)
            }
        }

        override fun unregister(updateCode: Int) {
            send(binder, MODULE, TXN_UNREGISTER) {
                writeStrongBinder(callbackFor(code))
                writeInt(updateCode)
            }
        }
    }

    /** IModuleCallback, one per module, since an update only carries its own code. */
    private inner class Callback(private val module: Int) : Binder() {
        init {
            attachInterface(null, CALLBACK)
        }

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean = when (code) {
            IBinder.INTERFACE_TRANSACTION -> {
                reply?.writeString(CALLBACK)
                true
            }
            TXN_UPDATE -> {
                data.enforceInterface(CALLBACK)
                val updateCode = data.readInt()
                val ints = data.createIntArray()
                data.createFloatArray()
                val strings = data.createStringArray()
                if (!closed) runCatching { listener?.invoke(SyuUpdate(module, updateCode, ints, strings)) }
                true
            }
            else -> super.onTransact(code, data, reply, flags)
        }
    }

    /** A one-way call, like the AIDL `oneway` methods of IRemoteModule. */
    private inline fun send(binder: IBinder, descriptor: String, code: Int, write: Parcel.() -> Unit) {
        call<Unit>(binder, descriptor, code, write, read = null)
    }

    /** Runs one transaction; for a two-way call, [read] gets the reply after its exception header. */
    private inline fun <T> call(
        binder: IBinder,
        descriptor: String,
        code: Int,
        write: Parcel.() -> Unit,
        noinline read: (Parcel.() -> T)?,
    ): T? {
        if (closed) throw SyuException("A conexão com o com.syu.ms foi fechada")
        val data = Parcel.obtain()
        val reply = if (read == null) null else Parcel.obtain()
        try {
            data.writeInterfaceToken(descriptor)
            data.write()
            binder.transact(code, data, reply, if (reply == null) IBinder.FLAG_ONEWAY else 0)
            if (reply == null || read == null) return null
            reply.readException()
            return reply.read()
        } catch (e: RemoteException) {
            throw SyuException("O com.syu.ms não respondeu", e)
        } catch (e: RuntimeException) {
            throw SyuException("O com.syu.ms recusou o comando: ${e.message}", e)
        } finally {
            data.recycle()
            reply?.recycle()
        }
    }

    companion object {
        private const val TOOLKIT = "com.syu.ipc.IRemoteToolkit"
        private const val MODULE = "com.syu.ipc.IRemoteModule"
        private const val CALLBACK = "com.syu.ipc.IModuleCallback"

        private const val TXN_GET_REMOTE_MODULE = IBinder.FIRST_CALL_TRANSACTION
        private const val TXN_CMD = IBinder.FIRST_CALL_TRANSACTION
        private const val TXN_REGISTER = IBinder.FIRST_CALL_TRANSACTION + 2
        private const val TXN_UNREGISTER = IBinder.FIRST_CALL_TRANSACTION + 3
        private const val TXN_UPDATE = IBinder.FIRST_CALL_TRANSACTION

        /** Binds `com.syu.ms` and returns once the service is connected. */
        suspend fun connect(context: Context): SyuToolkit {
            val app = context.applicationContext
            return suspendCancellableCoroutine { cont ->
                var client: SyuToolkitClient? = null
                val connection = object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName, service: IBinder) {
                        if (cont.isActive) {
                            val connected = SyuToolkitClient(app, this, service)
                            client = connected
                            cont.resume(connected)
                        } else {
                            runCatching { app.unbindService(this) }
                        }
                    }

                    override fun onServiceDisconnected(name: ComponentName) {
                        client?.disconnected()
                    }

                    override fun onBindingDied(name: ComponentName) {
                        client?.disconnected()
                    }

                    override fun onNullBinding(name: ComponentName) {
                        runCatching { app.unbindService(this) }
                        if (cont.isActive) cont.resumeWithException(SyuException("O com.syu.ms não entregou o serviço de rádio"))
                    }
                }
                // The stock FYT clients use both forms: the action within the package, and the
                // service class by name.
                val intents = listOf(
                    Intent(Syu.TOOLKIT_ACTION).setPackage(Syu.PACKAGE),
                    Intent(Syu.TOOLKIT_ACTION).setComponent(ComponentName(Syu.PACKAGE, Syu.TOOLKIT_SERVICE)),
                )
                var denied: SecurityException? = null
                val bound = intents.any { intent ->
                    try {
                        app.bindService(intent, connection, Context.BIND_AUTO_CREATE).also { ok ->
                            // A failed bind still has to be unbound before trying again.
                            if (!ok) runCatching { app.unbindService(connection) }
                        }
                    } catch (e: SecurityException) {
                        denied = e
                        false
                    }
                }
                if (!bound) {
                    denied?.let {
                        cont.resumeWithException(SyuException("O Android não deixou o app se ligar ao com.syu.ms", it))
                        return@suspendCancellableCoroutine
                    }
                    cont.resumeWithException(
                        SyuException("Não encontrei o serviço com.syu.ms. Essa central é FYT? Se não for, troque o sintonizador nos ajustes."),
                    )
                    return@suspendCancellableCoroutine
                }
                cont.invokeOnCancellation {
                    if (client == null) runCatching { app.unbindService(connection) }
                }
            }
        }
    }
}
