package com.ohenrique.carradio.tuner

/**
 * Walks the whole band with [Tuner.seek] and returns every station found, like the
 * "Sintonia automática" button of a stock radio.
 *
 * The tuner is left on the strongest station found, or on [Region.minKhz] when none was found.
 */
suspend fun Tuner.scanBand(onStationFound: (FoundStation) -> Unit = {}): List<FoundStation> {
    val region = state.value.region
    val found = mutableListOf<FoundStation>()
    tune(region.minKhz)
    var lastKhz = region.minKhz
    while (true) {
        val station = seek(SeekDirection.UP, wrap = false) ?: break
        if (station.frequencyKhz <= lastKhz) break
        found += station
        onStationFound(station)
        lastKhz = station.frequencyKhz
        if (lastKhz >= region.maxKhz) break
    }
    val best = found.maxByOrNull { it.rssiDbuv ?: Int.MIN_VALUE }
    tune(best?.frequencyKhz ?: region.minKhz)
    return found
}

/** Picks at most [limit] stations, strongest first, and returns them sorted by frequency. */
fun List<FoundStation>.strongest(limit: Int): List<FoundStation> =
    sortedByDescending { it.rssiDbuv ?: Int.MIN_VALUE }
        .take(limit)
        .sortedBy { it.frequencyKhz }
