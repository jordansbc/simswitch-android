package com.simswitch.scoring

import com.simswitch.state.DeviceState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The anti-thrash rules. A switch drops every open connection for a few seconds, so the bar to
 * take one has to be high and the reasons for refusing have to be exact.
 */
class SwitchPolicyTest {

    private val simA = 7
    private val simB = 6

    private fun scores(a: Double, b: Double, simAInService: Boolean = true) = listOf(
        ScoreEngine.SubScore(simA, "SIM A", a, a, 0.0, 0.0, simAInService, ""),
        ScoreEngine.SubScore(simB, "SIM B", b, b, 0.0, 0.0, true, ""),
    ).sortedByDescending { it.total }

    private fun state(blocker: DeviceState.Blocker? = null) = DeviceState.Snapshot(
        screenOn = false, onWifi = false, inCall = blocker == DeviceState.Blocker.CALL,
        tethering = false, mediaActive = false, recentKbps = 0.0, blocker = blocker,
    )

    @Test
    fun `holds when already on the best line`() {
        val d = SwitchPolicy().evaluate(scores(0.9, 0.4), simA, state())
        assertEquals(SwitchPolicy.Verdict.HOLD, d.verdict)
    }

    @Test
    fun `holds when the challenger only wins by a hair`() {
        // Scores wobble sample to sample; a tiny lead is noise, not a reason to drop connections.
        val d = SwitchPolicy().evaluate(scores(0.50, 0.55), simA, state())
        assertEquals(SwitchPolicy.Verdict.HOLD, d.verdict)
    }

    @Test
    fun `requires a streak before switching`() {
        val policy = SwitchPolicy()
        val s = scores(0.40, 0.80)
        assertEquals(SwitchPolicy.Verdict.WAITING, policy.evaluate(s, simA, state()).verdict)
        assertEquals(SwitchPolicy.Verdict.WAITING, policy.evaluate(s, simA, state()).verdict)
        assertEquals(SwitchPolicy.Verdict.SWITCH, policy.evaluate(s, simA, state()).verdict)
    }

    @Test
    fun `a challenger that keeps changing never builds a streak`() {
        val policy = SwitchPolicy()
        repeat(5) {
            policy.evaluate(scores(0.40, 0.80), simA, state())
            // Same margin, other direction: the streak must reset rather than accumulate.
            policy.evaluate(scores(0.80, 0.40), simB, state())
        }
        assertEquals(SwitchPolicy.Verdict.WAITING, policy.evaluate(scores(0.40, 0.80), simA, state()).verdict)
    }

    @Test
    fun `blocked when the moment is wrong even with a full streak`() {
        val policy = SwitchPolicy()
        val s = scores(0.40, 0.80)
        repeat(2) { policy.evaluate(s, simA, state()) }
        val d = policy.evaluate(s, simA, state(DeviceState.Blocker.CALL))
        assertEquals(SwitchPolicy.Verdict.BLOCKED, d.verdict)
        assertEquals(DeviceState.Blocker.CALL, d.blocker)
    }

    @Test
    fun `loss of service switches immediately without waiting for a streak`() {
        val d = SwitchPolicy().evaluate(scores(0.0, 0.5, simAInService = false), simA, state())
        assertEquals(SwitchPolicy.Verdict.SWITCH, d.verdict)
        assertEquals(true, d.urgent)
    }

    @Test
    fun `loss of service still respects a call`() {
        // Even with no service, tearing down a call in progress is worse than the dead link the
        // call is presumably already suffering.
        val d = SwitchPolicy().evaluate(
            scores(0.0, 0.5, simAInService = false), simA, state(DeviceState.Blocker.CALL),
        )
        assertEquals(SwitchPolicy.Verdict.BLOCKED, d.verdict)
    }

    @Test
    fun `an app in the foreground blocks an ordinary switch`() {
        val policy = SwitchPolicy()
        val s = scores(0.40, 0.80)
        repeat(2) { policy.evaluate(s, simA, state()) }
        val d = policy.evaluate(s, simA, state(DeviceState.Blocker.APP_IN_USE))
        assertEquals(SwitchPolicy.Verdict.BLOCKED, d.verdict)
        assertEquals(DeviceState.Blocker.APP_IN_USE, d.blocker)
    }

    @Test
    fun `loss of service switches through an app in the foreground`() {
        // The one blocker urgency overrides. APP_IN_USE protects attention, not a connection —
        // and with no service at all the app in front is already broken, so two seconds of SIM
        // manager is the cheaper of the two interruptions.
        val d = SwitchPolicy().evaluate(
            scores(0.0, 0.5, simAInService = false), simA, state(DeviceState.Blocker.APP_IN_USE),
        )
        assertEquals(SwitchPolicy.Verdict.SWITCH, d.verdict)
        assertEquals(true, d.urgent)
    }

