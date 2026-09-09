package com.simswitch.telephony

/**
 * One radio snapshot for one subscription.
 *
 * Populated for *both* SIMs. On DSDS only one radio carries data at a time, but the standby SIM
 * stays camped for paging and still reports signal — coarser and lazier than the active line, but
 * real. Confirming that is one of Phase 1's jobs; if the standby line turns out to report nothing
 * useful, the scoring design has to lean almost entirely on learned place history instead.
 */
data class SubSignal(
    val subId: Int,
    val slot: Int,
    val carrier: String,
    val isDataSub: Boolean,
    val inService: Boolean = false,
    val rat: String = "?",
    val bands: List<Int> = emptyList(),
    val bandTier: BandTier = BandTier.UNKNOWN,
    /** Reference signal received power, dBm. Roughly -44 (excellent) to -140 (dead). */
    val rsrp: Int? = null,
    /** Reference signal received quality, dB. */
    val rsrq: Int? = null,
    /** Signal-to-noise. The single best predictor of throughput out of these three. */
    val sinr: Int? = null,
    /** The platform's own 0..4 bars. Useful as a sanity check, too coarse to score on. */
    val level: Int = 0,
    val updatedAt: Long = 0L,
) {
    /** True when we have actually heard something real from this subscription. */
    val hasData: Boolean get() = rsrp != null || sinr != null

    fun ageMillis(now: Long): Long = if (updatedAt == 0L) Long.MAX_VALUE else now - updatedAt
}

/**
 * Frequency class, which matters more than raw dBm.
 *
 * Low band travels far and penetrates buildings but is narrow, so it is often slow while showing
 * excellent RSRP. Mid band is the sweet spot and is where most real 5G throughput comes from.
 * Comparing -95 dBm on n71 against -105 dBm on n77 by dBm alone gets the answer backwards, which
 * is exactly the mistake a naive "switch to the stronger signal" app makes.
 */
enum class BandTier { LOW, MID, HIGH, UNKNOWN }

object Bands {
    // n71/n5/n12/n13/n14/n26/n28/n29 are sub-1GHz; n77/n78/n41/n40/n25/n66/n2/n7/n30 are mid.
    private val NR_LOW = setOf(5, 8, 12, 13, 14, 18, 20, 26, 28, 29, 71)
    private val NR_MID = setOf(1, 2, 3, 7, 25, 30, 38, 40, 41, 48, 66, 70, 75, 76, 77, 78, 79)
    private val NR_HIGH = setOf(257, 258, 259, 260, 261) // mmWave

    private val LTE_LOW = setOf(5, 8, 12, 13, 14, 17, 18, 19, 20, 26, 28, 29, 71)
    private val LTE_MID = setOf(1, 2, 3, 4, 7, 25, 30, 38, 39, 40, 41, 46, 48, 66)

    fun tierOf(rat: String, bands: List<Int>): BandTier {
        if (bands.isEmpty()) return BandTier.UNKNOWN
        val isNr = rat.startsWith("NR", ignoreCase = true)
        // Take the best tier present — carrier aggregation means the high band is the one doing
        // the heavy lifting even when a low anchor is also reported.
        return bands.map { band ->
            when {
                isNr && band in NR_HIGH -> BandTier.HIGH
                isNr && band in NR_MID -> BandTier.MID
                isNr && band in NR_LOW -> BandTier.LOW
                !isNr && band in LTE_MID -> BandTier.MID
                !isNr && band in LTE_LOW -> BandTier.LOW
                else -> BandTier.UNKNOWN
            }
        }.minByOrNull {
            when (it) {
                BandTier.HIGH -> 0
                BandTier.MID -> 1
                BandTier.LOW -> 2
                BandTier.UNKNOWN -> 3
            }
        } ?: BandTier.UNKNOWN
    }
}
