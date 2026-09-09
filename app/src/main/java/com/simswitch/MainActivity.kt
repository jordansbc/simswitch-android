package com.simswitch

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.WindowInsets
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.simswitch.data.PlaceRepository
import com.simswitch.scoring.Arming
import com.simswitch.service.SimSwitchService
import com.simswitch.state.ForegroundApp
import com.simswitch.state.ProtectedApps
import com.simswitch.telephony.BandTier
import com.simswitch.telephony.Sim
import com.simswitch.telephony.SubSignal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Phase 1 dashboard: live radio metrics for both SIMs, side by side, updating as they change.
 *
 * Plain Views on purpose — this is a personal diagnostic tool and a readout is all it needs.
 * The reason it exists is to answer one question by inspection: does the standby SIM report
 * anything useful, or are its fields permanently blank?
 */
class MainActivity : Activity() {

    private lateinit var body: TextView
    private var usageButton: Button? = null
    private var overlayButton: Button? = null
    private var protectedButton: Button? = null
    private var scope: CoroutineScope? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        body = TextView(this).apply {
            setTypeface(Typeface.MONOSPACE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextIsSelectable(true)
        }

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 48, 32, 48)
            addView(TextView(this@MainActivity).apply {
                text = "SimSwitch — dual-SIM data switching"
                setTypeface(Typeface.DEFAULT_BOLD)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                setPadding(0, 0, 0, 24)
            })
            addView(armButton())
            addView(usageAccessButton())
            addView(overlayAccessButton())
            addView(protectedAppsButton())
            addView(button("Start monitoring") {
                ensurePermissions()
                SimSwitchService.start(this@MainActivity)
            })
            addView(button("Stop monitoring") { SimSwitchService.stop(this@MainActivity) })
            addView(body)
        }

        // targetSdk 36 means Android enforces edge-to-edge: the layout starts at y=0, behind the
        // action bar and status bar. Without this the header and the arm button are drawn under
        // the title bar and cannot be scrolled into view at all — the ScrollView is already at the
        // top, so they are simply unreachable. Hide the redundant action bar (the header below
        // already names the app) and inset the content past the system bars.
        actionBar?.hide()

        val root = ScrollView(this).apply { addView(column) }
        setContentView(root, LinearLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        root.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            v.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }

        // Opening the app is a clear enough signal to start observing. Nothing is switched, so
        // there is no reason to make the owner tap twice every time.
        if (ensurePermissions()) SimSwitchService.start(this)
    }

    override fun onStart() {
        super.onStart()
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        scope = s
        s.launch {
            Sim.monitor(this@MainActivity).signals.collectLatest { render(it) }
        }
    }

    override fun onResume() {
        super.onResume()
        // Granted in Settings, so the state can only have changed while we were away.
        usageButton?.text = usageAccessLabel()
        overlayButton?.text = overlayAccessLabel()
        protectedButton?.text = protectedAppsLabel()
    }

    override fun onStop() {
        scope?.cancel()
        scope = null
        super.onStop()
    }

    // ---------------------------------------------------------------- render

    private fun render(signals: Map<Int, SubSignal>) {
        if (signals.isEmpty()) {
            body.text = "No subscriptions yet.\n\nGrant permissions and tap Start monitoring."
            return
        }
        val now = System.currentTimeMillis()
        body.text = buildString {
            appendLine(learnedSection())
            appendLine()
            signals.values.sortedBy { it.slot }.forEach { s ->
                appendLine("${s.carrier}${if (s.isDataSub) "   ← DATA" else ""}")
                appendLine("  subId/slot : ${s.subId} / ${s.slot}")
                appendLine("  service    : ${if (s.inService) "in service" else "OUT OF SERVICE"}")
                appendLine("  network    : ${s.rat}${bandText(s)}")
                appendLine("  RSRP       : ${s.rsrp?.let { "$it dBm" } ?: "—"}")
                appendLine("  RSRQ       : ${s.rsrq?.let { "$it dB" } ?: "—"}")
                appendLine("  SINR       : ${s.sinr?.let { "$it dB" } ?: "—"}")
                appendLine("  bars       : ${s.level}/4")
                appendLine("  updated    : ${ageText(s, now)}")
                appendLine()
            }

            // Phase 1's actual finding. If the standby line never fills in, the Phase 3 scoring
            // engine cannot lean on live radio for it and must weight learned history far higher.
            val standby = signals.values.filterNot { it.isDataSub }
            appendLine("— standby SIM reporting —")
            if (standby.isEmpty()) {
                appendLine("  (only one subscription active)")
            } else {
                standby.forEach {
                    appendLine(
                        if (it.hasData) "  ${it.carrier}: REPORTING (rsrp=${it.rsrp} sinr=${it.sinr})"
                        else "  ${it.carrier}: no metrics yet — if this never fills in, scoring must rely on learned history"
                    )
                }
            }
        }
    }

    /**
     * What the learned map currently believes. Places are grouped so each one shows the two SIMs
     * side by side with a winner, since "which SIM wins here" is the only question that matters.
     */
    private fun learnedSection(): String = buildString {
        val repo = PlaceRepository(this@MainActivity)
        val (placeCount, sampleCount, seedCount) = repo.counts()

        appendLine("— shadow mode (deciding, not switching) —")
        val verdicts = repo.decisionSummary()
        if (verdicts.isEmpty()) {
            appendLine("  no decisions yet")
        } else {
            appendLine("  " + verdicts.entries.joinToString("  ") { "${it.key}=${it.value}" })
            val blockers = repo.blockerSummary()
            if (blockers.isNotEmpty()) {
                appendLine("  held back by: " + blockers.entries.joinToString("  ") { "${it.key}=${it.value}" })
            }
            repo.recentDecisions(4).forEach { d ->
                if (d.verdict != "HOLD") appendLine("  • ${d.verdict}: ${d.reason}")
            }
        }
        appendLine()

        appendLine("— learned so far —")
        appendLine("  places: $placeCount   samples: $sampleCount   seeded: $seedCount")

        val byPlace = repo.topPlaces(60).groupBy { it.placeKey }
        if (byPlace.isEmpty()) {
            appendLine("  (nothing yet — needs location fixes and mobile traffic)")
            return@buildString
        }

        byPlace.entries.take(8).forEach { (key, stats) ->
            val best = stats.filter { it.kbps != null }.maxByOrNull { it.kbps!! }
            val verdict = when {
                best == null -> "no throughput measured yet"
                stats.count { it.kbps != null } < 2 -> "only ${best.carrier} measured here"
                else -> "${best.carrier} leads (${"%.1f".format(best.kbps!! / 1000)} Mbps)"
            }
            appendLine("  $key — $verdict")
            stats.sortedBy { it.subId }.forEach { s ->
                val kbps = s.kbps?.let { "%.1f Mbps".format(it / 1000) } ?: "—"
                val sinr = s.sinr?.let { "sinr %.0f".format(it) } ?: "sinr —"
                val tag = if (s.isSeed) " [seed]" else ""
                appendLine("      ${s.carrier.padEnd(10)} $kbps  $sinr  n=${s.samples}$tag")
            }
        }
    }

    private fun bandText(s: SubSignal): String = when {
        s.bands.isEmpty() -> ""
        else -> "  bands=${s.bands.joinToString(",")} (${tierLabel(s.bandTier)})"
    }

    private fun tierLabel(tier: BandTier) = when (tier) {
        BandTier.LOW -> "low band — far reach, usually slow"
        BandTier.MID -> "mid band — the fast one"
        BandTier.HIGH -> "mmWave"
        BandTier.UNKNOWN -> "unknown band"
    }

    private fun ageText(s: SubSignal, now: Long): String {
        val age = s.ageMillis(now)
        return if (age == Long.MAX_VALUE) "never" else "${age / 1000}s ago"
    }

    // ------------------------------------------------------------ plumbing

    /** @return true when everything needed is already granted. */
    private fun ensurePermissions(): Boolean {
        val needed = listOf(
            // Cell identity (and therefore band numbers) requires location, not just phone state.
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.POST_NOTIFICATIONS,
        ).filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }

        if (needed.isNotEmpty()) requestPermissions(needed.toTypedArray(), 1)
        return needed.isEmpty()
    }

    override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(rc, p, r)
        if (r.isNotEmpty() && r.all { it == PackageManager.PERMISSION_GRANTED }) {
            SimSwitchService.start(this)
        }
    }

    /**
     * The one control that lets SimSwitch interrupt a live connection. Off by default, one tap to
     * turn off again, and the label always states the current state rather than the action.
     */
    private fun armButton(): Button {
        lateinit var b: Button
        fun label() = if (Arming.isArmed(this)) "ARMED — switching automatically (tap to disarm)"
        else "DISARMED — watching only (tap to arm)"
        b = button(label()) {
            Arming.setArmed(this, !Arming.isArmed(this))
            b.text = label()
            // Restart so the notification title and behaviour pick the change up immediately.
            SimSwitchService.start(this)
        }
        return b
    }

    /**
     * The grant behind the "don't interrupt me" rule.
     *
     * [ForegroundApp] fails open without it — switching keeps working exactly as it did before,
     * which is the safe failure but also the annoying one, so the state is stated on the button
     * rather than buried. Refreshed in [onResume] because the user leaves the app to grant it.
     */
    private fun usageAccessButton(): Button {
        lateinit var b: Button
        b = button(usageAccessLabel()) {
            startActivity(android.content.Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }
        usageButton = b
        return b
    }

    private fun usageAccessLabel(): String =
        if (ForegroundApp(this).hasAccess()) {
            "Usage access ON — won't switch while you're in an app"
        } else {
            "Usage access NEEDED — tap to grant, or it will interrupt apps"
        }

    /**
     * The grant that lets a switch actually happen while the app is in the background.
     *
     * Nothing is ever drawn over anything. "Display over other apps" is simply the exemption that
     * permits a background activity start, and without it the SIM manager never fronts — every
     * switch fails after a silent 15 s timeout. Unlike usage access this one does not fail safe:
     * without it automatic switching does not work at all, so it is stated in those terms.
     */
    private fun overlayAccessButton(): Button {
        lateinit var b: Button
        b = button(overlayAccessLabel()) {
            startActivity(
                android.content.Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:$packageName"),
                )
            )
        }
        overlayButton = b
        return b
    }

    private fun overlayAccessLabel(): String =
        if (Settings.canDrawOverlays(this)) {
            "Display-over-apps ON — switches can open the SIM manager"
        } else {
            "Display-over-apps NEEDED — tap to grant, or switches silently fail"
        }

    /**
     * Which apps a switch is not allowed to interrupt.
     *
     * Listing only launchable apps keeps this to a readable few dozen rather than every package on
     * the phone, and anything already protected is shown even if it no longer resolves a launcher
     * — otherwise unticking would be impossible for an app that changed.
     */
    private fun protectedAppsButton(): Button {
        lateinit var b: Button
        b = button(protectedAppsLabel()) { showProtectedAppsPicker() }
        protectedButton = b
        return b
    }

    private fun protectedAppsLabel(): String {
        val store = ProtectedApps(this)
        val n = store.packages().size
        val suffix = if (store.isSeeded()) " (default)" else ""
        return "Protected apps: $n$suffix — tap to choose"
    }

    private fun showProtectedAppsPicker() {
        val store = ProtectedApps(this)
        val selected = store.packages().toMutableSet()

        val pm = packageManager
        val launchable = pm.queryIntentActivities(
            android.content.Intent(android.content.Intent.ACTION_MAIN)
                .addCategory(android.content.Intent.CATEGORY_LAUNCHER),
            0,
        ).map { it.activityInfo.packageName }

        val packages = (launchable + selected).distinct()
            .filter { it != packageName }
            .sortedBy { pkg ->
                runCatching {
                    pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                }.getOrDefault(pkg).lowercase()
            }

        val labels = packages.map { pkg ->
            runCatching {
                pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
            }.getOrDefault(pkg)
        }.toTypedArray()
        val checked = packages.map { it in selected }.toBooleanArray()

        android.app.AlertDialog.Builder(this)
            .setTitle("Don't interrupt these apps")
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                if (isChecked) selected += packages[which] else selected -= packages[which]
            }
            .setPositiveButton("Save") { _, _ ->
                store.setPackages(selected)
                protectedButton?.text = protectedAppsLabel()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        setTextColor(Color.BLACK)
        setOnClickListener { onClick() }
    }
}
