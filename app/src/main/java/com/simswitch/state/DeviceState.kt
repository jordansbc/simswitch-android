package com.simswitch.state

import android.content.Context
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.net.wifi.WifiManager
import android.os.PowerManager

/**
 * Everything that decides whether *now* is a safe moment to move the data SIM.
 *
 * Switching the default data subscription tears down every open connection for a few seconds. That
 * is invisible if the phone is idle and extremely visible if the owner is mid-call. So the rule is:
 * switch when the phone is not actively using mobile data, and never during a call.
 *
 * Since the switch moved to the AccessibilityService it is no longer merely a dropped connection —
 * it visibly opens the SIM manager for two or three seconds. "Not using data" therefore stopped
 * being a sufficient test for "won't be noticed", and [Blocker.APP_IN_USE] covers the rest.
 */
class DeviceState(private val context: Context) {

    /** Why a switch is being held back, or null when it's safe to go. */
    enum class Blocker {
        /** A cellular or VoIP call is up. The hard one — never switch through this. */
        CALL,

        /** Hotspot clients would all drop at once. */
        TETHERING,

        /** Screen on and real traffic flowing — a switch here would be felt. */
        ACTIVE_DATA,

        /** Screen on and audio/video streaming, which may be buffered but is still in use. */
        MEDIA_PLAYING,

        /**
         * Screen on and an actual app in front — Maps, WhatsApp, a text thread.
         *
         * Not about data at all. Switching now opens the SIM manager on top of what the user is
         * looking at, and none of those apps move enough bytes to trip [Blocker.ACTIVE_DATA].
         */
        APP_IN_USE,
    }

    data class Snapshot(
        val screenOn: Boolean,
        val onWifi: Boolean,
        val inCall: Boolean,
        val tethering: Boolean,
        val mediaActive: Boolean,
        val recentKbps: Double,
        val blocker: Blocker?,
        val foregroundApp: String? = null,
        /**
         * Cellular is positively confirmed to be carrying this device's traffic, with no VPN in
         * the path. Only then can the mobile byte counters be attributed to the data SIM.
         *
         * Deliberately *not* the inverse of [onWifi]: see [isOnCellular].
         */
        val onCellular: Boolean = false,
        /** A VPN is in the path, so the mobile byte counters cannot be attributed to a radio. */
        val vpnActive: Boolean = false,
    ) {
        val safeToSwitch: Boolean get() = blocker == null

        val explanation: String
            get() = when (blocker) {
                Blocker.CALL -> "call in progress"
                Blocker.TETHERING -> "hotspot active"
                Blocker.ACTIVE_DATA -> "screen on, using data (%.0f kbps)".format(recentKbps)
                Blocker.MEDIA_PLAYING -> "screen on, media playing"
                Blocker.APP_IN_USE -> "in use (${foregroundApp ?: "an app"})"
                null -> when {
                    !screenOn -> "screen off"
                    onWifi -> "on Wi-Fi, mobile idle"
                    else -> "screen on but idle"
                }
            }
    }

    private val foreground = ForegroundApp(context)

    private var lastBytes = 0L
    private var lastAt = 0L

    fun snapshot(): Snapshot {
        val screenOn = context.getSystemService(PowerManager::class.java)?.isInteractive ?: true
        val audio = context.getSystemService(AudioManager::class.java)

        // MODE_IN_COMMUNICATION is what WhatsApp, Zoom, Teams, Discord and friends set for a VoIP
        // or video call — it is the signal that actually matters here, since a cellular call rides
        // its own subscription and would survive a data switch anyway. Both are treated as blocking
        // because in practice a call means the phone is in active use either way.
        val mode = audio?.mode
        val modeInCall = mode == AudioManager.MODE_IN_COMMUNICATION || mode == AudioManager.MODE_IN_CALL

        // Second, independent signal. Now that switches actually happen, relying on a single
        // indicator for the one thing the owner explicitly asked never to interrupt is too thin —
        // if a VoIP app fails to set the audio mode on this Samsung build we would switch straight
        // through a call. Anything holding the microphone is treated as a call.
        //
        // This is deliberately over-cautious: a voice memo or an assistant also blocks. The cost of
        // a false positive is a delayed switch; the cost of a false negative is a dropped call.
        val micActive = runCatching {
            audio?.activeRecordingConfigurations?.isNotEmpty() == true
        }.getOrDefault(false)

        val inCall = modeInCall || micActive

        val mediaActive = audio?.isMusicActive == true
        val onWifi = isOnWifi()
        val kbps = recentKbps()
        val tethering = isTethering()

        // Only asked for while the screen is on: with the screen off nothing is in the foreground
        // in any sense that matters, and the answer would be a stale package name.
        val front = if (screenOn) foreground.current() else null
        val quiet = !screenOn || foreground.isQuiet()

        return Snapshot(
            screenOn, onWifi, inCall, tethering, mediaActive, kbps,
            blockerFor(screenOn, onWifi, inCall, tethering, mediaActive, kbps, quiet),
            foregroundApp = front,
            onCellular = isOnCellular(),
            vpnActive = isVpnActive(),
        )
    }

