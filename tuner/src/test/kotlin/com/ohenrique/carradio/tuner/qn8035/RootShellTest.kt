package com.ohenrique.carradio.tuner.qn8035

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File
import java.nio.file.Files

class RootShellTest {

    @Test
    fun `runs commands in one shell and returns their output`() {
        RootShell(listOf("sh")).use { shell ->
            shell.run("X=42")
            assertEquals("42", shell.run("echo \$X").trim())
            assertThrows(I2cException::class.java) { shell.run("false") }
            assertEquals("still alive", shell.run("echo still alive").trim())
        }
    }

    @Test
    fun `talks to the chip through i2cget and i2cset`() {
        // Fake i2c-tools that keep register values in files.
        val dir = Files.createTempDirectory("i2c").toFile()
        File(dir, "i2cset").apply {
            writeText("#!/bin/sh\necho \"\$6\" > \"${dir.path}/reg_\$5\"\n")
            setExecutable(true)
        }
        File(dir, "i2cget").apply {
            writeText("#!/bin/sh\ncat \"${dir.path}/reg_\$5\" 2>/dev/null || echo 0x00\n")
            setExecutable(true)
        }
        val shell = RootShell(listOf("sh"))
        shell.run("export PATH=\"${dir.path}:\$PATH\"")
        RootShellI2cBus(shell, busNumber = 1, address = 0x10).use { bus ->
            bus.writeRegister(0x07, 0xAA)
            assertEquals(0xAA, bus.readRegister(0x07))
            assertEquals(0x00, bus.readRegister(0x08))
        }
        dir.deleteRecursively()
    }

    @Test
    fun `parses i2cget replies`() {
        assertEquals(0x84, RootShellI2cBus.parseHex("0x84\n"))
        assertEquals(null, RootShellI2cBus.parseHex("Error: Read failed"))
    }
}
