package com.simswitch.scoring

import com.simswitch.data.PlaceRepository
import com.simswitch.telephony.BandTier
import com.simswitch.telephony.SubSignal

/**
 * Ranks the two subscriptions.
 *
 * Anchored on **SINR**, deliberately. Phase 2 established that throughput can only ever be measured
 * on whichever SIM is currently carrying data — the standby radio moves no bytes — so a direct
 * throughput comparison between the two is impossible by construction. SINR is the one quality
 * metric reported for *both* lines, and it is also the best single predictor of achievable speed.
 *
 * Throughput still matters, but as a per-carrier calibration of what a given SINR is worth on that
 * network, never as a cross-carrier comparison.
 */
class ScoreEngine(private val repo: PlaceRepository) {

    data class SubScore(
        val subId: Int,
        val carrier: String,
        val total: Double,
        val live: Double,
        val learned: Double,
        val confidence: Double,
        val inService: Boolean,
        val detail: String,
    )

    fun score(signals: Collection<SubSignal>, placeKey: String?): List<SubScore> =
        signals.map { s ->
            // Live radio: SINR normalised, nudged by how good the band is. A low-band cell can show
            // excellent RSRP while delivering far less than a mid-band cell a few dB weaker, so
            // scoring on raw signal alone gets this backwards.
            val liveRaw = normalizeSinr(s.sinr)
            val live = liveRaw * bandFactor(s.bandTier)

            val stat = placeKey?.let { repo.statFor(it, s.subId) }
            val learned = stat?.sinr?.let { normalizeSinr(it) } ?: 0.0
            val confidence = stat?.confidence ?: 0.0

            // With no history the score is pure live radio; as a place accumulates samples the
            // learned view takes over. That is what stops a single momentary reading from
            // overruling everything known about a place.
            val blended = live * (1 - confidence * LEARNED_WEIGHT) +
                learned * (confidence * LEARNED_WEIGHT)

            // The public-coverage prior, which only speaks where we have little of our own data.
            // Its weight is scaled by (1 - confidence), so it fades to exactly nothing once a place
            // is properly sampled. It is never compared against measured throughput directly — it
            // lives in its own normalised column precisely to avoid that.
            val seed = stat?.seedScore ?: 0.0
            val seedWeight = SEED_WEIGHT * (1 - confidence)
            val withSeed = blended * (1 - seedWeight) + seed * seedWeight

            // A line with no service cannot carry data at any signal level.
            val total = if (!s.inService || s.sinr == null && s.rsrp == null) 0.0 else withSeed

            SubScore(
                subId = s.subId,
                carrier = s.carrier,
                total = total,
                live = live,
                learned = learned,
                confidence = confidence,
                inService = s.inService,
                detail = "sinr=${s.sinr ?: "—"} rsrp=${s.rsrp ?: "—"} ${s.rat} " +
                    "band=${s.bands.joinToString(",").ifEmpty { "?" }} n=${stat?.samples ?: 0}",
            )
        }.sortedByDescending { it.total }

    /**
     * SINR in dB to 0..1.
     *
     * Below about 0 dB the link is unusable; above roughly 25 dB extra headroom stops buying
     * throughput, so the scale saturates rather than rewarding a very strong signal indefinitely.
     */
    private fun normalizeSinr(sinr: Double?): Double {
        if (sinr == null) return 0.0
        return ((sinr - SINR_FLOOR) / (SINR_CEIL - SINR_FLOOR)).coerceIn(0.0, 1.0)
    }

    private fun normalizeSinr(sinr: Int?): Double = normalizeSinr(sinr?.toDouble())

    private fun bandFactor(tier: BandTier) = when (tier) {
        BandTier.HIGH -> 1.15
        BandTier.MID -> 1.0
        BandTier.LOW -> 0.85
        BandTier.UNKNOWN -> 0.95
    }

    private companion object {
        const val SINR_FLOOR = -5.0
        const val SINR_CEIL = 25.0

        /** How far the learned view can pull the score once a place is well sampled. */
        const val LEARNED_WEIGHT = 0.6

        /**
         * Ceiling on the public-coverage prior's influence, at a place with zero real samples.
         *
         * Kept modest on purpose: crowdsourced speed tests are best-case and can't see congestion,
         * so this should be enough to break a tie somewhere new and never enough to argue with the
         * radio in front of us.
         */
        const val SEED_WEIGHT = 0.35
    }
}