    /** Whether the Usage-access grant behind [Blocker.APP_IN_USE] is in place. */
    fun canSeeForegroundApp(): Boolean = foreground.hasAccess()

    /** Short-window mobile throughput, used to tell "idle" from "in use" rather than to score. */
    private fun recentKbps(): Double {
        val bytes = TrafficStats.getMobileRxBytes() + TrafficStats.getMobileTxBytes()
        val now = System.currentTimeMillis()

        if (lastAt == 0L || bytes < lastBytes) {
            lastBytes = bytes; lastAt = now
            return 0.0
        }
        val seconds = (now - lastAt) / 1000.0
        if (seconds < 1.0) return 0.0

        val delta = bytes - lastBytes
        lastBytes = bytes; lastAt = now
        return (delta * 8.0 / 1000.0) / seconds
    }

    private fun activeCaps(): NetworkCapabilities? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        return cm.getNetworkCapabilities(cm.activeNetwork ?: return null)
    }

    private fun isOnWifi(): Boolean =
        activeCaps()?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true

    private fun isVpnActive(): Boolean = activeCaps()?.let {
        it.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ||
            !it.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
    } ?: false

    /**
     * Whether the mobile byte counters can honestly be attributed to the data SIM.
     *
     * This must be a *positive* test for cellular, not `!onWifi`, and that distinction is the whole
     * bug behind the 234 Mbps "measurements" in the learned map. `getActiveNetwork()` returns the
     * **VPN** network when a VPN is up, and a VPN only advertises its underlying transports if it
     * declares them — the owner's does not reliably. So on Wi-Fi behind a VPN `isOnWifi()` answers
     * false, the old `!onWifi` gate opened, and a window of Wi-Fi traffic was recorded as a
     * cellular throughput sample for whichever SIM happened to be the DDS.
     *
     * A VPN is treated as disqualifying even over cellular: the tunnel is double-counted against
     * the mobile interface, so the rate is inflated by an unknown factor rather than merely
     * misattributed. Discarding costs us samples — SINR is the anchor for scoring, not throughput —
     * whereas a wrong sample poisons a place permanently.
     */
    private fun isOnCellular(): Boolean = activeCaps()?.let {
        it.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) &&
            !it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
            it.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
    } ?: false

    /** getWifiApState is hidden API; HiddenApiBypass is already initialised by the switcher. */
    private fun isTethering(): Boolean = runCatching {
        val wm = context.getSystemService(WifiManager::class.java) ?: return false
        val state = wm.javaClass.getMethod("getWifiApState").invoke(wm) as Int
        state == WIFI_AP_STATE_ENABLED
    }.getOrDefault(false)

    companion object {
        /**
         * The interlock rules, as a pure function so they can be tested exhaustively.
         *
         * This is deliberately separate from reading the device: Samsung blocks toggling Wi-Fi from
         * adb, so several of these combinations cannot be produced on the phone on demand. Keeping
         * the decision pure means the rules the owner actually cares about are verified by tests
         * rather than by hoping the right conditions turn up.
         *
         * Order matters. A call outranks everything; screen-off is the ideal window and beats a
         * busy link, because nobody notices a reconnect they can't see.
         *
         * @param foregroundQuiet nothing on screen worth protecting — see [ForegroundApp].
         */
        fun blockerFor(
            screenOn: Boolean,
            onWifi: Boolean,
            inCall: Boolean,
            tethering: Boolean,
            mediaActive: Boolean,
            kbps: Double,
            foregroundQuiet: Boolean = true,
        ): Blocker? = when {
            inCall -> Blocker.CALL
            tethering -> Blocker.TETHERING
            // Screen off is the ideal window: nobody is looking and nothing is interactive.
            !screenOn -> null
            // Ahead of the Wi-Fi bypass on purpose. That bypass reasons about the *link* being
            // idle, which is true and irrelevant: the switch is no longer invisible, so an app in
            // front is disturbed whether or not it is moving bytes. Being on Wi-Fi is exactly when
            // Maps looks idle, which is how it kept getting interrupted.
            !foregroundQuiet -> Blocker.APP_IN_USE
            // On Wi-Fi the mobile link is idle, so moving it costs nothing — and it leaves the
            // phone already on the better SIM for the moment it walks out of Wi-Fi range.
            onWifi -> null
            kbps > BUSY_KBPS -> Blocker.ACTIVE_DATA
            mediaActive -> Blocker.MEDIA_PLAYING
            else -> null
        }

        /**
         * Above this, treat the phone as actively using data.
         *
         * Deliberately well above idle chatter (push keepalives, sync) and below anything a person
         * would notice losing. A VoIP call sits *below* this — around 50-100 kbps — which is
         * exactly why calls are detected through the audio mode instead of throughput.
         */
        const val BUSY_KBPS = 100.0

        private const val WIFI_AP_STATE_ENABLED = 13
    }
}
