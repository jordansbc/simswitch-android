package com.simswitch.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Storage for the learned place map and the raw sample history.
 *
 * Plain SQLiteOpenHelper rather than Room: the schema is two tables, and Room would drag in an
 * annotation processor on a toolchain (AGP 9 with built-in Kotlin) that has already proven touchy
 * about extra plugins. Not worth the build risk for two tables.
 */
class SimSwitchDb(context: Context) : SQLiteOpenHelper(context, NAME, null, VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        // The learned map. One row per (place, subscription).
        //
        // `source` distinguishes a row seeded from public coverage data from one built out of real
        // measurements, and `samples` is what makes the seed lose gracefully: a seed lands with a
        // sample count of 2, so by the third real measurement it is numerically irrelevant.
        db.execSQL(
            """
            CREATE TABLE place_stats (
                place_key  TEXT    NOT NULL,
                sub_id     INTEGER NOT NULL,
                carrier    TEXT,
                kbps_ewma  REAL,
                sinr_ewma  REAL,
                -- Public coverage data lives in its own column, never mixed into kbps_ewma.
                -- Seed figures come from speed tests (best-case, ~200 Mbps median); our own
                -- kbps_ewma comes from passive everyday traffic and is far smaller. Comparing a
                -- seeded carrier against a measured one in the same units would hand the win to
                -- whichever had never been measured. Stored normalised 0..1 instead.
                seed_score REAL,
                samples    INTEGER NOT NULL DEFAULT 0,
                source     TEXT    NOT NULL DEFAULT 'MEASURED',
                updated_at INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY (place_key, sub_id)
            )
            """.trimIndent()
        )

        // Raw history, kept so Phase 3's thresholds can be tuned against real recorded behaviour
        // instead of guessed. Pruned to the most recent MAX_SAMPLES rows.
        db.execSQL(
            """
            CREATE TABLE samples (
                id          INTEGER PRIMARY KEY AUTOINCREMENT,
                ts          INTEGER NOT NULL,
                place_key   TEXT,
                lat         REAL,
                lon         REAL,
                sub_id      INTEGER NOT NULL,
                is_data_sub INTEGER NOT NULL,
                rat         TEXT,
                bands       TEXT,
                rsrp        INTEGER,
                sinr        INTEGER,
                kbps        REAL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_samples_ts ON samples(ts)")
        db.execSQL("CREATE INDEX idx_samples_place ON samples(place_key)")

        // Every verdict the policy reaches, including the ones it declined to act on. In shadow
        // mode this IS the deliverable: it is what gets checked against reality before the switcher
        // is armed, and what the margin and streak constants get tuned against afterwards.
        db.execSQL(
            """
            CREATE TABLE decisions (
                id         INTEGER PRIMARY KEY AUTOINCREMENT,
                ts         INTEGER NOT NULL,
                place_key  TEXT,
                verdict    TEXT    NOT NULL,
                from_sub   INTEGER NOT NULL,
                to_sub     INTEGER,
                margin     REAL,
                streak     INTEGER,
                blocker    TEXT,
                urgent     INTEGER NOT NULL DEFAULT 0,
                reason     TEXT,
                scores     TEXT
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_decisions_ts ON decisions(ts)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Up to v3 the store was still Phase-2 scratch data and dropping it cost nothing. It is not
        // scratch any more — by v4 it held ten days of samples and a coverage seed that takes a
        // network fetch to rebuild — so every migration from here has to repair in place.
        if (oldVersion < 3) {
            db.execSQL("DROP TABLE IF EXISTS place_stats")
            db.execSQL("DROP TABLE IF EXISTS samples")
            db.execSQL("DROP TABLE IF EXISTS decisions")
            onCreate(db)
            return
        }
        if (oldVersion < 4) repairImplausibleThroughput(db)
    }

    /**
     * Undo the throughput samples that were recorded while a VPN was in the path.
     *
     * `isOnWifi()` read `getActiveNetwork()`, which is the VPN network when a VPN is up, so the old
     * `!onWifi` gate opened on Wi-Fi behind a VPN and filed whole windows of Wi-Fi traffic against
     * whichever SIM was the DDS. The ceiling that should have caught it was set at 1.5 Gbps, so
     * 234,793 kbps and 224,797 kbps windows went straight in.
     *
     * A single such window is not a blemish on an average — [blend] gives each new sample 30% of
     * the result, so one 234 Mbps reading leaves a place claiming ~70 Mbps for AT&T indefinitely
     * and permanently biases it. The samples table is the source of truth, so the fix is to drop
     * the impossible readings and re-derive every measured average from what survives.
     *
     * Deliberately reruns the real EWMA in sample order rather than substituting a plain mean:
     * a repair that produced different numbers from the live code would be its own kind of wrong.
     */
    private fun repairImplausibleThroughput(db: SQLiteDatabase) {
        db.beginTransaction()
        try {
            db.execSQL("UPDATE samples SET kbps = NULL WHERE kbps > ?", arrayOf<Any>(MAX_PLAUSIBLE_KBPS))

            // Only measured rows have a kbps_ewma to rebuild; seed rows never carry one.
            val rows = db.rawQuery(
                "SELECT place_key, sub_id FROM place_stats WHERE source = 'MEASURED'", null,
            )
            val targets = mutableListOf<Pair<String, Int>>()
            rows.use { while (it.moveToNext()) targets += it.getString(0) to it.getInt(1) }

            for ((placeKey, subId) in targets) {
                var ewma: Double? = null
                db.rawQuery(
                    "SELECT kbps FROM samples WHERE place_key = ? AND sub_id = ? " +
                        "AND kbps IS NOT NULL ORDER BY ts",
                    arrayOf(placeKey, subId.toString()),
                ).use { c ->
                    while (c.moveToNext()) {
                        val observed = c.getDouble(0)
                        // PlaceRepository.blend's own constant, not a copy of it — a repair that
                        // smoothed differently from the live path would produce numbers the app
                        // could never have arrived at on its own.
                        val a = PlaceRepository.ALPHA
                        ewma = ewma?.let { a * observed + (1 - a) * it } ?: observed
                    }
                }
                db.execSQL(
                    "UPDATE place_stats SET kbps_ewma = ? WHERE place_key = ? AND sub_id = ?",
                    arrayOf<Any?>(ewma, placeKey, subId),
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    companion object {
        private const val NAME = "simswitch.db"
        private const val VERSION = 4
        const val MAX_SAMPLES = 200_000

        /**
         * Backstop against counter artefacts that survive the transport gate, applied both to live
         * samples and retrospectively by [repairImplausibleThroughput]. Lives here, next to the
         * migration, so the ingest rule and the repair rule cannot drift apart.
         *
         * 1.5 Gbps was the original value, set to catch one specific 830 Mbps blip and far too
         * generous to catch anything else: 234,793 kbps windows sailed under it and are now baked
         * into the learned map. 100 Mbps is still ~12x the highest genuine passive sample observed
         * over ten days (8.27 Mbps), so it cannot discard a real reading, while a window claiming
         * 100 Mbps means ~250 MB moved in 20 s of background use — an accounting artefact by
         * definition.
         *
         * A backstop, not the fix: a VPN inflating the counters 2-3x produces values well under
         * any ceiling, and that class is caught by `DeviceState.isOnCellular` instead.
         */
        const val MAX_PLAUSIBLE_KBPS = 100_000.0
    }
}

/** One learned opinion: how a given subscription performs at a given place. */
data class PlaceStat(
    val placeKey: String,
    val subId: Int,
    val carrier: String,
    val kbps: Double?,
    val sinr: Double?,
    /** Normalised 0..1 prior from public coverage data; independent of [kbps]. */
    val seedScore: Double?,
    val samples: Int,
    val source: String,
    val updatedAt: Long,
) {
    val isSeed: Boolean get() = source == "SEED"

    /**
     * How much this row should count, 0..1. Saturates around 10 samples.
     *
     * This is the mechanism that lets public coverage data help without ever overriding reality:
     * a seed enters with samples=2 (confidence ~0.2) and real measurements quickly swamp it.
     */
    val confidence: Double get() = (samples / 10.0).coerceAtMost(1.0)
}

class PlaceRepository(context: Context) {

    private val helper = SimSwitchDb(context.applicationContext)

    /** Fold one observation into the learned map, and keep the raw row. */
    fun record(
        placeKey: String?,
        lat: Double?,
        lon: Double?,
        subId: Int,
        carrier: String,
        isDataSub: Boolean,
        rat: String,
        bands: String,
        rsrp: Int?,
        sinr: Int?,
        kbps: Double?,
    ) {
        val db = helper.writableDatabase
        val now = System.currentTimeMillis()

        db.insert("samples", null, ContentValues().apply {
            put("ts", now)
            put("place_key", placeKey)
            lat?.let { put("lat", it) }
            lon?.let { put("lon", it) }
            put("sub_id", subId)
            put("is_data_sub", if (isDataSub) 1 else 0)
            put("rat", rat)
            put("bands", bands)
            rsrp?.let { put("rsrp", it) }
            sinr?.let { put("sinr", it) }
            kbps?.let { put("kbps", it) }
        })

        if (placeKey == null) return

        // EWMA rather than a running mean: networks change (new towers, new congestion patterns),
        // so recent evidence should outweigh a measurement from three months ago.
        val existing = statFor(placeKey, subId)
        val newKbps = blend(existing?.kbps, kbps, existing?.isSeed == true)
        val newSinr = blend(existing?.sinr, sinr?.toDouble(), existing?.isSeed == true)

        // A seed row is replaced by measured data the first time we actually measure here — its
        // sample count must not keep inflating a guess.
        val baseSamples = if (existing == null || existing.isSeed) 0 else existing.samples

        db.execSQL(
            """
            INSERT INTO place_stats (place_key, sub_id, carrier, kbps_ewma, sinr_ewma, samples, source, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, 'MEASURED', ?)
            ON CONFLICT(place_key, sub_id) DO UPDATE SET
                carrier = excluded.carrier,
                kbps_ewma = excluded.kbps_ewma,
                sinr_ewma = excluded.sinr_ewma,
                samples = excluded.samples,
                source = 'MEASURED',
                updated_at = excluded.updated_at
            """.trimIndent(),
            arrayOf<Any?>(placeKey, subId, carrier, newKbps, newSinr, baseSamples + 1, now)
        )
    }

    /**
     * Seed a place from public coverage data.
     *
     * Writes only [seedScore] — never kbps — and only fills the column in, so a place that already
     * has real measurements keeps them. Measured rows can also carry a seed score; the scoring
     * engine fades it out as real samples accumulate.
     */
    fun seed(placeKey: String, subId: Int, carrier: String, seedScore: Double) {
        helper.writableDatabase.execSQL(
            """
            INSERT INTO place_stats (place_key, sub_id, carrier, seed_score, samples, source, updated_at)
            VALUES (?, ?, ?, ?, 0, 'SEED', ?)
            ON CONFLICT(place_key, sub_id) DO UPDATE SET seed_score = excluded.seed_score
            """.trimIndent(),
            arrayOf<Any?>(placeKey, subId, carrier, seedScore, System.currentTimeMillis())
        )
    }

    fun seededCellCount(): Int =
        helper.readableDatabase.rawQuery("SELECT COUNT(DISTINCT place_key) FROM place_stats WHERE seed_score IS NOT NULL", null)
            .use { if (it.moveToFirst()) it.getInt(0) else 0 }

    fun statFor(placeKey: String, subId: Int): PlaceStat? =
        helper.readableDatabase.rawQuery(
            "SELECT place_key, sub_id, carrier, kbps_ewma, sinr_ewma, seed_score, samples, source, updated_at " +
                "FROM place_stats WHERE place_key = ? AND sub_id = ?",
            arrayOf(placeKey, subId.toString())
        ).use { if (it.moveToFirst()) it.toStat() else null }

    fun statsFor(placeKey: String): List<PlaceStat> =
        helper.readableDatabase.rawQuery(
            "SELECT place_key, sub_id, carrier, kbps_ewma, sinr_ewma, seed_score, samples, source, updated_at " +
                "FROM place_stats WHERE place_key = ?",
            arrayOf(placeKey)
        ).use { c -> buildList { while (c.moveToNext()) add(c.toStat()) } }

    fun topPlaces(limit: Int = 30): List<PlaceStat> =
        helper.readableDatabase.rawQuery(
            "SELECT place_key, sub_id, carrier, kbps_ewma, sinr_ewma, seed_score, samples, source, updated_at " +
                "FROM place_stats ORDER BY samples DESC, updated_at DESC LIMIT ?",
            arrayOf(limit.toString())
        ).use { c -> buildList { while (c.moveToNext()) add(c.toStat()) } }

    fun recordDecision(
        placeKey: String?,
        verdict: String,
        fromSub: Int,
        toSub: Int?,
        margin: Double,
        streak: Int,
        blocker: String?,
        urgent: Boolean,
        reason: String,
        scores: String,
    ) {
        helper.writableDatabase.insert("decisions", null, ContentValues().apply {
            put("ts", System.currentTimeMillis())
            put("place_key", placeKey)
            put("verdict", verdict)
            put("from_sub", fromSub)
            toSub?.let { put("to_sub", it) }
            put("margin", margin)
            put("streak", streak)
            put("blocker", blocker)
            put("urgent", if (urgent) 1 else 0)
            put("reason", reason)
            put("scores", scores)
        })
    }

    data class DecisionRow(val ts: Long, val verdict: String, val blocker: String?, val reason: String)

    fun recentDecisions(limit: Int = 12): List<DecisionRow> =
        helper.readableDatabase.rawQuery(
            "SELECT ts, verdict, blocker, reason FROM decisions ORDER BY id DESC LIMIT ?",
            arrayOf(limit.toString())
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(DecisionRow(c.getLong(0), c.getString(1), if (c.isNull(2)) null else c.getString(2), c.getString(3) ?: ""))
                }
            }
        }

    /** Verdict counts, so the dashboard can show what shadow mode would have done overall. */
    fun decisionSummary(): Map<String, Int> =
        helper.readableDatabase.rawQuery(
            "SELECT verdict, COUNT(*) FROM decisions GROUP BY verdict", null
        ).use { c -> buildMap { while (c.moveToNext()) put(c.getString(0), c.getInt(1)) } }

    fun blockerSummary(): Map<String, Int> =
        helper.readableDatabase.rawQuery(
            "SELECT blocker, COUNT(*) FROM decisions WHERE blocker IS NOT NULL GROUP BY blocker", null
        ).use { c -> buildMap { while (c.moveToNext()) put(c.getString(0), c.getInt(1)) } }

    fun counts(): Triple<Int, Int, Int> {
        val db = helper.readableDatabase
        fun one(sql: String) = db.rawQuery(sql, null).use { if (it.moveToFirst()) it.getInt(0) else 0 }
        return Triple(
            one("SELECT COUNT(DISTINCT place_key) FROM place_stats"),
            one("SELECT COUNT(*) FROM samples"),
            one("SELECT COUNT(*) FROM place_stats WHERE source='SEED'"),
        )
    }

    fun prune() {
        helper.writableDatabase.execSQL(
            "DELETE FROM samples WHERE id NOT IN (SELECT id FROM samples ORDER BY id DESC LIMIT ?)",
            arrayOf(SimSwitchDb.MAX_SAMPLES)
        )
    }

    private fun blend(old: Double?, observed: Double?, oldWasSeed: Boolean): Double? = when {
        observed == null -> old
        old == null -> observed
        // The first real measurement discards the seed's guess outright rather than averaging
        // with it — a modeled number should not pull a measured one around.
        oldWasSeed -> observed
        else -> ALPHA * observed + (1 - ALPHA) * old
    }

    private fun android.database.Cursor.toStat() = PlaceStat(
        placeKey = getString(0),
        subId = getInt(1),
        carrier = getString(2) ?: "",
        kbps = if (isNull(3)) null else getDouble(3),
        sinr = if (isNull(4)) null else getDouble(4),
        seedScore = if (isNull(5)) null else getDouble(5),
        samples = getInt(6),
        source = getString(7),
        updatedAt = getLong(8),
    )

    // Not private: the v4 repair migration re-derives stored averages and has to smooth them with
    // this exact constant rather than a copy of its value.
    companion object {
        const val ALPHA = 0.3
    }
}
