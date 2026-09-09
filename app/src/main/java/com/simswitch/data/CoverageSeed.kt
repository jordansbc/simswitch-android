package com.simswitch.data

import android.content.Context
import android.telephony.SubscriptionManager
import org.json.JSONObject

/**
 * Loads the bundled coverage prior into the learned store, once.
 *
 * The asset is built offline by `tools/fetch_coverage_seed.mjs` from CoverageMap's crowdsourced
 * speed squares, bounded to the the target metro area. It exists to give the app an opinion about
 * places the owner hasn't driven yet — nothing more.
 *
 * Two properties keep it honest:
 *
 *  1. It is stored normalised (0..1) in its own column, never as kbps. The source figures are
 *     *speed tests* — best-case, ~200 Mbps median — while our own measurements are passive
 *     everyday traffic and orders of magnitude smaller. Putting them in the same column would let
 *     a carrier that had never been measured beat one that had, purely on units.
 *  2. Its influence fades to nothing as real samples accumulate at a place (see ScoreEngine).
 */
object CoverageSeed {

    private const val ASSET = "coverage_seed.json"
    private const val PREF = "simswitch"
    private const val KEY_LOADED = "seed_loaded_version"
    /** Bump whenever the bundled asset changes so the new data is re-imported. v2 = the target metro. */
    private const val VERSION = 2

    /**
     * Speed-test Mbps that counts as "as good as it gets" for normalisation.
     *
     * Chosen from the observed distribution in the pulled data — p50 ≈ 200 Mbps, p90 ≈ 640. Above
     * this the extra headroom tells us nothing useful about which SIM to prefer.
     */
    private const val EXCELLENT_MBPS = 400.0

    fun loadIfNeeded(context: Context, repo: PlaceRepository) {
        val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        if (prefs.getInt(KEY_LOADED, 0) >= VERSION) return

        // The seed names carriers; the phone knows subscription ids. Match the two up, and if a
        // carrier in the file isn't on this phone, simply skip it.
        val subs = context.getSystemService(SubscriptionManager::class.java)
        val byCarrier = runCatching {
            subs?.activeSubscriptionInfoList.orEmpty()
                .associateBy({ it.carrierName?.toString()?.trim().orEmpty() }, { it.subscriptionId })
        }.getOrDefault(emptyMap())

        if (byCarrier.isEmpty()) return // no SIM info yet — try again next start

        val json = runCatching {
            context.assets.open(ASSET).bufferedReader().use { it.readText() }
        }.getOrNull() ?: return

        val cells = runCatching { JSONObject(json).getJSONObject("cells") }.getOrNull() ?: return

        var written = 0
        val keys = cells.keys()
        while (keys.hasNext()) {
            val placeKey = keys.next()
            val carriers = cells.optJSONObject(placeKey) ?: continue
            val carrierKeys = carriers.keys()
            while (carrierKeys.hasNext()) {
                val carrier = carrierKeys.next()
                val subId = byCarrier[carrier] ?: continue
                val kbps = carriers.optDouble(carrier, Double.NaN)
                if (kbps.isNaN()) continue

                val score = ((kbps / 1000.0) / EXCELLENT_MBPS).coerceIn(0.0, 1.0)
                repo.seed(placeKey, subId, carrier, score)
                written++
            }
        }

        prefs.edit().putInt(KEY_LOADED, VERSION).apply()
        android.util.Log.i("SimSwitch", "coverage seed loaded: $written rows")
    }
}
