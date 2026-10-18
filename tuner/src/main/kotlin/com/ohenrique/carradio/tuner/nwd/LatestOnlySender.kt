package com.ohenrique.carradio.tuner.nwd

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.launch

/**
 * Sends values one at a time on [dispatcher], always the newest one: a value offered while
 * another is being sent waits, and replaces any value still waiting. A send is never interrupted.
 *
 * Holding a step arrow tunes every 110 ms, faster than the vendor may answer. This keeps those
 * tunes from piling up, so the dial stops where the finger did.
 */
internal class LatestOnlySender<T>(
    scope: CoroutineScope,
    dispatcher: CoroutineDispatcher,
    private val send: suspend (T) -> Unit,
) {
    private class Item<T>(val value: T, val done: CompletableDeferred<Boolean>)

    private val lock = Any()
    private var waiting: Item<T>? = null
    private var sending = false
    private val wake = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch(dispatcher) { wake.consumeEach { drain() } }
    }

    /** True when nothing is being sent or waiting to be. */
    val idle: Boolean get() = synchronized(lock) { waiting == null && !sending }

    /**
     * Queues [value]. The result completes with true once it was sent, with false if a newer value
     * replaced it first, and with the exception if sending it failed.
     */
    fun offer(value: T): Deferred<Boolean> {
        val item = Item(value, CompletableDeferred())
        synchronized(lock) {
            waiting?.done?.complete(false)
            waiting = item
        }
        wake.trySend(Unit)
        return item.done
    }

    private suspend fun drain() {
        while (true) {
            val item = synchronized(lock) {
                waiting.also {
                    waiting = null
                    sending = it != null
                }
            } ?: return
            try {
                send(item.value)
                item.done.complete(true)
            } catch (e: CancellationException) {
                item.done.cancel(e)
                throw e
            } catch (e: Exception) {
                item.done.completeExceptionally(e)
            } finally {
                synchronized(lock) { sending = false }
            }
        }
    }
}
