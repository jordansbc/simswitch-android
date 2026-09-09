package com.simswitch.scoring

import com.simswitch.state.DeviceState

/**
 * Decides whether to actually move the data SIM.
 *
 * In Phase 3 this runs in **shadow mode**: it reaches a verdict and logs it, but nothing acts on
 * the result. The point is to check its calls against reality — on a drive, at home, overnight —
 * before handing it the ability to interrupt a connection.
 */
class SwitchPolicy {

    enum class Verdict {
        /** Current SIM is fine. */
        HOLD,

        /** Another SIM is better and everything says go. In Phase 4 this becomes a real switch. */
        SWITCH,

        /** Another SIM is better but the moment is wrong — see [Decision.blocker]. */
        BLOCKED,

        /** Better, but not yet convincingly or not for long enough. */
        WAITING,
    }

    data class Decision(
        val verdict: Verdict,
        val fromSubId: Int,
        val toSubId: Int?,
        val margin: Double,
        val streak: Int,
        val blocker: DeviceState.Blocker?,
        val urgent: Boolean,
        val reason: String,
    )

    private var streak = 0
    private var streakTarget: Int? = null
    private var lastSwitchAt = 0L

    /** Consecutive failed switch attempts, and the instant we may next try one. */
    private var consecutiveFailures = 0
    private var failureBackoffUntil = 0L
    private val recentSwitches = ArrayDeque<Long>()

    fun evaluate(
        scores: List<ScoreEngine.SubScore>,
        currentSubId: Int,
        device: DeviceState.Snapshot,
        now: Long = System.currentTimeMillis(),
    ): Decision {
        val current = scores.firstOrNull { it.subId == currentSubId }
        val best = scores.firstOrNull()

        if (current == null || best == null) {
            return hold(currentSubId, "no scores available")
        }
        if (best.subId == currentSubId) {
            resetStreak()
            return hold(currentSubId, "already on the best line")
        }

        val margin = best.total - current.total

        // The one case worth interrupting for: the line we're on has no service at all. Waiting out
        // a three-sample streak while the phone has no data would be perverse.
        val urgent = !current.inService && best.inService

        if (!urgent && margin < MIN_MARGIN) {
            resetStreak()
            return hold(currentSubId, "%s leads by only %.3f (need %.2f)".format(best.carrier, margin, MIN_MARGIN))
        }

        // Require the same challenger to win repeatedly. A single sample can be a momentary fade;
        // three in a row across a minute is a real change in conditions.
        if (streakTarget != best.subId) {
            streakTarget = best.subId
            streak = 0
        }
        streak++

        if (!urgent && streak < REQUIRED_STREAK) {
            return Decision(
                Verdict.WAITING, currentSubId, best.subId, margin, streak, device.blocker, false,
                "%s ahead by %.3f, %d/%d consecutive".format(best.carrier, margin, streak, REQUIRED_STREAK),
            )
        }

        // A mechanism that just failed will almost certainly fail again, and on the accessibility
        // path every attempt opens the SIM manager over whatever the user is doing. This gate
        // applies even when urgent: hammering a broken switcher helps nobody, and doing it on top
        // of a live app is actively hostile.
        if (now < failureBackoffUntil) {
            return Decision(
                Verdict.WAITING, currentSubId, best.subId, margin, streak, device.blocker, urgent,
                "backing off after $consecutiveFailures failed attempt(s): " +
                    "${(failureBackoffUntil - now) / 1000}s left",
            )
        }

        // Don't thrash. Both of these are about the cost of switching, so they apply even when the
        // challenger is clearly better — but not when we currently have no service at all.
        if (!urgent) {
            val sinceSwitch = now - lastSwitchAt
            if (lastSwitchAt != 0L && sinceSwitch < MIN_DWELL_MS) {
                return Decision(
                    Verdict.WAITING, currentSubId, best.subId, margin, streak, device.blocker, false,
                    "dwell: only ${sinceSwitch / 1000}s since last switch (need ${MIN_DWELL_MS / 1000}s)",
                )
            }
            pruneSwitchWindow(now)
            if (recentSwitches.size >= MAX_SWITCHES_PER_HOUR) {
                return Decision(
                    Verdict.WAITING, currentSubId, best.subId, margin, streak, device.blocker, false,
                    "rate limit: ${recentSwitches.size} switches in the last hour",
                )
            }
        }

        // the owner's rule: never mid-call, and not while the screen is on and data is in use.
        // Checked last so the log still shows how close it came, which is what makes the blocker
        // counts meaningful when tuning.
        device.blocker?.let { blocker ->
            // The one blocker urgency may override. APP_IN_USE protects attention, not a working
            // connection — and if the line we are on has no service at all, the app in front is
            // already broken. Interrupting Maps for two seconds beats leaving it with no data.
            // Every other blocker still stands: a call is never worth interrupting, urgent or not.
            if (!(urgent && blocker == DeviceState.Blocker.APP_IN_USE)) {
                return Decision(
                    Verdict.BLOCKED, currentSubId, best.subId, margin, streak, blocker, urgent,
                    "would switch to ${best.carrier} but ${device.explanation}",
                )
            }
        }

        return Decision(
            Verdict.SWITCH, currentSubId, best.subId, margin, streak, null, urgent,
            if (urgent) "${current.carrier} has no service — switching to ${best.carrier} (${device.explanation})"
            else "%s better by %.3f for %d windows, %s".format(best.carrier, margin, streak, device.explanation),
        )
    }

