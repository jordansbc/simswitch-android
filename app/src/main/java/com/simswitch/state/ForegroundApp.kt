package com.simswitch.state

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Process

/**
 * What app is on screen right now — the missing input to "is this a polite moment to switch".
 *
 * The interlocks in [DeviceState] were designed around an *invisible* switch, back when moving the
 * data SIM was a binder call. It isn't any more: the AccessibilityService opens Samsung's SIM
 * manager over whatever is in front, steals focus for two or three seconds, and drops the user back
 * where they were. Throughput cannot see that coming — Maps, WhatsApp and a text thread all sit
 * well under [DeviceState.BUSY_KBPS], so they read as "screen on but idle" and were switched
 * straight through (2026-09-02 and again 2026-09-03).
 *
 * So the question is no longer "is the link busy" but "is the *person* busy", and the only honest
 * answer to that is which app has focus.
 *
 * **Which apps count is the user's call.** The original rule here was an allowlist — only the
 * launcher and the system shell were quiet, everything else blocked — on the reasoning that a
 * blocklist would be wrong the first time a browser or a bank app turned up. Ten days of device
 * data showed the cost of that: 13 switches against 475 blocks, mostly from apps a two-second
 * interruption does not spoil. [ProtectedApps] now owns the question, and a rescue switch overrides
 * the answer entirely.
 *
 * **Fails open.** Without the Usage-access grant this returns "quiet" and behaviour is exactly what
 * it was before — a missing grant must not silently freeze switching forever. [hasAccess] is
 * surfaced in the UI so the grant can't go unnoticed instead.
 */
class ForegroundApp(private val context: Context) {

    /** Last package seen resuming an activity. Null until the first event turns up. */
    @Volatile
    private var lastForeground: String? = null

    /** End of the window already read, so each poll only asks for what is new. */
    private var queriedThrough = 0L

    private var quietPackages: Set<String>? = null

    private val protectedApps = ProtectedApps(context)

    /**
     * Whether Usage access has been granted.
     *
     * Checked through appops rather than [Context.checkSelfPermission] because PACKAGE_USAGE_STATS
     * is a signature permission that is never "granted" in the ordinary sense — declaring it only
     * makes the app appear in Settings → Special access → Usage data access, and the user's toggle
     * there sets the op.
     */
    @Suppress("DEPRECATION") // The non-deprecated replacements are all @SystemApi.
    fun hasAccess(): Boolean = runCatching {
        val ops = context.getSystemService(AppOpsManager::class.java) ?: return false
        ops.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName,
        ) == AppOpsManager.MODE_ALLOWED
    }.getOrDefault(false)

    /** The package currently in front, or null if it cannot be determined. */
    fun current(): String? {
        if (!hasAccess()) return null
        val usage = context.getSystemService(UsageStatsManager::class.java) ?: return null

        val now = System.currentTimeMillis()
        // First poll looks back far enough to find the app that was already open before the service
        // started; after that, only the sliver since the last poll, with a little overlap so an
        // event landing on the boundary isn't missed.
        val begin = if (queriedThrough == 0L) now - INITIAL_LOOKBACK_MS else queriedThrough - OVERLAP_MS

        runCatching {
            val events = usage.queryEvents(begin, now)
            val event = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                    lastForeground = event.packageName
                }
            }
            queriedThrough = now
        }
        // No events in the window means nothing came to the foreground, i.e. unchanged — not
        // unknown. Holding the last value is the whole reason this is stateful.
        return lastForeground
    }

    /**
     * True when nothing is on screen that a two-second interruption would spoil.
     *
     * Unknown counts as quiet, deliberately: see the fail-open note on the class.
     */
    fun isQuiet(): Boolean {
        val pkg = current() ?: return true
        return pkg in alwaysQuietPackages() || !protectedApps.isProtected(pkg)
    }

    /**
     * The launcher, the system shell and ourselves.
     *
     * Kept even though these would never be on a protected list anyway: it makes "the home screen
     * is quiet" a property of the code rather than of the user's current selection, so a stray tap
     * in the app picker cannot freeze switching on the home screen.
     *
     * The home package is resolved rather than hardcoded — this phone uses Samsung's One UI Home,
     * but a launcher is exactly the kind of thing that gets replaced. SystemUI is included because
     * it owns the notification shade, recents and the moment just after an unlock, which is the
     * single most common instant for the phone to be awake with nothing actually in use.
     */
    private fun alwaysQuietPackages(): Set<String> = quietPackages ?: buildSet {
        add(context.packageName)
        add("com.android.systemui")
        runCatching {
            val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            context.packageManager.queryIntentActivities(home, 0).forEach {
                add(it.activityInfo.packageName)
            }
        }
    }.also { quietPackages = it }

    private companion object {
        const val INITIAL_LOOKBACK_MS = 4 * 60 * 60 * 1000L
        const val OVERLAP_MS = 5 * 1000L
    }
}
