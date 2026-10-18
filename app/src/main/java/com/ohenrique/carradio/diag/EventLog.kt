package com.ohenrique.carradio.diag

import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * What the tuner traced, each line with the time it was added. Kept in memory only, for the
 * diagnostics screen: it shows what the head unit did in real use. The first [headCapacity] lines
 * stay for good, since they hold the power on (the radio type, the band, which claim switched the
 * source and how fast), and so do the last [capacity].
 */
class EventLog(private val capacity: Int = 300, private val headCapacity: Int = 80) {

    private val head = ArrayList<String>(headCapacity)
    private val tail = ArrayDeque<String>(capacity)
    private var dropped = 0

    @Synchronized
    fun add(message: String) {
        val line = "${LocalTime.now().format(TIME)} $message"
        if (head.size < headCapacity) {
            head += line
            return
        }
        if (tail.size == capacity) {
            tail.removeFirst()
            dropped++
        }
        tail.addLast(line)
    }

    @Synchronized
    fun lines(): List<String> = buildList {
        addAll(head)
        if (dropped > 0) add("… $dropped linhas omitidas …")
        addAll(tail)
    }

    private companion object {
        val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
    }
}
