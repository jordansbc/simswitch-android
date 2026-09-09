package com.simswitch.switching

import android.app.KeyguardManager
import android.content.ComponentName
import android.content.Context
import android.os.PowerManager
import android.provider.Settings
import android.telephony.SubscriptionManager

/**
 * The [SimSwitcher] the service actually uses, wrapping [SwitchAccessibilityService].
 *
 * This class owns the one real concession of the accessibility approach: UI can only be driven when
 * it is rendered and unlocked. A locked or sleeping phone therefore cannot be switched, and saying
 * so plainly here is the whole job — [unavailableReason] is written to the decisions log verbatim,
 * so a drive that did not switch can always be explained afterwards.
 *
 * There is deliberately **no pending-switch queue**. A deferred decision would be stale by the time
 * the phone was unlocked: whatever is true at unlock wins, streak and dwell rules included.
 * Deferral is therefore just "decline now, and let the next evaluation decide".
 *
 * What changed on 2026-09-06 is *when* that next evaluation happens. Waiting for the 20-second
 * window meant the quiet moment an unlock opens — often only a second or two before a finger lands
 * on an app — was nearly always missed, which is why so few deferrals ever turned into switches.
 * The service now evaluates on ACTION_USER_PRESENT as well; still no replay, just a far better
 * question time. See [com.simswitch.state.UnlockWindow].
 */
class AccessibilitySwitcher(private val context: Context) : SimSwitcher {

    override fun unavailableReason(): String? {
        if (SwitchAccessibilityService.instance == null) {
            return if (isEnabledInSettings()) {
                "accessibility service enabled but not connected yet"
            } else {
                "accessibility service is off — turn on SimSwitch in Settings → Accessibility"
            }
        }
        if (context.getSystemService(PowerManager::class.java)?.isInteractive != true) {
            return "screen off"
        }
        if (context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked != false) {
            return "phone locked"
        }
        return null
    }

    override fun switchTo(subId: Int): SimSwitcher.Result {
        unavailableReason()?.let { return SimSwitcher.Result(false, it) }
        val service = SwitchAccessibilityService.instance
            ?: return SimSwitcher.Result(false, "accessibility service disconnected mid-switch")

        val labels = labelsBySub()
        val target = labels[subId].orEmpty()
        if (target.isEmpty()) {
            return SimSwitcher.Result(false, "no SIM name known for sub $subId, cannot identify it")
        }
        val other = labels.filterKeys { it != subId }.values.flatten().toSet()

        return service.performSwitch(subId, target, other)
    }

    /**
     * Every name each subscription could be listed under.
     *
     * The SIM manager shows the user's own nickname ("Line 1"), while the app scores by carrier
     * name ("SIM A") — they are different strings for the same line, and on this phone the
     * picker uses the nickname. Collecting both and letting the matcher subtract the *other* SIM's
     * names is what keeps this correct without hardcoding either.
     */
    private fun labelsBySub(): Map<Int, Set<String>> {
        val manager = context.getSystemService(SubscriptionManager::class.java)
            ?: return emptyMap()
        val active = runCatching { manager.activeSubscriptionInfoList }.getOrNull().orEmpty()
        return active.associate { info ->
            info.subscriptionId to setOfNotNull(
                info.displayName?.toString()?.trim()?.takeIf(String::isNotEmpty),
                info.carrierName?.toString()?.trim()?.takeIf(String::isNotEmpty),
            )
        }
    }

    /** Whether the user has granted the service, independent of whether it has bound yet. */
    fun isEnabledInSettings(): Boolean {
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty()
        val component = ComponentName(context, SwitchAccessibilityService::class.java)
        return enabled.split(':').any {
            ComponentName.unflattenFromString(it) == component
        }
    }
}
