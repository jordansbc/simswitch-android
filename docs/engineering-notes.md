# Engineering notes

The things that cost real time. Most of these are Android/One UI behaviours where the obvious
mental model is wrong, and where the debugging tool and the running code disagree about reality.

---

## Mechanism

### `multi_sim_data_call` is a mirror, not a lever (Samsung)

The tempting shortcut on Samsung is `pm grant WRITE_SECURE_SETTINGS` and then write
`Settings.Global.multi_sim_data_call`. Tested directly, with the screen off and Wi-Fi off so nothing
else could interfere: the write **sticks**, and nothing happens. `multi_sim_data_call_slot` never
moves, `multi_sim_dds_progressing` never leaves 0, and the actual DDS is unchanged. The key reflects
the DDS; it does not set it.

On OnePlus/OxygenOS the equivalent vendor key (`oplus_customize_multi_sim_network_primary_slot`)
*is* a real lever under `WRITE_SECURE_SETTINGS`. There is no One UI counterpart, which is why this
project ended up driving the settings UI instead of writing a setting.

### Shizuku dies on exactly the trips you care about

Shizuku's privileged server is spawned by `adbd`. It calls `setsid()` and reparents to init
(`ppid=1`), which looks like full detachment — but it remains in **adbd's cgroup**
(`0::/system/uid_0/pid_<adbd>`). When the phone leaves the Wi-Fi network that Wireless debugging was
enabled on, Android disables Wireless debugging, `adbd` restarts, and `libprocessgroup` kills that
cgroup along with the server.

Net effect: the privileged path is alive **only on Wi-Fi**, i.e. only when a data switch is never
wanted. Worse, it fails silently — the app kept logging confident "hold" decisions while completely
disarmed. Any adb-spawned helper (`rish` included) has this same property.

The durable lesson: a granted **permission** survives reboots and drives; a privileged **process**
does not.

### A foreground service may not start an activity

This one presents as a mystery. `startActivity` returns normally, throws nothing, logs nothing, and
the activity simply never appears. The switcher then times out waiting for a window that was never
going to arrive.

The background-activity-start allowance for foreground services was removed in Android 10 and does
**not** cover `foregroundServiceType="specialUse"`. The exemption an app like this can actually
qualify for is holding **`SYSTEM_ALERT_WINDOW`** ("Display over other apps"), even though nothing is
ever drawn with it.

Two things worth knowing:

- `appops get <pkg> SYSTEM_ALERT_WINDOW` reports a `rejectTime`, which is how you confirm the
  diagnosis retrospectively — it timestamps the moment the ActivityStarter refused.
- The grant is revocable at any time from Settings, and losing it turns every switch back into a
  silent 15 s timeout. Check it rather than assuming it, and name it in the failure message.

To verify the whole path without changing anything, **perform a no-op switch to the SIM that is
already the DDS**. It opens the SIM manager, navigates, taps the already-selected row and confirms
the DDS — exercising every step — while leaving the phone exactly as it was. Fire it from a
background context 30 s after backgrounding the app, or the foreground grace period will mask the
very bug you are testing for.

---

## AccessibilityService gotchas

### `flagReportViewIds` is mandatory, and its absence is invisible

Without it, `getViewIdResourceName()` returns **null for every node**, breaking all id-based
matching. The trap is that `uiautomator` sets the flag for itself, so `adb shell uiautomator dump`
shows the ids perfectly while the running service sees none. The debugging tool and the code
disagree about reality.

Suspect this immediately if id matching fails while text matching works.

### The SIM picker labels SIMs by a nickname no API exposes

`SubscriptionInfo.displayName` is a generic `"MOBILE"` and `carrierName` is the carrier — but the
picker shows whatever the user named the line ("Line 1", "Line 2"). Matching on the API's names
therefore fails against the on-screen rows.

The fix is to derive the mapping **from the screen**: read the SIM list at the top of the SIM
manager, pairing each row's name with its carrier line, before scrolling. It has to happen first —
scrolling down to "Mobile data" recycles the list out of the node tree.

### Wait for the window, don't time-box the read

`startActivity` returns long before the window exists. Time-boxing the SIM-row read instead of
waiting for the window means the read spends its whole budget scanning the *launcher*, finds no
rows, and the scroll step then arrives after the activity appears and scrolls straight past the SIM
list. The symptom is an empty row list and a picker match that fails on unaugmented labels — the
same error message as before the fix, which makes it look like the fix did nothing.

Wait on `rootInActiveWindow.packageName` being the settings package, then read.

### A tap landing proves nothing

Only the DDS actually moving proves a switch. Poll `getDefaultDataSubscriptionId()` until it
changes or a deadline passes, and report a tap-without-movement as a failure.

### Keep `packageNames` narrow

The service is scoped to the telephony-settings packages precisely so the OS cannot hand it the
contents of a banking app. Do not widen it for convenience — in particular, do not widen it to
detect unlocks.

---

## Android behaviours that broke assumptions

### One UI never delivered `ACTION_USER_PRESENT`

A receiver for `ACTION_USER_PRESENT` registered cleanly, appeared in the system's filter table for
that action — and fired **zero** times. It appeared in **no broadcast record's receiver list**
(`deferralPolicy=2`, records stuck `Pending`).

Registration succeeding proves nothing about delivery. Check the broadcast *records*, not the
registration. Replaced with a poll of `isInteractive` + `isKeyguardLocked`.

### `getActiveNetwork()` returns the VPN, and that silently corrupts data

`ConnectivityManager.getActiveNetwork()` returns the **VPN** network when a VPN is up, and a VPN
only advertises its underlying transports if it declares them — many don't. So a
`hasTransport(TRANSPORT_WIFI)` check answers **false while on Wi-Fi**.

