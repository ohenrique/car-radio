package com.ohenrique.carradio.tuner.qn8035

/**
 * Emulates the parts of a QN8035 the driver uses: tuning, CCA seek, signal registers and RDS,
 * following the datasheet's register descriptions.
 *
 * [stations] maps frequency in kHz to signal level in dBµV.
 */
class FakeQn8035(
    private val stations: Map<Int, Int> = emptyMap(),
    private val productId: Int = 0x21,
    /** STATUS1 reads that report the scan as still running after a CCA starts. */
    private val ccaBusyReads: Int = 3,
) : I2cBus {

    val registers = IntArray(0x19)
    val writes = mutableListOf<Pair<Int, Int>>()
    var tunedChannel = -1
        private set
    var closed = false
        private set

    private val pendingRds = ArrayDeque<IntArray>()
    private var busyReads = 0
    private var ccaFailed = false

    init {
        reset()
    }

    /** Queues one RDS group; [errors] uses the STATUS2[3:0] layout (block A in bit 3). */
    fun queueRdsGroup(a: Int, b: Int, c: Int, d: Int, errors: Int = 0) {
        pendingRds += intArrayOf(a, b, c, d, errors)
    }

    override fun readRegister(register: Int): Int = when (register) {
        0x02 -> 25
        0x03 -> rssiAt(tunedChannel) + 43
        0x04 -> status1()
        0x13 -> status2()
        else -> registers[register]
    }

    override fun writeRegister(register: Int, value: Int) {
        writes += register to value
        registers[register] = value
        if (register == 0x00) onSystem1(value)
    }

    override fun close() {
        closed = true
    }

    private fun reset() {
        registers.fill(0)
        registers[0x06] = productId shl 2
        tunedChannel = -1
    }

    private fun onSystem1(value: Int) {
        when {
            value and 0x80 != 0 -> reset()
            value and 0x10 == 0 -> Unit
            value and 0x02 != 0 -> runCca()
            value and 0x01 != 0 -> {
                tunedChannel = currentChannel()
                ccaFailed = false
            }
        }
    }

    private fun runCca() {
        val start = registers[0x08] or (((registers[0x0A] ushr 2) and 0x3) shl 8)
        val stop = registers[0x09] or (((registers[0x0A] ushr 4) and 0x3) shl 8)
        val step = when ((registers[0x0A] ushr 6) and 0x3) {
            0 -> 1
            1 -> 2
            else -> 4
        }
        val minimumDbuv = (registers[0x01] and 0x3F) - 10
        val direction = if (stop >= start) step else -step
        var channel = start
        var found: Int? = null
        while (if (direction > 0) channel <= stop else channel >= stop) {
            if (rssiAt(channel) > minimumDbuv && stations.containsKey(60_000 + channel * 50)) {
                found = channel
                break
            }
            channel += direction
        }
        val result = found ?: stop
        ccaFailed = found == null
        registers[0x07] = result and 0xFF
        registers[0x0A] = (registers[0x0A] and 0xFC) or ((result ushr 8) and 0x3)
        tunedChannel = result
        busyReads = ccaBusyReads
    }

    private fun status1(): Int {
        val fsm = if (busyReads > 0) {
            busyReads--
            0b111
        } else {
            0b101
        }
        var value = fsm shl 4
        if (ccaFailed) value = value or 0x08
        if (stations[60_000 + tunedChannel * 50] == null) value = value or 0x01
        return value
    }

    private fun status2(): Int {
        val group = pendingRds.removeFirstOrNull() ?: return registers[0x13]
        for (block in 0 until 4) {
            registers[0x0B + block * 2] = (group[block] ushr 8) and 0xFF
            registers[0x0B + block * 2 + 1] = group[block] and 0xFF
        }
        registers[0x13] = ((registers[0x13] and 0x80) xor 0x80) or (group[4] and 0xF) or 0x10
        return registers[0x13]
    }

    private fun currentChannel() = registers[0x07] or ((registers[0x0A] and 0x3) shl 8)

    private fun rssiAt(channel: Int) = stations[60_000 + channel * 50] ?: 5
}
