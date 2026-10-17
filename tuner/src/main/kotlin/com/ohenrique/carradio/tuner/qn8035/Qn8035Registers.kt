package com.ohenrique.carradio.tuner.qn8035

import kotlin.math.roundToInt

/**
 * Register map of the Quintic QN8035 FM receiver, from the QN8035 datasheet (Rev 0.08).
 *
 * The chip answers on 7-bit I2C address 0x10. Channels are 50 kHz apart starting at 60 MHz,
 * as a 10-bit index split between CH (low 8 bits) and CH_STEP (high 2 bits).
 */
internal object Qn8035Registers {
    const val DEFAULT_ADDRESS = 0x10
    const val PRODUCT_ID = 0x21

    const val SYSTEM1 = 0x00
    const val CCA = 0x01
    const val SNR = 0x02
    const val RSSISIG = 0x03
    const val STATUS1 = 0x04
    const val CID2 = 0x06
    const val CH = 0x07
    const val CH_START = 0x08
    const val CH_STOP = 0x09
    const val CH_STEP = 0x0A
    const val RDSD0 = 0x0B
    const val STATUS2 = 0x13
    const val VOL_CTL = 0x14
    const val XTAL_DIV0 = 0x15
    const val XTAL_DIV1 = 0x16
    const val XTAL_DIV2 = 0x17
    const val INT_CTRL = 0x18

    // SYSTEM1
    const val SWRST = 0x80
    const val STNBY = 0x20
    const val RXREQ = 0x10
    const val RDSEN = 0x08
    const val FORCE_MO = 0x04
    const val CHSC = 0x02
    const val CCA_CH_DIS = 0x01

    // CCA
    const val XTAL_INJ = 0x80
    const val RXCCAD_MASK = 0x3F

    // STATUS1
    const val FSM_SHIFT = 4
    const val FSM_MASK = 0x7
    const val FSM_RECEIVING = 0b101
    const val FSM_RXCCA = 0b111
    const val RXCCA_FAIL = 0x08
    const val ST_MO_RX = 0x01

    // STATUS2
    const val RDS_RXTXUPD = 0x80

    // VOL_CTL
    const val MUTE_EN = 0x80
    const val TC_75US = 0x40
    const val GAIN_ANA_MAX = 0x07

    // INT_CTRL
    const val RDS_ONLY = 0x20

    private const val BASE_KHZ = 60_000
    private const val CHANNEL_KHZ = 50

    /** RSSISIG minus this offset is the signal level in dBµV. */
    const val RSSI_OFFSET = 43

    fun channelOf(frequencyKhz: Int): Int = (frequencyKhz - BASE_KHZ) / CHANNEL_KHZ

    fun frequencyOf(channel: Int): Int = BASE_KHZ + channel * CHANNEL_KHZ

    /** FSTEP field (CH_STEP[7:6]) for a seek step in kHz. */
    fun seekStepBits(stepKhz: Int): Int = when (stepKhz) {
        50 -> 0b00
        100 -> 0b01
        200 -> 0b10
        else -> throw IllegalArgumentException("QN8035 não suporta passo de $stepKhz kHz")
    }

    /** CCA threshold field: a channel is valid when its RSSI in dBµV is above RXCCAD - 10. */
    fun ccaThresholdBits(minimumDbuv: Int): Int = (minimumDbuv + 10).coerceIn(0, RXCCAD_MASK)

    /** XTAL_DIV[10:0] and PLL_DLT[12:0] for the reference clock driving the chip. */
    fun clockDividers(clockHz: Int): Pair<Int, Int> {
        val xtalDiv = (clockHz / 32_768.0).roundToInt().coerceIn(1, 0x7FF)
        val pllDlt = (28_500_000.0 / (clockHz.toDouble() / xtalDiv / 512)).roundToInt() - 442_368
        return xtalDiv to pllDlt.coerceIn(0, 0x1FFF)
    }
}
