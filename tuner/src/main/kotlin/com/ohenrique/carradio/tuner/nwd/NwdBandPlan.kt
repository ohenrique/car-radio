package com.ohenrique.carradio.tuner.nwd

import com.ohenrique.carradio.tuner.Region

/**
 * The FM band as the NWD radio service counts it: [minRaw] to [maxRaw] in steps of [stepRaw], each
 * unit worth [scaleKhz] kHz.
 *
 * The K4811 counts FM in 10 kHz (8750 is 87.5 MHz), other NWD builds in 100 kHz (875) or in kHz,
 * and no call says which. [fromRadioPoints] and [fromFrequency] tell them apart by the size of the
 * numbers, which never overlap for FM.
 */
data class NwdBandPlan(val scaleKhz: Int, val minRaw: Int, val maxRaw: Int, val stepRaw: Int) {

    val minKhz: Int get() = minRaw * scaleKhz
    val maxKhz: Int get() = maxRaw * scaleKhz
    val stepKhz: Int get() = stepRaw * scaleKhz

    /** How many channels the band has, both ends included. */
    val channels: Int get() = (maxRaw - minRaw) / stepRaw + 1

    /** The app's region closest to this band. */
    val region: Region get() = Region.closest(minKhz, maxKhz, stepKhz)

    /** [frequencyKhz] in the service's unit, on the nearest channel of its grid. */
    fun toRaw(frequencyKhz: Int): Int {
        val raw = Math.round(frequencyKhz.toDouble() / scaleKhz).toInt().coerceIn(minRaw, maxRaw)
        val steps = Math.round((raw - minRaw).toDouble() / stepRaw).toInt()
        val snapped = minRaw + steps * stepRaw
        return if (snapped > maxRaw) snapped - stepRaw else snapped
    }

    fun toKhz(raw: Int): Int = raw * scaleKhz

    companion object {
        /**
         * The unit of an FM value as big as [raw]: 10 kHz for 5 000 to 12 000, 100 kHz for 500 to
         * 1 200, kHz for 50 000 to 120 000, and null for anything else. AM values can fall in
         * these ranges too (1 010 kHz would read as 101 MHz), so the unit is only taken from FM.
         */
        fun scaleOf(raw: Int): Int? = when (raw) {
            in 5_000..12_000 -> 10
            in 500..1_200 -> 100
            in 50_000..120_000 -> 1
            else -> null
        }

        /**
         * The plan of the FM entry of a [NwdService.getRadioPoint] answer: entry 0 when it is valid,
         * else the first valid entry whose maximum looks like FM. Null when there is none.
         */
        fun fromRadioPoints(points: List<NwdRadioPoint?>?): NwdBandPlan? {
            if (points == null) return null
            val fm = points.firstOrNull()?.takeIf(::looksLikeFm) ?: points.firstOrNull { it != null && looksLikeFm(it) }
            fm ?: return null
            return NwdBandPlan(scaleOf(fm.max)!!, fm.min, fm.max, fm.step)
        }

        /**
         * A plan with the limits of [region], in the unit [frequency] is counted in. Null unless it
         * is an FM reading above zero: a cold tuner reports 0, and AM is in kHz everywhere.
         */
        fun fromFrequency(frequency: NwdFrequency?, region: Region): NwdBandPlan? {
            if (frequency == null || !Nwd.Band.isFm(frequency.band) || frequency.frequency <= 0) return null
            return forRegion(scaleOf(frequency.frequency) ?: return null, region)
        }

        /** The last resort: 10 kHz units, as on the K4811, with the limits of [region]. */
        fun fallback(region: Region): NwdBandPlan = forRegion(10, region)

        private fun forRegion(scaleKhz: Int, region: Region) = NwdBandPlan(
            scaleKhz = scaleKhz,
            minRaw = region.minKhz / scaleKhz,
            maxRaw = region.maxKhz / scaleKhz,
            stepRaw = maxOf(1, region.stepKhz / scaleKhz),
        )

        private fun looksLikeFm(point: NwdRadioPoint?) = point != null && point.isValid && scaleOf(point.max) != null
    }
}
