package com.ohenrique.carradio.tuner.qn8035

import java.io.BufferedReader
import java.io.Closeable
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter

/** Register access to one I2C device. Implementations are not thread safe. */
interface I2cBus : Closeable {
    fun readRegister(register: Int): Int

    fun writeRegister(register: Int, value: Int)

    fun readRegisters(firstRegister: Int, count: Int): IntArray =
        IntArray(count) { readRegister(firstRegister + it) }

    override fun close() {}
}

class I2cException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * One long-lived root shell. Starting a `su` process per register access would be far too slow
 * for seek and RDS, so commands are written to the same shell and their output is read back up to
 * a marker line.
 */
class RootShell(command: List<String> = listOf("su")) : Closeable {

    private val process: Process = try {
        ProcessBuilder(command).redirectErrorStream(true).start()
    } catch (e: IOException) {
        throw I2cException("Não foi possível abrir um shell root (${command.joinToString(" ")}). A central tem root?", e)
    }
    private val input = BufferedReader(InputStreamReader(process.inputStream))
    private val output = OutputStreamWriter(process.outputStream)
    private var sequence = 0

    /** Runs [command] and returns its output; throws when it exits with a non-zero status. */
    fun run(command: String): String {
        val marker = "__carradio_${sequence++}__"
        output.write("$command; echo \"$marker\$?\"\n")
        output.flush()
        val lines = StringBuilder()
        while (true) {
            val line = input.readLine() ?: throw I2cException("O shell root foi encerrado (acesso root negado?)")
            if (line.startsWith(marker)) {
                val status = line.removePrefix(marker).trim()
                if (status != "0") throw I2cException("'$command' falhou ($status): ${lines.trim()}")
                return lines.toString()
            }
            lines.appendLine(line)
        }
    }

    override fun close() {
        runCatching {
            output.write("exit\n")
            output.flush()
        }
        process.destroy()
    }
}

/**
 * Reaches the chip with `i2cget`/`i2cset` (i2c-tools, or busybox) in a [RootShell]. This is the
 * only way an ordinary app can talk to a chip on the I2C bus without a vendor service, so it
 * needs a rooted head unit. `-f` is passed because a kernel driver may already claim the address.
 */
class RootShellI2cBus(
    private val shell: RootShell,
    private val busNumber: Int,
    private val address: Int,
) : I2cBus {

    override fun readRegister(register: Int): Int {
        val reply = shell.run("i2cget -y -f $busNumber ${hex(address)} ${hex(register)} b")
        return parseHex(reply) ?: throw I2cException("Resposta inválida do i2cget: $reply")
    }

    override fun writeRegister(register: Int, value: Int) {
        shell.run("i2cset -y -f $busNumber ${hex(address)} ${hex(register)} ${hex(value and 0xFF)} b")
    }

    override fun close() = shell.close()

    companion object {
        fun hex(value: Int) = "0x" + value.toString(16).padStart(2, '0')

        fun parseHex(reply: String): Int? = reply.trim().lines().lastOrNull()?.trim()?.removePrefix("0x")?.toIntOrNull(16)

        /**
         * Opens a root shell and finds the bus whose device at [address] identifies as a
         * QN8035, unless [busNumber] is given.
         */
        fun open(busNumber: Int?, address: Int): RootShellI2cBus {
            val shell = RootShell()
            try {
                val bus = busNumber ?: findQn8035(shell, address)
                    ?: throw I2cException("Nenhum QN8035 respondeu no endereço ${hex(address)} em /dev/i2c-*")
                return RootShellI2cBus(shell, bus, address)
            } catch (e: Exception) {
                shell.close()
                throw e
            }
        }

        private fun findQn8035(shell: RootShell, address: Int): Int? {
            val buses = shell.run("ls /dev/i2c-* 2>/dev/null || true")
                .lines()
                .mapNotNull { it.trim().removePrefix("/dev/i2c-").toIntOrNull() }
                .sorted()
            return buses.firstOrNull { bus ->
                val id = runCatching {
                    parseHex(shell.run("i2cget -y -f $bus ${hex(address)} ${hex(Qn8035Registers.CID2)} b"))
                }.getOrNull()
                id != null && (id ushr 2) == Qn8035Registers.PRODUCT_ID
            }
        }
    }
}