    /** Phase 4 calls this after a switch actually lands, so dwell and rate limiting mean something. */
    fun recordSwitch(now: Long = System.currentTimeMillis()) {
        lastSwitchAt = now
        recentSwitches.addLast(now)
        pruneSwitchWindow(now)
        resetStreak()
        consecutiveFailures = 0
        failureBackoffUntil = 0L
    }

    /**
     * A switch was attempted and did not land.
     *
     * Failures need their own brake, and learning that cost a real afternoon. [recordSwitch] runs
     * only on success, so a failed attempt used to leave dwell, the hourly cap and the streak all
     * untouched — and the next window, 20 seconds later, tried again, indefinitely. While the
     * switch was an invisible binder call that merely wasted cycles. On the accessibility path each
     * attempt opens the SIM manager on top of whatever is on screen, so one wrong assumption about
     * a UI label turned into ten interruptions of Maps and YouTube in an afternoon (2026-09-02).
     *
     * Exponential, capped, and cleared by the next success.
     */
    fun recordSwitchFailure(now: Long = System.currentTimeMillis()) {
        consecutiveFailures++
        val step = (consecutiveFailures - 1).coerceIn(0, 4)
        failureBackoffUntil = now + minOf(FAILURE_BACKOFF_BASE_MS shl step, FAILURE_BACKOFF_MAX_MS)
        resetStreak()
    }

    private fun pruneSwitchWindow(now: Long) {
        while (recentSwitches.isNotEmpty() && now - recentSwitches.first() > HOUR_MS) {
            recentSwitches.removeFirst()
        }
    }

    private fun resetStreak() {
        streak = 0
        streakTarget = null
    }

    private fun hold(currentSubId: Int, reason: String) =
        Decision(Verdict.HOLD, currentSubId, null, 0.0, 0, null, false, reason)

    /** Visible so the tests can assert against the real thresholds rather than copies of them. */
    companion object {
        /** Scores are 0..1, so this is "meaningfully better", not "better by a rounding error". */
        const val MIN_MARGIN = 0.12
        const val REQUIRED_STREAK = 3
        const val MIN_DWELL_MS = 5 * 60 * 1000L
        const val MAX_SWITCHES_PER_HOUR = 4

        /** 1, 2, 4, 8, then 15 minutes between retries after repeated failures. */
        const val FAILURE_BACKOFF_BASE_MS = 60 * 1000L
        const val FAILURE_BACKOFF_MAX_MS = 15 * 60 * 1000L
        const val HOUR_MS = 60 * 60 * 1000L
    }
}