Any gate written as "not on Wi-Fi" therefore opens on Wi-Fi-behind-a-VPN. Here that meant whole
windows of Wi-Fi traffic were recorded as *cellular* throughput samples for whichever SIM happened
to be the data SIM — 234,793 kbps and 224,797 kbps readings against a real-world maximum of about
8.3 Mbps.

Write the **positive** test: cellular present, Wi-Fi absent, `NET_CAPABILITY_NOT_VPN` present. A VPN
is disqualifying even over cellular, because the tunnel is double-counted against the mobile
interface and inflates the rate by an unknown factor rather than merely misattributing it.

### `targetSdk = 36` forces edge-to-edge

The layout drew behind the action bar and the top ~140 px was unreachable, with the ScrollView
already at scroll 0. This completely hid the arm button — the app looked like it had no way to turn
it on. Fixed with `actionBar?.hide()` plus `systemBars()` insets. Any new top-of-screen control
needs the same treatment.

---

## Measurement traps

### Passive throughput measures demand, not capacity

Only ~16% of windows carried enough traffic to measure at all. The distribution over ten days:
p50 0.97 Mbps, p99 7.19 Mbps, maximum ever 8.27 Mbps. These are numbers for *what was requested*,
not what the link could do.

The practical consequence: any rule of the form "don't switch if already fast enough" is dead code,
because the measured figure almost never gets near a meaningful threshold. And a plausibility
ceiling set from theoretical link speed (1.5 Gbps) is useless — it has to be set from the observed
distribution. 100 Mbps is ~12x the highest genuine sample and still catches every artefact seen.

### Attribution is the whole game

Because throughput can only ever belong to one line, getting *which* line wrong silently corrupts
the learned map. Three distinct bugs, all found by diffing the database against the phone's actual
DDS — worth re-running that check after any change here:

1. The DDS was read once at service start, so when it was moved by hand a whole night of one
   carrier's traffic was filed under the other. Re-read it every window.
2. That fix didn't cover *self-initiated* switches: the code re-read the DDS immediately after
   switching and consumed the change, so the next window saw none. The first armed switch recorded
   403,083 kbps — a `TrafficStats` interface swap, not a measurement. A switch now resets the
   monitor and discards the following window outright.
3. The VPN case above.

### A single bad sample is permanent

With an EWMA at α = 0.3, one 234 Mbps reading leaves a place claiming ~70 Mbps indefinitely. Since
the samples table is the source of truth, the repair is to null the impossible readings and
**re-derive** every stored average from what survives — reusing the live blending constant, so the
repair cannot produce numbers the app could never have reached on its own.

Related: a migration that drops tables is fine while the store is scratch and catastrophic once it
holds weeks of data plus a generated asset. Gate the drop to the versions where it was actually
harmless.

---

## Policy notes

### Politeness needs to be asked at the right time, and about the right apps

Two failure modes, in sequence.

**Too rude.** The interlocks were designed for an *invisible* switch (a binder call). Once the
switch visibly opened the SIM manager for 2–3 seconds, "screen on but idle" stopped meaning "won't
be noticed" — navigation apps sit well under any busy-traffic threshold and were switched straight
through.

**Then too polite.** Blocking on *any* foreground app produced 13 switches against 475 blocks over
ten days, with streaks of up to 654 consecutive windows wanting to switch and never doing it. The
top blocker was a reading app; the only genuinely handoff-sensitive one in the list was maps.

Two things fixed it:

- **Ask at a better moment.** The quiet gap after an unlock is 1–2 s and the loop polls every 20 s,
  so the question was almost never asked while the phone was awake and idle. Evaluating on unlock
  as well — gated by two spaced observations, at 800 ms and 2000 ms, that must *both* be quiet —
  distinguishes "unlocked to the home screen" from "unlocked by tapping a notification".
- **Let the user pick.** A short, user-editable protected list beats guessing from the fact that
  something is on screen.

The split that makes a short list safe: protected apps hold **optimisation** handoffs, but never
**rescue** ones. If the current SIM has no service, the switch happens regardless of what is in
front.

### Retry storms

A failing switcher will hammer. Record failures and back off (1/2/4/8/15 min), and apply the backoff
even to urgent switches — hammering a broken mechanism helps nobody, and doing it on top of whatever
the user is looking at is worse than waiting.

---

## Device notes

- Samsung's build ships **no `sqlite3` binary**. Pull the database with
  `adb exec-out run-as <pkg> cat databases/<name>.db` and read it on the PC.
- `adb shell` re-parses commands on the device, so SQL has to be single-quoted as one argument.
- The **SIMINFO provider is blocked from shell** ("not phone/system UID") — enumerate subscriptions
  through `SubscriptionManager` with `READ_PHONE_STATE`, not `content query`.
- `adb shell dumpsys deviceidle whitelist +<pkg>` is what keeps the service alive across a long
  drive.
- Samsung blocks toggling Wi-Fi from adb: `svc wifi` and `cmd wifi set-wifi-enabled` both no-op.
  Interlock combinations involving Wi-Fi cannot be staged on the device — hence pure functions and
  unit tests.

### Proving a mechanism before writing an app

`adb shell` runs as the same UID Shizuku grants, so a tiny dex run via
`CLASSPATH=... app_process / ProbeMain` proves a privileged mechanism with nothing installed and no
screen taps. Source in [`tools/ProbeMain.java`](../tools/ProbeMain.java).

Resolve methods **by name** via reflection, never `service call isub <transaction>`: transaction
codes differ per build, and a wrong index on a live phone could hit `setDefaultVoiceSubId` instead.
