package com.simswitch.switching

/**
 * Moves the default data subscription.
 *
 * Phase 0 proved a privileged binder path via Shizuku, and it was the implementation through Phase
 * 4. It has since been removed: Shizuku's server inherits adbd's cgroup and is killed whenever
 * Android disables Wireless debugging, which it does on leaving the Wi-Fi network. That left the
 * mechanism working only on Wi-Fi, and a data switch is never wanted on Wi-Fi. The interface stays
 * because the shape was right even when the mechanism was not — see [AccessibilitySwitcher].
 */
interface SimSwitcher {

    /**
     * Why a switch cannot happen right now, or null if it can.
     *
     * A reason rather than a bare boolean because the answer is routinely "not yet" rather than
     * "broken" — a sleeping phone is the normal case, not a fault — and the distinction is only
     * useful if it survives into the decisions log in words.
     */
    fun unavailableReason(): String?

    fun isAvailable(): Boolean = unavailableReason() == null

    /**
     * Attempt the switch and report whether the data subscription *actually* moved.
     *
     * Implementations must verify rather than trust: on this platform a call can return cleanly, or
     * a tap can land, while the DDS stays exactly where it was.
     */
    fun switchTo(subId: Int): Result

    data class Result(val success: Boolean, val detail: String)
}
