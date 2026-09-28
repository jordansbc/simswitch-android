package com.simswitch.switching

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.SystemClock
import android.telephony.SubscriptionManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/**
 * Mechanism B: move the data SIM by driving Samsung's SIM manager.
 *
 * This replaces the Shizuku binder path, which was not merely fragile but structurally unusable.
 * Shizuku's privileged server is spawned by adbd and, though it calls `setsid()` and reparents to
 * init, it stays in adbd's cgroup (`/system/uid_0/pid_<adbd>`). Android disables Wireless debugging
 * when the phone leaves the Wi-Fi network it was enabled on, adbd restarts, `libprocessgroup` kills
 * that cgroup, and the server dies with it. The privileged path was therefore available *only* on
 * Wi-Fi — precisely when a data switch is never wanted. Verified on device 2026-09-02.
 *
 * An AccessibilityService is granted once and survives reboots, network changes and drives, with no
 * adb, no root and nothing to restart. The cost is that it can only act on UI that is rendered and
 * unlocked; [AccessibilitySwitcher] owns that decision and defers when the phone is asleep.
 *
 * The flow, read off the real device (SM-S928B, One UI, Android 16):
 * ```
 * android.settings.MANAGE_ALL_SIM_PROFILES_SETTINGS   → NoPermissionSimCardMgrActivity
 *   scroll to "Mobile data" under "Preferred SIMs"
 *   tap it                                            → PopupWindow of android:id/text1 checkables
 *   tap the row whose text is the target SIM's name
 * ```
 *
 * **The safety rule, which every click here obeys: only ever tap a node we positively identified.**
 * The picker offers "Off" alongside the two SIMs, and the row directly above "Mobile data" is
 * "Messages". A fuzzy match would silently kill mobile data or move the SMS subscription. So every
 * tap is an exact, case-insensitive, whitespace-trimmed match against a label we already know, and
 * anything unrecognised aborts without touching the screen.
 */
class SwitchAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    /**
     * The flow is polled rather than event-driven. Events would only add choreography: we always
     * know what we are waiting for, and a poll with an explicit deadline is far easier to reason
     * about — and to fail safely — than a state machine spread across callbacks.
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    /**
     * Open the SIM manager and select [subId], returning only once the DDS has actually moved.
     *
     * Must not be called on the main thread — it blocks for up to a few seconds.
     *
     * @param targetLabels every name the target SIM might be listed under (nickname and carrier).
     * @param otherLabels  the same for every *other* subscription, so an ambiguous row is refused
     *                     rather than guessed at.
     */
    @Synchronized
    fun performSwitch(
        subId: Int,
        targetLabels: Set<String>,
        otherLabels: Set<String>,
    ): SimSwitcher.Result {
        openSimManager()?.let {
            return SimSwitcher.Result(false, "could not open the SIM manager: $it")
        }

        try {
            // Learn the on-screen nicknames before scrolling past the SIM list. This has to happen
            // first: the list is at the top of the screen, and scrolling to "Mobile data" recycles
            // it out of the node tree.
            if (!awaitSimManagerWindow()) {
                // A background activity start that Android refuses fails exactly like a slow one:
                // startActivity returns normally and nothing ever appears. Naming the likely cause
                // here is the difference between a diagnosable failure and a mystery, since the
                // grant is the one thing the user has to fix and the app cannot fix for them.
                return SimSwitcher.Result(
                    false,
                    if (!canStartActivityFromBackground()) {
                        "the SIM manager never came to the foreground — " +
                            "'Display over other apps' is not granted, so Android is dropping the " +
                            "background activity start"
                    } else {
                        "the SIM manager never came to the foreground (windows: ${describeWindows()})"
                    },
                )
            }
            val simRows = awaitSimRows()
            val (target, other) = augmentWithNicknames(targetLabels, otherLabels, simRows)

            val row = awaitScrolling(DATA_ROW_LABELS)
                ?: return SimSwitcher.Result(
                    false,
                    "'Mobile data' never appeared in the SIM manager (saw: ${visibleTexts()})",
                )
            if (!tap(row)) return SimSwitcher.Result(false, "'Mobile data' had no clickable parent")

            val choice = awaitPickerChoice(target, other)
                ?: return SimSwitcher.Result(
                    false,
                    "no unambiguous row for $target in the picker " +
                        "(saw: ${visibleTexts()}; simRows=$simRows)",
                )
            if (!tap(choice)) return SimSwitcher.Result(false, "picker row had no clickable parent")

            // A tap landing proves nothing; only the DDS actually moving does. This is the same
            // discipline the Shizuku path used, and for the same reason.
            val deadline = SystemClock.uptimeMillis() + VERIFY_TIMEOUT_MS
            while (SystemClock.uptimeMillis() < deadline) {
                if (SubscriptionManager.getDefaultDataSubscriptionId() == subId) {
                    return SimSwitcher.Result(true, "data subscription moved to $subId")
                }
                Thread.sleep(POLL_MS)
            }
            return SimSwitcher.Result(
                false,
                "picker was tapped but the DDS did not move within ${VERIFY_TIMEOUT_MS}ms",
            )
        } catch (t: Throwable) {
            return SimSwitcher.Result(false, "UI automation failed: $t")
        } finally {
            leaveSettings()
        }
    }

    // ---- node plumbing -------------------------------------------------------------------------

    /**
     * Wait for one of [labels] to appear, scrolling the list forward until it does.
     *
     * Scrolling is not optional: "Mobile data" sits below the fold on this device, and a
     * RecyclerView drops off-screen rows out of the node tree entirely, so the row has to be
     * scrolled into existence rather than merely searched for.
     */
    private fun awaitScrolling(labels: Set<String>): AccessibilityNodeInfo? {
        repeat(SCROLL_ATTEMPTS) {
            Thread.sleep(SETTLE_MS)
            findByText(labels)?.let { return it }
            // Stop once nothing will scroll any further, rather than spinning out the attempts.
            if (!scrollForward()) return findByText(labels)
        }
        return findByText(labels)
    }

    /**
     * Find the picker row for the target SIM.
     *
     * Two guards matter here. The SIM manager screen behind the popup also lists the SIM names, so
     * rows are accepted only when they carry `android:id/text1` — the picker's own item id, which
     * the manager list (`:id/simName`, `:id/plmn`) never uses. And a label claimed by more than one
     * subscription, or matched more than once, is refused outright rather than guessed.
     */
    private fun awaitPickerChoice(
        targetLabels: Set<String>,
        otherLabels: Set<String>,
    ): AccessibilityNodeInfo? {
        val wanted = targetLabels.map { it.trim().lowercase() }.toSet() -
            otherLabels.map { it.trim().lowercase() }.toSet()
        if (wanted.isEmpty()) return null

        repeat(PICKER_ATTEMPTS) {
            Thread.sleep(SETTLE_MS)
            val hits = nodes().filter { node ->
                node.viewIdResourceName == PICKER_ITEM_ID &&
                    node.text?.toString()?.trim()?.lowercase() in wanted
            }
            if (hits.size == 1) return hits.single()
            if (hits.size > 1) return null // ambiguous — refuse rather than pick one
        }
        return null
    }

    /**
     * Add each SIM's on-screen nickname to the label set it belongs to.
     *
     * The picker lists SIMs by the name the user gave them — "Line 1", "Line 2" — and that
     * name is not reachable from [android.telephony.SubscriptionInfo] at all: `displayName` came
     * back as the generic "MOBILE" and `carrierName` as "SIM A", neither of which appears in
     * the picker. The first live attempt failed on exactly this.
     *
     * The SIM manager screen solves it by showing both together: `:id/simName` is the nickname and
     * `:id/plmn` the carrier, as siblings in one row. So the nickname is *derived* here by matching
     * the carrier name we already know, rather than guessed or hardcoded.
     *
     * A nickname whose carrier matches neither side is dropped rather than assigned, so an
     * unrecognised third SIM can never be mistaken for the target.
     */
    private fun augmentWithNicknames(
        targetLabels: Set<String>,
        otherLabels: Set<String>,
        simRows: List<Pair<String, String>>,
    ): Pair<Set<String>, Set<String>> {
        val target = targetLabels.toMutableSet()
        val other = otherLabels.toMutableSet()
        val lower = { s: Set<String> -> s.map { it.trim().lowercase() }.toSet() }

        for ((nickname, carrier) in simRows) {
            val key = carrier.trim().lowercase()
            when (key) {
                in lower(targetLabels) -> target += nickname
                in lower(otherLabels) -> other += nickname
            }
        }
        return target to other
    }

    /**
     * Whether Android will honour a `startActivity` from this background service.
     *
     * A foreground service is not itself a licence to start an activity — that allowance was
     * removed in Android 10 and a foreground-service exemption only applies to a few types, not
     * `specialUse`. Holding "Display over other apps" (SYSTEM_ALERT_WINDOW) is the exemption this
     * app actually qualifies for, which is why the permission is declared despite the app never
     * drawing an overlay.
     *
     * Checked rather than assumed because the grant is revocable at any time from Settings, and
     * losing it turns every switch into a silent 15 s timeout.
     */
    fun canStartActivityFromBackground(): Boolean = android.provider.Settings.canDrawOverlays(this)

    /**
     * Block until the SIM manager is genuinely the active window.
     *
     * `startActivity` returns immediately, and from a background service the activity can take
     * several seconds to actually front. Time-boxing the SIM-row read instead of waiting for the
     * window is what produced `simRows=[]` twice: the read spent its whole budget scanning the
     * launcher, found no rows, and the scroll step then arrived after the activity had appeared
     * and scrolled straight past the SIM list. Waiting on the window removes the race entirely,
     * and a timeout here is honestly reportable rather than silently degrading.
     */
    private fun awaitSimManagerWindow(): Boolean {
        val start = SystemClock.uptimeMillis()
        val deadline = start + WINDOW_TIMEOUT_MS
        var reopened = false
        while (SystemClock.uptimeMillis() < deadline) {
            if (settingsWindows().isNotEmpty()) return true
            // One more start at the halfway mark. A launch that lands mid-transition (the unlock
            // animation, the shade closing) can be swallowed, and a second start is harmless when
            // the first one is merely slow — CLEAR_TOP brings back the same activity.
            if (!reopened && SystemClock.uptimeMillis() - start >= WINDOW_TIMEOUT_MS / 2) {
                reopened = true
                openSimManager()
            }
            Thread.sleep(POLL_MS)
        }
        return false
    }

    /** Start the SIM manager, returning the error if the start itself threw. */
    private fun openSimManager(): Throwable? = runCatching {
        startActivity(
            Intent(SIM_SETTINGS_ACTION)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        )
    }.exceptionOrNull()

    /**
     * Every window the SIM manager's packages own, topmost first — the picker popup above the
     * manager screen.
     *
     * This, not `rootInActiveWindow`, is what every lookup reads. "Active" is whichever window the
     * system last credited with input or accessibility focus, and that is not reliably the activity
     * we just started: in one logged failure the usage events show the SIM manager resumed and
     * stayed up until the owner swiped it away, while the switcher polled for 15 s and reported
     * it "never came to the foreground" — and its two back presses closed nothing, so focus was
     * never on it. Most switch failures in an 18-day trial were that message.
     * Accessibility actions are delivered to a view directly, so the window needs no focus to be
     * driven.
     *
     * packageNames in the service config still applies: any other app's window comes back with a
     * null root and is dropped here, so this widens nothing.
     */
    private fun settingsWindows(): List<AccessibilityWindowInfo> =
        runCatching { windows }.getOrNull().orEmpty()
            .filter { it.root?.packageName?.toString() in SETTINGS_PACKAGES }
            .sortedByDescending { it.layer }

    /**
     * The window layout, for failure messages only: each window's type, whether it is the SIM
     * manager's, and which holds active/focus. Other apps' windows are named only as "other" — the
     * service is scoped not to read them, and a failure log is no reason to start.
     */
    private fun describeWindows(): String =
        runCatching { windows }.getOrNull().orEmpty().joinToString(", ") { w ->
            val owner = w.root?.packageName?.toString()?.takeIf { it in SETTINGS_PACKAGES } ?: "other"
            val type = when (w.type) {
                AccessibilityWindowInfo.TYPE_APPLICATION -> "app"
                AccessibilityWindowInfo.TYPE_INPUT_METHOD -> "ime"
                AccessibilityWindowInfo.TYPE_SYSTEM -> "system"
                AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY -> "a11y"
                AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER -> "divider"
                else -> "type${w.type}"
            }
            "$type/$owner" + (if (w.isActive) "*active" else "") + (if (w.isFocused) "*focused" else "")
        }.ifEmpty { "none reported" }

    /**
     * Wait for the SIM list to render before reading it.
     *
     * `startActivity` returns long before the window exists, so reading immediately scans whatever
     * was on screen before — the launcher — and finds no SIM rows at all. The first attempt at the
     * nickname lookup failed exactly this way, silently: no rows meant no nicknames, and the picker
     * match then failed on the unaugmented labels with the same message as before the fix. The
     * scroll step only worked by accident, because it happens to sleep before its first look.
     */
    private fun awaitSimRows(): List<Pair<String, String>> {
        repeat(SIM_ROW_ATTEMPTS) {
            Thread.sleep(SETTLE_MS)
            val rows = readSimRows()
            if (rows.isNotEmpty()) return rows
        }
        return emptyList()
    }

    /** Every (nickname, carrier) pair visible in the SIM list, read from sibling nodes. */
    private fun readSimRows(): List<Pair<String, String>> =
        nodes().filter { it.viewIdResourceName?.endsWith(SIM_NAME_ID) == true }
            .mapNotNull { nameNode ->
                val nickname = nameNode.text?.toString()?.trim().orEmpty()
                if (nickname.isEmpty()) return@mapNotNull null
                val parent = nameNode.parent ?: return@mapNotNull null
                val carrier = descendants(parent)
                    .firstOrNull { it.viewIdResourceName?.endsWith(PLMN_ID) == true }
                    ?.text?.toString()?.trim()
                    ?.takeIf(String::isNotEmpty)
                    ?: return@mapNotNull null
                nickname to carrier
            }

    private fun descendants(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val out = mutableListOf<AccessibilityNodeInfo>()
        val queue = ArrayDeque(listOf(root))
        while (queue.isNotEmpty() && out.size < MAX_NODES) {
            val node = queue.removeFirst()
            out += node
            for (i in 0 until node.childCount) node.getChild(i)?.let(queue::addLast)
        }
        return out
    }

    private fun findByText(labels: Set<String>): AccessibilityNodeInfo? {
        val wanted = labels.map { it.trim().lowercase() }.toSet()
        return nodes().firstOrNull { it.text?.toString()?.trim()?.lowercase() in wanted }
    }

    /** Click the node, or the nearest ancestor that will actually take a click. */
    private fun tap(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        repeat(ANCESTOR_DEPTH) {
            val target = current ?: return false
            if (target.isClickable && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true
            }
            current = target.parent
        }
        return false
    }

    /**
     * Scroll every scrollable container, returning whether any of them actually moved.
     *
     * There are two here: an outer ScrollView wrapping the collapsing app bar, and the
     * `recycler_view` holding the preference rows. The outer one comes first in tree order but
     * scrolling it does not advance the list, so taking only the first scrollable node found the
     * app-bar wrapper and silently did nothing — the bug that made the first live attempt fail.
     */
    private fun scrollForward(): Boolean =
        nodes().filter { it.isScrollable }
            .map { it.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) }
            .any { it }

    /**
     * Every node in the SIM manager's windows, topmost window first, breadth-first within each.
     * See [settingsWindows] for why this is not `rootInActiveWindow`.
     */
    private fun nodes(): List<AccessibilityNodeInfo> {
        val roots = settingsWindows().mapNotNull { it.root }
        if (roots.isEmpty()) return emptyList()
        val out = mutableListOf<AccessibilityNodeInfo>()
        val queue = ArrayDeque(roots)
        while (queue.isNotEmpty() && out.size < MAX_NODES) {
            val node = queue.removeFirst()
            out += node
            for (i in 0 until node.childCount) node.getChild(i)?.let(queue::addLast)
        }
        return out
    }

    /** Only for failure messages — a One UI relabel should be diagnosable from the decisions log. */
    private fun visibleTexts(): String =
        nodes().mapNotNull { it.text?.toString()?.trim()?.takeIf(String::isNotEmpty) }
            .distinct()
            .take(12)
            .joinToString(", ")

    /**
     * Leave Settings however the attempt ended.
     *
     * A global back goes to whichever window has focus, which is not necessarily ours — the logged
     * failure described at [settingsWindows] left the SIM manager on screen after both backs. So
     * back is pressed only while a SIM-manager window is the focused one; otherwise the manager's
     * own "Navigate up" button is clicked, which needs no focus. If neither applies the screen is
     * left for the user rather than sending a back into whatever app they are in.
     */
    private fun leaveSettings() {
        runCatching {
            repeat(LEAVE_ATTEMPTS) {
                val top = settingsWindows().firstOrNull() ?: return
                when {
                    top.isFocused || top.isActive -> performGlobalAction(GLOBAL_ACTION_BACK)
                    !clickNavigateUp() -> return
                }
                Thread.sleep(SETTLE_MS)
            }
        }
    }

    private fun clickNavigateUp(): Boolean {
        val up = nodes().firstOrNull {
            it.contentDescription?.toString()?.trim()?.lowercase() in NAVIGATE_UP_LABELS
        } ?: return false
        return tap(up)
    }

    companion object {
        /** Set on connect, cleared on teardown — the honest signal for "can we switch right now". */
        @Volatile
        var instance: SwitchAccessibilityService? = null
            private set

        private const val SIM_SETTINGS_ACTION = "android.settings.MANAGE_ALL_SIM_PROFILES_SETTINGS"

        /** The picker's list-item id. Deliberately not the manager list's `:id/simName`. */
        private const val PICKER_ITEM_ID = "android:id/text1"

        /** SIM-list row ids, matched by suffix so the package prefix can vary. */
        private const val SIM_NAME_ID = ":id/simName"
        private const val PLMN_ID = ":id/plmn"

        /**
         * Accepted spellings of the "Mobile data" preference row. English only, matching the
         * phone's locale; a locale change would fail closed (no row found, no tap) rather than
         * clicking the wrong preference, which is the outcome that matters.
         */
        private val DATA_ROW_LABELS = setOf("mobile data", "mobile data network")

        private const val SETTLE_MS = 400L
        private const val POLL_MS = 250L
        private const val SCROLL_ATTEMPTS = 8
        private const val PICKER_ATTEMPTS = 10
        private const val SIM_ROW_ATTEMPTS = 12
        private const val WINDOW_TIMEOUT_MS = 15_000L

        /** Packages the SIM manager can legitimately be served by on this build. */
        private val SETTINGS_PACKAGES = setOf(
            "com.samsung.android.app.telephonyui",
            "com.android.settings",
        )
        private const val VERIFY_TIMEOUT_MS = 8_000L
        private const val ANCESTOR_DEPTH = 6
        private const val LEAVE_ATTEMPTS = 3
        private val NAVIGATE_UP_LABELS = setOf("navigate up", "back")
        private const val MAX_NODES = 600
    }
}
