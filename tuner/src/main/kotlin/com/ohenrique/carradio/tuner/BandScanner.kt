package com.ohenrique.carradio.tuner

/**
 * Walks the whole band with [Tuner.seek] and returns every station found, sorted by frequency,
 * like the "Sintonia automática" button of a stock radio.
 *
 * Seeks up from the bottom of the band, wrapping around, until a station comes up a second time.
 * The tuner is left on the strongest station found (the first one when the hardware does not
 * report signal strength), or on [Region.minKhz] when none was found.
 */
suspend fun Tuner.scanBand(onStationFound: (FoundStation) -> Unit = {}): List<FoundStation> {
    val region = state.value.region
    val found = linkedMapOf<Int, FoundStation>()
    tune(region.minKhz)
    while (found.size < MAX_SCAN_STATIONS) {
        val station = seek(SeekDirection.UP, wrap = true) ?: break
        if (station.frequencyKhz in found) break
        found[station.frequencyKhz] = station
        onStationFound(station)
    }
    val stations = found.values.sortedBy { it.frequencyKhz }
    val best = stations.filter { it.rssiDbuv != null }.maxByOrNull { it.rssiDbuv!! } ?: stations.firstOrNull()
    tune(best?.frequencyKhz ?: region.minKhz)
    return stations
}

private const val MAX_SCAN_STATIONS = 100

/** Picks at most [limit] stations, strongest first, and returns them sorted by frequency. */
fun List<FoundStation>.strongest(limit: Int): List<FoundStation> =
    sortedByDescending { it.rssiDbuv ?: Int.MIN_VALUE }
        .take(limit)
        .sortedBy { it.frequencyKhz }
