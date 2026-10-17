package com.ohenrique.carradio.tuner.rds

import com.ohenrique.carradio.tuner.RdsInfo

/**
 * Decodes RDS groups (IEC 62106 / EN 50067) into station name and radio text.
 *
 * Feed it one group at a time; pass null for any block the receiver flagged as corrupted.
 * Only groups 0 (Program Service name) and 2 (RadioText) are decoded, which is all a car
 * radio screen shows.
 */
class RdsDecoder {

    var info: RdsInfo = RdsInfo.EMPTY
        private set

    private val ps = CharArray(PS_LENGTH) { ' ' }
    private var psMask = 0

    private val rt = CharArray(RT_MAX_LENGTH) { ' ' }
    private var rtMask = 0
    private var rtAbFlag = -1
    private var rtVersionB = false
    private var rtEndIndex = -1
    private var rtLastSegment = -1

    fun reset() {
        info = RdsInfo.EMPTY
        ps.fill(' ')
        psMask = 0
        resetRadioText()
    }

    /** Returns true when [info] changed. */
    fun onGroup(blockA: Int?, blockB: Int?, blockC: Int?, blockD: Int?): Boolean {
        val before = info
        if (blockA != null) info = info.copy(programId = blockA and 0xFFFF)
        if (blockB == null) return info != before

        val groupType = (blockB ushr 12) and 0xF
        val versionB = (blockB and 0x0800) != 0
        info = info.copy(programType = (blockB ushr 5) and 0x1F)

        when (groupType) {
            0 -> if (blockD != null) onProgramService(blockB and 0x3, blockD)
            2 -> onRadioText(blockB, versionB, blockC, blockD)
        }
        return info != before
    }

    private fun onProgramService(segment: Int, blockD: Int) {
        ps[segment * 2] = rdsChar(blockD ushr 8)
        ps[segment * 2 + 1] = rdsChar(blockD)
        psMask = psMask or (1 shl segment)
        if (psMask == 0xF) {
            val name = String(ps).trim()
            info = info.copy(programService = name.ifEmpty { null })
            psMask = 0
        }
    }

    private fun onRadioText(blockB: Int, versionB: Boolean, blockC: Int?, blockD: Int?) {
        val abFlag = (blockB ushr 4) and 0x1
        if (abFlag != rtAbFlag || versionB != rtVersionB) {
            // A/B flag toggles when the station starts a new message.
            resetRadioText()
            rtAbFlag = abFlag
            rtVersionB = versionB
        }
        val segment = blockB and 0xF
        val chars = if (versionB) {
            blockD ?: return
            intArrayOf(blockD ushr 8, blockD)
        } else {
            if (blockC == null || blockD == null) return
            intArrayOf(blockC ushr 8, blockC, blockD ushr 8, blockD)
        }
        val start = segment * chars.size
        chars.forEachIndexed { i, code ->
            val index = start + i
            if ((code and 0xFF) == CARRIAGE_RETURN && (rtEndIndex < 0 || index < rtEndIndex)) rtEndIndex = index
            rt[index] = rdsChar(code)
        }

        val wrapped = segment <= rtLastSegment
        rtLastSegment = segment
        rtMask = rtMask or (1 shl segment)

        val segmentsComplete = Integer.numberOfTrailingZeros(rtMask.inv())
        val text = when {
            rtEndIndex >= 0 && segmentsComplete * chars.size > rtEndIndex -> String(rt, 0, rtEndIndex)
            wrapped && segmentsComplete > 0 -> String(rt, 0, segmentsComplete * chars.size)
            rtMask == 0xFFFF -> String(rt, 0, 16 * chars.size)
            else -> return
        }
        info = info.copy(radioText = text.trim().replace(Regex("\\s+"), " ").ifEmpty { null })
    }

    private fun resetRadioText() {
        rt.fill(' ')
        rtMask = 0
        rtAbFlag = -1
        rtEndIndex = -1
        rtLastSegment = -1
    }

    companion object {
        private const val PS_LENGTH = 8
        private const val RT_MAX_LENGTH = 64
        private const val CARRIAGE_RETURN = 0x0D

        /** Rows 8 and 9 of the EBU Latin character set, which hold the accented letters. */
        private const val EBU_ROWS_8_9 = "áàéèíìóòúùÑÇŞß¡Ĳâäêëîïôöûüñçşğıĳ"

        fun rdsChar(code: Int): Char {
            val c = code and 0xFF
            return when (c) {
                in 0x20..0x7E -> c.toChar()
                in 0x80..0x9F -> EBU_ROWS_8_9[c - 0x80]
                else -> ' '
            }
        }
    }
}
