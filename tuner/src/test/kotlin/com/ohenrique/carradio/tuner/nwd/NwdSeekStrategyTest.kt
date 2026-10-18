package com.ohenrique.carradio.tuner.nwd

import com.ohenrique.carradio.tuner.SeekDirection.DOWN
import com.ohenrique.carradio.tuner.SeekDirection.UP
import com.ohenrique.carradio.tuner.nwd.NwdSeekStrategy.Advice.KEEP
import com.ohenrique.carradio.tuner.nwd.NwdSeekStrategy.Advice.RETRY_OTHER
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NwdSeekStrategyTest {

    @Test
    fun `starts with search and true for up`() {
        val strategy = NwdSeekStrategy()

        assertEquals(SeekCall.SEARCH, strategy.call)
        assertTrue(strategy.argument(SeekCall.SEARCH, UP))
        assertFalse(strategy.argument(SeekCall.SEARCH, DOWN))
        assertTrue(strategy.argument(SeekCall.SEEK, UP))
    }

    @Test
    fun `a sweep or a jump confirms the call`() {
        val swept = NwdSeekStrategy()
        assertEquals(KEEP, swept.record(SeekCall.SEARCH, UP, wentUp = true, channels = 1, busySeen = true))
        // Once confirmed, even a single channel without busy keeps it.
        assertEquals(KEEP, swept.record(SeekCall.SEARCH, UP, wentUp = true, channels = 1, busySeen = false))
        assertEquals(SeekCall.SEARCH, swept.call)

        val jumped = NwdSeekStrategy()
        assertEquals(KEEP, jumped.record(SeekCall.SEARCH, UP, wentUp = true, channels = 12, busySeen = false))
        assertEquals(KEEP, jumped.record(SeekCall.SEARCH, UP, wentUp = null, channels = 0, busySeen = false))
        assertEquals(SeekCall.SEARCH, jumped.call)
    }

    @Test
    fun `an empty sweep confirms the call too`() {
        val strategy = NwdSeekStrategy()

        assertEquals(KEEP, strategy.record(SeekCall.SEARCH, UP, wentUp = true, channels = 0, busySeen = true))
        assertEquals(KEEP, strategy.record(SeekCall.SEARCH, UP, wentUp = null, channels = 0, busySeen = false))
        assertEquals(SeekCall.SEARCH, strategy.call)
    }

    @Test
    fun `one channel or nothing asks for the other call`() {
        val step = NwdSeekStrategy()
        assertEquals(RETRY_OTHER, step.record(SeekCall.SEARCH, UP, wentUp = true, channels = 1, busySeen = false))
        assertEquals(SeekCall.SEEK, step.call)

        val nothing = NwdSeekStrategy()
        assertEquals(RETRY_OTHER, nothing.record(SeekCall.SEARCH, DOWN, wentUp = null, channels = 0, busySeen = false))
        assertEquals(SeekCall.SEEK, nothing.call)
        assertEquals(KEEP, nothing.record(SeekCall.SEEK, DOWN, wentUp = false, channels = -7, busySeen = true))
        assertEquals(SeekCall.SEEK, nothing.call)
    }

    @Test
    fun `switches at most twice`() {
        val strategy = NwdSeekStrategy()

        assertEquals(RETRY_OTHER, strategy.record(SeekCall.SEARCH, UP, wentUp = true, channels = 1, busySeen = false))
        assertEquals(RETRY_OTHER, strategy.record(SeekCall.SEEK, UP, wentUp = true, channels = 1, busySeen = false))
        assertEquals(KEEP, strategy.record(SeekCall.SEARCH, UP, wentUp = true, channels = 1, busySeen = false))
        assertEquals(KEEP, strategy.record(SeekCall.SEARCH, UP, wentUp = null, channels = 0, busySeen = false))
        assertEquals(SeekCall.SEARCH, strategy.call)
    }

    @Test
    fun `learns an inverted boolean from the first move`() {
        val strategy = NwdSeekStrategy()

        strategy.record(SeekCall.SEARCH, UP, wentUp = false, channels = -9, busySeen = true)

        assertFalse(strategy.argument(SeekCall.SEARCH, UP))
        assertTrue(strategy.argument(SeekCall.SEARCH, DOWN))
        // The other call keeps its own guess.
        assertTrue(strategy.argument(SeekCall.SEEK, UP))
    }

    @Test
    fun `a confirmed direction is never flipped`() {
        val strategy = NwdSeekStrategy()
        strategy.record(SeekCall.SEARCH, UP, wentUp = true, channels = 5, busySeen = true)

        strategy.record(SeekCall.SEARCH, UP, wentUp = false, channels = -5, busySeen = true)
        strategy.record(SeekCall.SEARCH, DOWN, wentUp = true, channels = 5, busySeen = true)

        assertTrue(strategy.argument(SeekCall.SEARCH, UP))
    }

    @Test
    fun `no move teaches nothing about the direction`() {
        val strategy = NwdSeekStrategy()

        strategy.record(SeekCall.SEARCH, UP, wentUp = null, channels = 0, busySeen = true)
        strategy.record(SeekCall.SEARCH, UP, wentUp = false, channels = -3, busySeen = true)

        assertFalse(strategy.argument(SeekCall.SEARCH, UP))
    }

    @Test
    fun `a report about a call no longer in use changes only its direction`() {
        val strategy = NwdSeekStrategy()
        strategy.record(SeekCall.SEARCH, UP, wentUp = true, channels = 1, busySeen = false)

        assertEquals(KEEP, strategy.record(SeekCall.SEARCH, UP, wentUp = true, channels = 1, busySeen = false))
        assertEquals(SeekCall.SEEK, strategy.call)
    }
}
