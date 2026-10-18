package com.ohenrique.carradio.tuner.nwd

import com.ohenrique.carradio.tuner.SeekDirection
import kotlin.math.abs

/** The two RadioFeature calls that move the dial by themselves. */
internal enum class SeekCall(val label: String) {
    SEARCH("search"),
    SEEK("seek"),
}

/**
 * Learns, for one connection to the service, which of its two calls stops on the next station
 * and which way each call's boolean goes.
 *
 * The open NWD clients disagree on both: on the units they ran on, one call swept to the next
 * station and the other moved one channel, but not always the same one, and one client sends
 * `true` for down. The starting guess is [SeekCall.SEARCH] with `true` for up. Every seek reports
 * what it saw to [record], which may ask for the other call once; the tuner never loops on it.
 */
internal class NwdSeekStrategy(private val maxSwitches: Int = 2) {

    enum class Advice { KEEP, RETRY_OTHER }

    /** The call a seek starts with. */
    var call = SeekCall.SEARCH
        private set

    private var callConfirmed = false
    private var switches = 0
    private val trueMeansUp = mutableMapOf(SeekCall.SEARCH to true, SeekCall.SEEK to true)
    private val directionConfirmed = mutableSetOf<SeekCall>()

    /** The boolean that makes [call] go in [direction]. */
    fun argument(call: SeekCall, direction: SeekDirection): Boolean =
        trueMeansUp.getValue(call) == (direction == SeekDirection.UP)

    /**
     * Takes in how a seek with [call] in [direction] went: whether the dial's first move went up
     * ([wentUp], null when it did not move), how many channels it ended up from where it started
     * (signed, a wrap past the band edge counted in [direction]) and whether the service said it
     * was busy.
     */
    fun record(call: SeekCall, direction: SeekDirection, wentUp: Boolean?, channels: Int, busySeen: Boolean): Advice {
        if (wentUp != null && call !in directionConfirmed) {
            if (wentUp != (direction == SeekDirection.UP)) trueMeansUp[call] = !trueMeansUp.getValue(call)
            directionConfirmed += call
        }
        if (call != this.call || callConfirmed) return Advice.KEEP
        // A sweep the service reported, or a jump of several channels: this call finds stations.
        // A sweep that came back empty also counts, the band simply has nothing to stop on.
        if (busySeen || abs(channels) > 1) {
            callConfirmed = true
            return Advice.KEEP
        }
        // One channel without a sweep looks like the fine step, and no move at all like a call
        // this service ignores. Either way the other call is worth a try, a few times at most.
        if (switches >= maxSwitches) return Advice.KEEP
        switches++
        this.call = if (call == SeekCall.SEARCH) SeekCall.SEEK else SeekCall.SEARCH
        return Advice.RETRY_OTHER
    }
}
