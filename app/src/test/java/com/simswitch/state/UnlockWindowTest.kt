package com.simswitch.state

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * the owner's rule for the unlock trigger:
 *
 *   "Yes, try and do the instant the phone unlocks (unless I am answering a call, or clicking on a
 *    notification to open the phone)"
 *
 * The call half is already covered by [DeviceState.Blocker.CALL] and tested in [DeviceStateTest];
 * what is new here is the notification tap, which is a *timing* problem rather than a rule. For a
 * beat after the keyguard goes, the launcher is still in front and the tapped app has not drawn —
 * so the observation that decides this cannot be a single one.
 */
class UnlockWindowTest {

    @Test
    fun `unlocking to a quiet home screen is a switchable moment`() {
        assertTrue(UnlockWindow.shouldAct(firstQuiet = true, secondQuiet = true, inCall = false))
    }

    @Test
    fun `tapping a notification is not`() {
        // The exact race this exists for: quiet at 800ms because the launcher is still up, busy at
        // 2000ms because the tapped app has drawn. A single early check would have switched.
        assertFalse(UnlockWindow.shouldAct(firstQuiet = true, secondQuiet = false, inCall = false))
    }

    @Test
    fun `leaving an app just after unlocking is declined too`() {
        // Busy then quiet. Someone is still moving; the cost of waiting is one deferred switch and
        // the next unlock offers another chance.
        assertFalse(UnlockWindow.shouldAct(firstQuiet = false, secondQuiet = true, inCall = false))
    }

    @Test
    fun `a call vetoes the window even if the screen looks quiet`() {
        // Unlocking to answer, or unlocking during a call that is already up: the phone can look
        // idle — a VoIP call moves under 100 kbps and may sit on the launcher — and must not be
        // touched anyway.
        assertFalse(UnlockWindow.shouldAct(firstQuiet = true, secondQuiet = true, inCall = true))
    }

    @Test
    fun `the first unlock always runs`() {
        assertTrue(UnlockWindow.mayRun(lastRunAt = 0L, now = 1_000L))
    }

    @Test
    fun `a burst of unlocks only evaluates once`() {
        val at = 100_000L
        assertFalse(UnlockWindow.mayRun(lastRunAt = at, now = at + 1_000))
        assertFalse(UnlockWindow.mayRun(lastRunAt = at, now = at + UnlockWindow.MIN_INTERVAL_MS - 1))
    }

    @Test
    fun `a later unlock runs again`() {
        val at = 100_000L
        assertTrue(UnlockWindow.mayRun(lastRunAt = at, now = at + UnlockWindow.MIN_INTERVAL_MS))
    }

    @Test
    fun `only the locked to unlocked transition counts`() {
        assertTrue(UnlockWindow.isUnlockEdge(wasLocked = true, lockedNow = false))
        // Already unlocked and staying that way is not an unlock — otherwise the poll would fire
        // every second for as long as the phone was in use.
        assertFalse(UnlockWindow.isUnlockEdge(wasLocked = false, lockedNow = false))
        assertFalse(UnlockWindow.isUnlockEdge(wasLocked = true, lockedNow = true))
        assertFalse(UnlockWindow.isUnlockEdge(wasLocked = false, lockedNow = true))
    }

    @Test
    fun `the awake poll is fast enough to catch a short window`() {
        assertTrue(UnlockWindow.POLL_AWAKE_MS < UnlockWindow.SETTLE_MS + UnlockWindow.CONFIRM_MS)
        assertTrue(UnlockWindow.POLL_ASLEEP_MS > UnlockWindow.POLL_AWAKE_MS)
    }

    @Test
    fun `the confirming look lands after the settle`() {
        // Both delays are additive in the service, so the second observation is taken 2s after the
        // unlock. Fast enough to still be inside the quiet moment; slow enough for a tapped app.
        assertTrue(UnlockWindow.SETTLE_MS > 0)
        assertTrue(UnlockWindow.CONFIRM_MS > 0)
        assertTrue(UnlockWindow.SETTLE_MS + UnlockWindow.CONFIRM_MS < UnlockWindow.MIN_INTERVAL_MS)
    }
}
