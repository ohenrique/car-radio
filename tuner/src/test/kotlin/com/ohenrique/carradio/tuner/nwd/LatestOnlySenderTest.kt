package com.ohenrique.carradio.tuner.nwd

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LatestOnlySenderTest {

    @Test
    fun `only the newest value is sent after a slow send`() = runTest {
        val sent = mutableListOf<Int>()
        val sender = LatestOnlySender<Int>(backgroundScope, StandardTestDispatcher(testScheduler)) {
            delay(500)
            sent += it
        }

        val first = sender.offer(1)
        runCurrent()
        val second = sender.offer(2)
        val third = sender.offer(3)
        assertFalse(sender.idle)
        advanceTimeBy(2_000)

        assertEquals(listOf(1, 3), sent)
        assertTrue(first.await())
        assertFalse(second.await())
        assertTrue(third.await())
        assertTrue(sender.idle)
    }

    @Test
    fun `values are sent in order when nothing overlaps`() = runTest {
        val sent = mutableListOf<Int>()
        val sender = LatestOnlySender<Int>(backgroundScope, StandardTestDispatcher(testScheduler)) { sent += it }

        for (value in 1..3) {
            assertTrue(sender.offer(value).await())
        }

        assertEquals(listOf(1, 2, 3), sent)
    }

    @Test
    fun `a failed send reports its exception and the next one still goes`() = runTest {
        val sent = mutableListOf<Int>()
        val sender = LatestOnlySender<Int>(backgroundScope, StandardTestDispatcher(testScheduler)) {
            if (it == 1) throw NwdException("recusou")
            sent += it
        }

        val failed = runCatching { sender.offer(1).await() }
        val next = sender.offer(2).await()

        assertEquals("recusou", failed.exceptionOrNull()?.message)
        assertTrue(next)
        assertEquals(listOf(2), sent)
    }
}