    @Test
    fun `dwell time prevents an immediate second switch`() {
        val policy = SwitchPolicy()
        val s = scores(0.40, 0.80)
        repeat(3) { policy.evaluate(s, simA, state()) }
        policy.recordSwitch()
        repeat(3) { policy.evaluate(s, simA, state()) }
        assertEquals(SwitchPolicy.Verdict.WAITING, policy.evaluate(s, simA, state()).verdict)
    }

    @Test
    fun `rate limit stops flapping across the hour`() {
        val policy = SwitchPolicy()
        val s = scores(0.40, 0.80)
        val now = 1_000_000L
        // Four switches spread far enough apart to clear dwell, then a fifth must be refused.
        repeat(4) { i -> policy.recordSwitch(now + i * 10 * 60_000L) }
        val later = now + 45 * 60_000L
        repeat(3) { policy.evaluate(s, simA, state(), later) }
        assertEquals(SwitchPolicy.Verdict.WAITING, policy.evaluate(s, simA, state(), later).verdict)
    }

    // ---- failure backoff -----------------------------------------------------------------------
    // These cover the 2026-09-02 regression: a failed switch left dwell, the hourly cap and the
    // streak untouched, so the next window retried 20s later. Harmless when the switch was an
    // invisible binder call; on the accessibility path it opened the SIM manager on top of Maps
    // ten times in an afternoon.

    private fun switchNow(policy: SwitchPolicy, now: Long): SwitchPolicy.Decision {
        val s = scores(0.40, 0.80)
        repeat(2) { policy.evaluate(s, simA, state(), now) }
        return policy.evaluate(s, simA, state(), now)
    }

    @Test
    fun `a failed switch is not retried on the very next window`() {
        val policy = SwitchPolicy()
        val t0 = 1_000_000L
        assertEquals(SwitchPolicy.Verdict.SWITCH, switchNow(policy, t0).verdict)

        policy.recordSwitchFailure(t0)

        val next = switchNow(policy, t0 + 20_000)
        assertEquals(SwitchPolicy.Verdict.WAITING, next.verdict)
        assertTrue(next.reason, next.reason.contains("backing off"))
    }

    @Test
    fun `backoff expires and the switch is retried`() {
        val policy = SwitchPolicy()
        val t0 = 1_000_000L
        switchNow(policy, t0)
        policy.recordSwitchFailure(t0)

        val after = switchNow(policy, t0 + SwitchPolicy.FAILURE_BACKOFF_BASE_MS + 1)
        assertEquals(SwitchPolicy.Verdict.SWITCH, after.verdict)
    }

    @Test
    fun `repeated failures back off further, up to the cap`() {
        val policy = SwitchPolicy()
        val t0 = 1_000_000L
        repeat(8) { policy.recordSwitchFailure(t0) }

        // Still waiting just under the cap...
        val justUnder = switchNow(policy, t0 + SwitchPolicy.FAILURE_BACKOFF_MAX_MS - 1)
        assertEquals(SwitchPolicy.Verdict.WAITING, justUnder.verdict)

        // ...and never longer than the cap, however many times it failed.
        val past = switchNow(policy, t0 + SwitchPolicy.FAILURE_BACKOFF_MAX_MS + 1)
        assertEquals(SwitchPolicy.Verdict.SWITCH, past.verdict)
    }

    @Test
    fun `backoff applies even to an urgent switch`() {
        // Urgent means the current line has no service. Retrying a broken switcher every 20s still
        // helps nobody, and doing it over a live app is worse than waiting.
        val policy = SwitchPolicy()
        val t0 = 1_000_000L
        policy.recordSwitchFailure(t0)

        val d = policy.evaluate(scores(0.40, 0.80, simAInService = false), simA, state(), t0 + 20_000)
        assertEquals(SwitchPolicy.Verdict.WAITING, d.verdict)
        assertTrue(d.reason, d.reason.contains("backing off"))
    }

    @Test
    fun `a successful switch clears the backoff`() {
        val policy = SwitchPolicy()
        val t0 = 1_000_000L
        repeat(3) { policy.recordSwitchFailure(t0) }
        policy.recordSwitch(t0)

        // Only dwell should stand in the way now, not the failure backoff.
        val d = switchNow(policy, t0 + SwitchPolicy.MIN_DWELL_MS + 1)
        assertEquals(SwitchPolicy.Verdict.SWITCH, d.verdict)
    }
}
