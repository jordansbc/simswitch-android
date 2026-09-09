package com.simswitch.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import com.simswitch.MainActivity
import com.simswitch.data.PlaceRepository
import com.simswitch.data.SimSwitchDb
import com.simswitch.location.PlaceTracker
import com.simswitch.scoring.Arming
import com.simswitch.scoring.ScoreEngine
import com.simswitch.scoring.SwitchPolicy
import com.simswitch.state.DeviceState
import com.simswitch.state.UnlockWindow
import com.simswitch.switching.AccessibilitySwitcher
import com.simswitch.switching.SimSwitcher
import com.simswitch.telephony.Sim
import com.simswitch.telephony.SubSignal
import com.simswitch.telephony.ThroughputMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Phase 2 foreground service: observes both radios, tracks place, samples real throughput, and
 * writes everything to the learned store.
 *
 * Still switches nothing. Phase 3 adds scoring in shadow mode on top of this data; Phase 4 arms it.
 */
class SimSwitchService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var notifier: Job? = null
    private var recorder: Job? = null

    private lateinit var places: PlaceTracker
    private lateinit var repo: PlaceRepository
    private lateinit var device: DeviceState
    private lateinit var scorer: ScoreEngine
    private val policy = SwitchPolicy()
    private val throughput = ThroughputMonitor()
    private val switcher: SimSwitcher by lazy { AccessibilitySwitcher(this) }

    /** Shown in the notification so a switch is never silent. */
    @Volatile private var lastSwitch: String? = null

    /** Set when a switch lands, so the next window's throughput delta is thrown away. */
    @Volatile private var discardNextThroughput = false

    /**
     * The window loop and the unlock trigger both decide and both switch, so they must not do it
     * at the same time — two overlapping runs would each see the pre-switch DDS and could open the
     * SIM manager twice.
     */
    private val evaluating = Mutex()

    private var unlockWatcher: Job? = null

    /** When the unlock path last ran, for [UnlockWindow.mayRun]. */
    @Volatile private var lastUnlockRunAt = 0L

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Starting…"))

        repo = PlaceRepository(this)
        runCatching { com.simswitch.data.CoverageSeed.loadIfNeeded(this, repo) }
        places = PlaceTracker(this)
        device = DeviceState(this)
        scorer = ScoreEngine(repo)
        val monitor = Sim.monitor(this)

        monitor.start()
        places.start()
        throughput.reset()

        notifier = scope.launch {
            monitor.signals.collectLatest { signals ->
                notificationManager().notify(NOTIFICATION_ID, buildNotification(summarize(signals)))
            }
        }

        recorder = scope.launch { recordLoop() }
        unlockWatcher = scope.launch { unlockWatchLoop() }
    }

    /**
     * Watch for the phone being unlocked.
     *
     * Deliberately a poll of two local getters rather than an ACTION_USER_PRESENT receiver — that
     * was tried first and One UI never delivered it. See [UnlockWindow] for the evidence.
     */
    private suspend fun unlockWatchLoop() {
        val power = getSystemService(android.os.PowerManager::class.java)
        val keyguard = getSystemService(android.app.KeyguardManager::class.java)

        // Seeded from what is true right now, not from `true`. The service restarts on every
        // install and after a reboot, and starting up while the phone happens to be unlocked is
        // not an unlock — seeding optimistically would fire an evaluation on each restart.
        var wasLocked = power?.isInteractive != true || keyguard?.isKeyguardLocked != false

        while (true) {
            val interactive = power?.isInteractive != false
            // A dark screen is locked for our purposes whatever the keyguard says: the switcher
            // cannot drive UI that isn't rendered, and this keeps the asleep path to one call.
            val locked = !interactive || keyguard?.isKeyguardLocked != false

            if (UnlockWindow.isUnlockEdge(wasLocked, locked)) {
                android.util.Log.i(TAG, "unlock detected")
                onUnlock()
            }
            wasLocked = locked

            delay(if (interactive) UnlockWindow.POLL_AWAKE_MS else UnlockWindow.POLL_ASLEEP_MS)
        }
    }

    /**
     * Decide immediately on unlock instead of waiting up to 20 seconds for the next window.
     *
     * This does not replay anything. It runs the same evaluation the loop runs, on signal read
     * now — margin, streak, dwell, rate limit and every interlock still apply, and a decision that
     * would have been HOLD stays HOLD. The only thing it changes is *when* the question is asked,
     * which on this mechanism is the whole game: the quiet moment after an unlock is often over
     * within a couple of seconds.
     *
     * The two spaced observations are the guard against the owner's own case — unlocking by tapping a
     * notification, where the launcher is still in front for a beat before the tapped app draws.
     * See [UnlockWindow].
     */
    private suspend fun onUnlock() {
        val now = System.currentTimeMillis()
        if (!UnlockWindow.mayRun(lastUnlockRunAt, now)) return
        lastUnlockRunAt = now
        if (!Arming.isArmed(this)) return

        delay(UnlockWindow.SETTLE_MS)
        val first = device.snapshot()
        delay(UnlockWindow.CONFIRM_MS)
        val second = device.snapshot()

        if (!UnlockWindow.shouldAct(
                firstQuiet = first.safeToSwitch,
                secondQuiet = second.safeToSwitch,
                inCall = first.inCall || second.inCall,
            )
        ) {
            android.util.Log.i(
                TAG,
                "unlock: not a quiet moment (${first.explanation} → ${second.explanation}), leaving it",
            )
            return
        }

        val signals = Sim.monitor(this).signals.value
        if (signals.size < 2) return
        android.util.Log.i(TAG, "unlock: quiet moment (${second.explanation}) — evaluating now")
        evaluating.withLock { evaluateShadow(signals.values, places.place.value?.key, onUnlock = true) }
    }

    /**
     * Writes one observation per SIM every window.
     *
     * Throughput is attributed only to the subscription actually carrying data — on DSDS the
     * standby radio moves no bytes, so crediting it with the phone's traffic would be fiction.
     * The standby line still gets its radio metrics recorded, which is what the learned map needs
     * in order to have any opinion about it at all.
     */
    private suspend fun recordLoop() {
        var sinceLastPrune = 0
        while (true) {
            delay(WINDOW_MS)

            // The DDS can move without us: the owner flipping it in Settings, or a carrier change.
            // Re-read it every window so throughput lands on the line that actually carried it.
            val dataSubChanged = Sim.monitor(this).refreshDataSub()

            val signals = Sim.monitor(this).signals.value
            if (signals.isEmpty()) continue

            val place = places.place.value
            val state = device.snapshot()

            // Throughput is only meaningful when cellular is actually carrying the traffic. The
            // test is a *positive* one — cellular confirmed, no VPN — rather than "not on Wi-Fi".
            // `!onWifi` was true in every case we could not identify, including on Wi-Fi behind a
            // VPN, and that is how 224 Mbps and 234 Mbps windows of Wi-Fi traffic ended up filed
            // against a radio. See DeviceState.isOnCellular.
            // A window that straddles a DDS change carries bytes from both lines and belongs to
            // neither, so it is dropped rather than guessed at.
            val raw = throughput.sample()
            val settling = discardNextThroughput
            discardNextThroughput = false
            val kbps = raw?.takeIf {
                state.onCellular && !dataSubChanged && !settling && it <= MAX_PLAUSIBLE_KBPS
            }
            if (raw != null && kbps == null) {
                android.util.Log.i(
                    TAG,
                    "discarded throughput sample ${raw.toInt()} kbps " +
                        "(onCellular=${state.onCellular} vpn=${state.vpnActive} " +
                        "onWifi=${state.onWifi} dataSubChanged=$dataSubChanged settling=$settling)"
                )
            }

            signals.values.forEach { s ->
                repo.record(
                    placeKey = place?.key,
                    lat = place?.lat,
                    lon = place?.lon,
                    subId = s.subId,
                    carrier = s.carrier,
                    isDataSub = s.isDataSub,
                    rat = s.rat,
                    bands = s.bands.joinToString(","),
                    rsrp = s.rsrp,
                    sinr = s.sinr,
                    kbps = if (s.isDataSub) kbps else null,
                )
            }

            android.util.Log.i(
                TAG,
                "place=${place?.key ?: "—"} kbps=${kbps?.let { "%.0f".format(it) } ?: "idle"} " +
                    // fg is what decides APP_IN_USE, and it is invisible in the decisions table
                    // while the verdict is HOLD — which is most of the time.
                    "fg=${state.foregroundApp ?: "—"}${state.blocker?.let { "/$it" } ?: ""} " +
                    summarize(signals)
            )

            evaluating.withLock { evaluateShadow(signals.values, place?.key) }

            if (++sinceLastPrune >= PRUNE_EVERY_WINDOWS) {
                sinceLastPrune = 0
                runCatching { repo.prune() }
            }
        }
    }

    /**
     * Score, decide, record — and, when armed, actually switch.
     *
     * The decision path is identical whether armed or not, so what gets logged in shadow mode is
     * exactly what would have happened. Arming only adds the final step.
     */
    private fun evaluateShadow(
        signals: Collection<SubSignal>,
        placeKey: String?,
        onUnlock: Boolean = false,
    ) {
        if (signals.size < 2) return

        val current = android.telephony.SubscriptionManager.getDefaultDataSubscriptionId()
        val scores = scorer.score(signals, placeKey)
        val state = device.snapshot()
        val decision = policy.evaluate(scores, current, state)

        val scoreText = scores.joinToString(" | ") {
            "%s=%.3f(%s)".format(it.carrier, it.total, it.detail)
        }

        var verdict = decision.verdict.name
        var reason = decision.reason
        // Marked in the decisions table so it can be answered later whether the unlock trigger
        // actually earns its keep, rather than assumed.
        if (onUnlock) reason = "$reason [on unlock]"

        if (decision.verdict == SwitchPolicy.Verdict.SWITCH && decision.toSubId != null) {
            val armed = Arming.isArmed(this)
            if (!armed) {
                verdict = "SWITCH_SHADOW"
            } else if (switcher.unavailableReason() != null) {
                // Driving the SIM manager needs the screen on and unlocked, so a sleeping phone
                // simply defers — the normal overnight case, not a fault. Nothing is queued: the
                // next window re-decides once the phone is in use, on fresh signal rather than a
                // stale verdict.
                verdict = "SWITCH_DEFERRED"
                reason = "$reason — deferred: ${switcher.unavailableReason()}"
            } else {
                val result = switcher.switchTo(decision.toSubId)
                if (result.success) {
                    policy.recordSwitch()
                    Sim.monitor(this).refreshDataSub()
                    // Moving the DDS swaps the active mobile interface, and the device-wide
                    // TrafficStats counters jump discontinuously across it. The first window after
                    // a switch therefore measures that discontinuity, not the network — the very
                    // first real switch recorded 403,083 kbps on LTE band 66. Re-baseline the
                    // counters and skip one window. Note refreshDataSub() above already consumed
                    // the DDS change, so recordLoop's dataSubChanged check cannot catch this on
                    // its own; this flag is what covers a switch we performed ourselves.
                    throughput.reset()
                    discardNextThroughput = true
                    verdict = "SWITCHED"
                    lastSwitch = "${timeNow()} → ${scores.firstOrNull { it.subId == decision.toSubId }?.carrier}"
                } else {
                    // Without this the streak survives and the next window retries 20s later,
                    // reopening the SIM manager over whatever is on screen. See recordSwitchFailure.
                    policy.recordSwitchFailure()
                    verdict = "SWITCH_FAILED"
                }
                reason = "$reason [${result.detail}]"
            }
        }

        runCatching {
            repo.recordDecision(
                placeKey = placeKey,
                verdict = verdict,
                fromSub = decision.fromSubId,
                toSub = decision.toSubId,
                margin = decision.margin,
                streak = decision.streak,
                blocker = decision.blocker?.name,
                urgent = decision.urgent,
                reason = reason,
                scores = scoreText,
            )
        }

        // Only the interesting verdicts reach logcat; HOLD is the overwhelming majority and would
        // bury everything else.
        if (decision.verdict != SwitchPolicy.Verdict.HOLD) {
            android.util.Log.i(TAG, "[$verdict] $reason  {$scoreText}")
        }
    }

    private fun timeNow(): String =
        java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date())

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        unlockWatcher?.cancel()
        notifier?.cancel()
        recorder?.cancel()
        scope.cancel()
        places.stop()
        Sim.monitor(this).stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ------------------------------------------------------------ notification

    /** e.g. "SIM A ● NR -92/18  |  the MVNO NR -104/6" — the ● marks the data SIM. */
    private fun summarize(signals: Map<Int, SubSignal>): String {
        if (signals.isEmpty()) return "No subscriptions visible"
        return signals.values
            .sortedBy { it.slot }
            .joinToString("  |  ") { s ->
                val marker = if (s.isDataSub) " ●" else ""
                "${s.carrier}$marker ${s.rat} ${s.rsrp ?: "–"}/${s.sinr ?: "–"}"
            }
    }

    private fun buildNotification(text: String): Notification {
        val tap = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val armed = Arming.isArmed(this)
        val title = if (armed) "SimSwitch — active" else "SimSwitch — watching only"
        val body = lastSwitch?.let { "$text\nlast switch: $it" } ?: text

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(tap)
            .setOngoing(true)
            .build()
    }

    private fun createChannel() {
        // Low importance: this notification exists because a foreground service requires one,
        // not because it wants attention.
        notificationManager().createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "SimSwitch status", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "Ongoing dual-SIM radio monitoring" }
        )
    }

    private fun notificationManager() = getSystemService(NotificationManager::class.java)

    companion object {
        private const val TAG = "SimSwitch"
        private const val CHANNEL_ID = "simswitch.status"
        private const val NOTIFICATION_ID = 1

        /** 20s: fast enough to catch a dead spot while driving, slow enough to be cheap. */
        private const val WINDOW_MS = 20_000L

        /** Shared with the repair migration so ingest and repair cannot disagree. */
        private const val MAX_PLAUSIBLE_KBPS = SimSwitchDb.MAX_PLAUSIBLE_KBPS
        private const val PRUNE_EVERY_WINDOWS = 180 // ~1 hour

        fun start(context: Context) =
            context.startForegroundService(Intent(context, SimSwitchService::class.java))

        fun stop(context: Context) =
            context.stopService(Intent(context, SimSwitchService::class.java))
    }
}
