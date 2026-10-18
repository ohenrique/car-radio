package com.ohenrique.carradio.tuner

/**
 * FM band plan. The stock Adak radio recommends "América do Sul 2", which covers the extended
 * Brazilian band (76.1 to 87.9 MHz, used by stations that migrated from AM) plus the regular band.
 */
enum class Region(
    val minKhz: Int,
    val maxKhz: Int,
    val stepKhz: Int,
    /** FM de-emphasis time constant: 75 µs in the Americas, 50 µs elsewhere. */
    val deemphasisMicros: Int,
    val defaultFrequencyKhz: Int,
) {
    BRAZIL(76_100, 107_900, 100, 75, 87_900),
    AMERICAS(87_500, 108_000, 100, 75, 87_500),
    USA(87_900, 107_900, 200, 75, 87_900),
    EUROPE(87_500, 108_000, 50, 50, 87_500),
    JAPAN(76_000, 95_000, 100, 50, 76_000);

    /** Clamps [frequencyKhz] into the band and rounds it to the nearest channel. */
    fun snap(frequencyKhz: Int): Int {
        val clamped = frequencyKhz.coerceIn(minKhz, maxKhz)
        val steps = Math.round((clamped - minKhz).toDouble() / stepKhz).toInt()
        return (minKhz + steps * stepKhz).coerceAtMost(maxKhz)
    }

    /** Moves [steps] channels from [frequencyKhz], wrapping around the band edges. */
    fun step(frequencyKhz: Int, steps: Int): Int {
        val channels = (maxKhz - minKhz) / stepKhz + 1
        val index = (snap(frequencyKhz) - minKhz) / stepKhz
        val next = Math.floorMod(index + steps, channels)
        return minKhz + next * stepKhz
    }

    operator fun contains(frequencyKhz: Int): Boolean = frequencyKhz in minKhz..maxKhz

    companion object {
        /** The region whose grid matches a band a head unit reports, in kHz. */
        fun closest(minKhz: Int, maxKhz: Int, stepKhz: Int?): Region = when {
            minKhz < 80_000 && maxKhz <= 95_000 -> JAPAN
            minKhz < 80_000 -> BRAZIL
            stepKhz == 200 -> USA
            stepKhz == 50 -> EUROPE
            else -> AMERICAS
        }
    }
}

/** "98.5" style label for a frequency in kHz. 50 kHz steps get a second decimal. */
fun formatMhz(frequencyKhz: Int): String {
    val mhz = frequencyKhz / 1000
    val rest = frequencyKhz % 1000
    return if (rest % 100 == 0) "$mhz.${rest / 100}" else "$mhz.${(rest / 10).toString().padStart(2, '0')}"
}
