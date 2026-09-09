package com.simswitch.telephony

import android.net.TrafficStats

/**
 * Passive throughput measurement — never probes, never spends data.
 *
 * Samples the device-wide mobile byte counters and reports the rate over each window. Windows with
 * too little traffic are discarded rather than recorded as "slow": an idle phone is not a slow
 * network, and recording idleness as a measurement would poison the learned map with zeros for
 * every place the owner simply wasn't using their phone.
 *
 * The reported figure is a *floor*, not a capacity — it says the network delivered at least this
 * much, because we only ever see the traffic that happened to be requested. Rankings built from it
 * are still meaningful since both SIMs are judged on the same basis over time.
 */
class ThroughputMonitor {

    private var lastRx = 0L
    private var lastTx = 0L
    private var lastAt = 0L

    fun reset() {
        lastRx = TrafficStats.getMobileRxBytes()
        lastTx = TrafficStats.getMobileTxBytes()
        lastAt = System.currentTimeMillis()
    }

    /**
     * @return observed kbps for the window just elapsed, or null when there wasn't enough traffic
     *         to say anything meaningful.
     */
    fun sample(): Double? {
        val rx = TrafficStats.getMobileRxBytes()
        val tx = TrafficStats.getMobileTxBytes()
        val now = System.currentTimeMillis()

        // TrafficStats counters are cumulative since boot and reset on reboot; a negative delta
        // means we're comparing across a reboot, so just re-baseline.
        if (lastAt == 0L || rx < lastRx || tx < lastTx) {
            lastRx = rx; lastTx = tx; lastAt = now
            return null
        }

        val bytes = (rx - lastRx) + (tx - lastTx)
        val seconds = (now - lastAt) / 1000.0

        lastRx = rx; lastTx = tx; lastAt = now

        if (seconds < 1.0 || bytes < MIN_BYTES) return null
        return (bytes * 8.0 / 1000.0) / seconds
    }

    private companion object {
        /**
         * ~250 KB in a window. Low enough that ordinary browsing or a podcast qualifies, high
         * enough that a keepalive ping or a push notification doesn't masquerade as a speed test.
         */
        const val MIN_BYTES = 250_000L
    }
}
