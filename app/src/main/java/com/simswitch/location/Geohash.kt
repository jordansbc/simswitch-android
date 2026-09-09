package com.simswitch.location

/**
 * Minimal geohash encoder.
 *
 * Precision 7 is ~150m x 150m, which is the right grain for "am I at home / at this intersection".
 * Finer would fragment the learned store so every visit looks like a new place; coarser would blur
 * a dead spot together with the good coverage a block away.
 *
 * Geohash has a well-known edge artifact — two points either side of a cell boundary get completely
 * different keys despite being metres apart. That is tolerable here because the score also carries
 * live radio and the seeded prior, so a boundary crossing degrades to "no history yet" rather than
 * a wrong answer.
 */
object Geohash {

    private const val BASE32 = "0123456789bcdefghjkmnpqrstuvwxyz"

    fun encode(lat: Double, lon: Double, precision: Int = 7): String {
        var latMin = -90.0; var latMax = 90.0
        var lonMin = -180.0; var lonMax = 180.0

        val hash = StringBuilder(precision)
        var bit = 0
        var chIndex = 0
        var even = true // alternate longitude / latitude, starting with longitude

        while (hash.length < precision) {
            if (even) {
                val mid = (lonMin + lonMax) / 2
                if (lon >= mid) { chIndex = chIndex * 2 + 1; lonMin = mid } else { chIndex *= 2; lonMax = mid }
            } else {
                val mid = (latMin + latMax) / 2
                if (lat >= mid) { chIndex = chIndex * 2 + 1; latMin = mid } else { chIndex *= 2; latMax = mid }
            }
            even = !even

            if (++bit == 5) {
                hash.append(BASE32[chIndex])
                bit = 0
                chIndex = 0
            }
        }
        return hash.toString()
    }
}
