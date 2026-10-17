package com.ohenrique.carradio.tuner.rds

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RdsDecoderTest {

    private val decoder = RdsDecoder()

    private fun word(text: String, offset: Int) = (text[offset].code shl 8) or text[offset + 1].code

    private fun sendPs(name: String, pty: Int = 10) {
        val padded = name.padEnd(8)
        for (segment in 0 until 4) {
            decoder.onGroup(0xC123, (pty shl 5) or segment, 0, word(padded, segment * 2))
        }
    }

    private fun sendRt2A(text: String, abFlag: Int = 0, segments: IntRange? = null) {
        val padded = text.padEnd(((text.length + 3) / 4) * 4)
        val range = segments ?: (0 until padded.length / 4)
        for (segment in range) {
            val b = (2 shl 12) or (abFlag shl 4) or segment
            decoder.onGroup(0xC123, b, word(padded, segment * 4), word(padded, segment * 4 + 2))
        }
    }

    @Test
    fun `decodes the station name once all four segments arrive`() {
        decoder.onGroup(0xC123, 0, 0, word("RO", 0))
        decoder.onGroup(0xC123, 1, 0, word("CK", 0))
        assertNull(decoder.info.programService)

        sendPs("ROCK 89")

        assertEquals("ROCK 89", decoder.info.programService)
        assertEquals(0xC123, decoder.info.programId)
        assertEquals(10, decoder.info.programType)
    }

    @Test
    fun `follows a scrolling station name`() {
        sendPs("RADIO")
        sendPs("AO VIVO")
        assertEquals("AO VIVO", decoder.info.programService)
    }

    @Test
    fun `radio text ends at carriage return`() {
        sendRt2A("Bom dia\r")
        assertEquals("Bom dia", decoder.info.radioText)
    }

    @Test
    fun `radio text without carriage return is published when the station repeats it`() {
        sendRt2A("Noticias 24h")
        assertNull(decoder.info.radioText)

        sendRt2A("Noticias 24h", segments = 0..0)

        assertEquals("Noticias 24h", decoder.info.radioText)
    }

    @Test
    fun `a new A-B flag starts a new message`() {
        sendRt2A("Primeira\r", abFlag = 0)
        sendRt2A("Outra msg\r", abFlag = 1)
        assertEquals("Outra msg", decoder.info.radioText)
    }

    @Test
    fun `version B radio text carries two characters per group`() {
        val text = "Oi!\r"
        for (segment in 0 until 2) {
            decoder.onGroup(0xC123, (2 shl 12) or 0x0800 or segment, 0xC123, word(text, segment * 2))
        }
        assertEquals("Oi!", decoder.info.radioText)
    }

    @Test
    fun `corrupted blocks are ignored`() {
        decoder.onGroup(0xC123, 0, 0, null)
        decoder.onGroup(0xC123, null, 0, word("XX", 0))
        assertNull(decoder.info.programService)
    }

    @Test
    fun `maps EBU accented letters`() {
        assertEquals('ç', RdsDecoder.rdsChar(0x9B))
        assertEquals('é', RdsDecoder.rdsChar(0x82))
        assertEquals('A', RdsDecoder.rdsChar(0x41))
    }
}
