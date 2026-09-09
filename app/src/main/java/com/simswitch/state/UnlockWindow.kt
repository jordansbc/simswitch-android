package com.simswitch.state

/**
 * The rules for acting on the moment an unlock opens up.
 *
 * The 20-second window loop is the wrong instrument for this. An unlock creates a switchable
 * moment that is often **one or two seconds long** — the phone is awake, the keyguard is gone, and
 * the launcher is showing until a finger lands on an icon. A poll that fires on its own schedule
 * almost never lands inside that, which is most of why so little ever switched: on 2026-09-06 the
 * app wanted T-Mobile for 47 consecutive windows during a drive and took the decision 598 times
 * without ever being able to act, every one of them `deferred: screen off`.
 *
 * So the unlock itself drives an immediate evaluation instead of waiting for the next window.
 * Nothing about the *decision* changes — fresh signal, full margin, streak, dwell and every
 * interlock still apply. Only the timing does.
 *
 * **Why this is polled rather than driven by ACTION_USER_PRESENT.** That was the first
 * implementation and it never fired once on this phone (2026-09-06). The receiver registered
 * correctly and appeared in the system's USER_PRESENT filter table, but `dumpsys activity
 * broadcasts` showed it in *no* broadcast record's receiver list: One UI enqueues that broadcast
 * with `deferralPolicy=2` and the records sat pending indefinitely. A trigger that the OEM may
 * defer forever is not a trigger. `isInteractive` and `isKeyguardLocked` are two cheap local
 * calls that nobody can defer, so the lock state is watched directly.
 *
 * The other candidate — noticing the unlock through the AccessibilityService, which is already
 * running and event-driven — was rejected outright. It only receives events for the two telephony
 * packages it is bound to, and widening that would give SimSwitch the ability to read every window
 * on the phone. A slightly better trigger is not worth the broadest permission on Android.
 *
 * The one new hazard is the reason this class exists: **an unlock is not evidence of an idle
 * phone.** The owner unlocks by tapping a notification, which unlocks and opens an app in one motion.
 * For a moment after the keyguard goes the launcher is still what's in front and the tapped app
 * hasn't drawn yet, so a single check taken too early reads "quiet" and interrupts precisely the
 * thing the user just asked for. Two agreeing observations, [SETTLE_MS] and then [CONFIRM_MS]
 * apart, are what separate "unlocked to the home screen" from "unlocked into something".
 */
object UnlockWindow {

    /**
     * How long to let the unlock animation and the tapped app settle before looking.
     *
     * Long enough that the launcher/keyguard transition is over, short enough that the switch still
     * lands inside the quiet moment rather than after it.
     */
    const val SETTLE_MS = 800L

    /** Gap before the confirming look. This is the window a notification-tap has to reveal itself. */
    const val CONFIRM_MS = 1_200L

    /**
     * Unlocks can arrive in bursts — a failed fingerprint, a glance that re-locks, a notification
     * dismissed and the phone locked again. Without a floor, each one queues another pair of
     * checks and the app spends its time waking itself up.
     */
    const val MIN_INTERVAL_MS = 10_000L

    /**
     * How often the lock state is checked while the screen is on.
     *
     * Fast, because this is the whole point: the window being caught can be shorter than the
     * 20-second sampling loop. Both checks are local getters, and the screen being on already
     * costs orders of magnitude more than polling two booleans.
     */
    const val POLL_AWAKE_MS = 1_000L

    /**
     * And while it is off, which is most of the day.
     *
     * A screen-off phone cannot be unlocked without the screen coming on first, so this only has
     * to be fast enough to notice the screen waking — [POLL_AWAKE_MS] takes over from there.
     */
    const val POLL_ASLEEP_MS = 5_000L

    /**
     * The locked → unlocked transition.
     *
     * Starting from "locked" means a service that starts up while the phone is already unlocked
     * does not treat that as an unlock; the 20-second loop covers the steady state anyway.
     */
    fun isUnlockEdge(wasLocked: Boolean, lockedNow: Boolean): Boolean = wasLocked && !lockedNow

    /**
     * Whether the two observations taken after an unlock agree that nothing is in use.
     *
     * Both must be quiet. A first-quiet-then-busy pair is the notification tap — the app opened
     * between the looks, which is exactly the case a single check gets wrong. Busy-then-quiet is
     * someone leaving an app in the 2 seconds after unlocking; declining that costs one deferred
     * switch and the next unlock will offer another chance, while acting on it risks yanking the
     * screen away from someone still moving.
     *
     * [inCall] is checked separately from the blockers so that a call seen in *either* observation
     * vetoes the whole window, rather than being averaged away by the other one.
     */
    fun shouldAct(firstQuiet: Boolean, secondQuiet: Boolean, inCall: Boolean): Boolean =
        !inCall && firstQuiet && secondQuiet

    /** Whether enough time has passed since the last unlock-triggered evaluation to run another. */
    fun mayRun(lastRunAt: Long, now: Long): Boolean =
        lastRunAt == 0L || now - lastRunAt >= MIN_INTERVAL_MS
}
