package com.ohenrique.carradio.diag

import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * The last [capacity] lines the tuner traced, each with the time it was added. Kept in memory
 * only, for the diagnostics screen: it shows what the head unit did in real use.
 */
class EventLog(private val capacity: Int = 300) {

    private val lines = ArrayDeque<String>(capacity)

    @Synchronized
    fun add(message: String) {
        if (lines.size == capacity) lines.removeFirst()
        lines.addLast("${LocalTime.now().format(TIME)} $message")
    }

    @Synchronized
    fun lines(): List<String> = lines.toList()

    private companion object {
        val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
    }
}
