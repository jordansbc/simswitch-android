# SimSwitch

An Android app that automatically moves the **mobile data** subscription between two SIMs, picking
whichever network is actually better where the phone is. Voice and SMS are deliberately untouched.

Built for and tested on a **Galaxy S24 Ultra (SM-S928B, One UI, Android 16)** with two eSIMs — one
on AT&T, one on T-Mobile. No root, no bootloader unlock, no Shizuku, no recurring setup ritual.

This is a personal project, shared because the mechanism research took a while and some of the
findings are hard to come by. It is not a product, has no release build, and carries no warranty.

---

## The constraint that shapes everything

On DSDS (dual SIM, dual standby) **only the active data SIM moves bytes.** The standby radio is
registered but carries no traffic, so throughput can only ever be measured for one line at a time.
A direct throughput comparison between the two SIMs is impossible by construction.

What the standby radio *does* report is full signal metrics — RSRP, RSRQ, SINR, band. So scoring is
anchored on **SINR**, with measured throughput used to calibrate SINR→speed per carrier over time,
and a public-coverage prior to cover places never visited.

> **Known gap:** there is no exploration policy. The app only learns the other SIM's throughput
> where it switches for its own reasons, so a place where the current SIM is merely *adequate* may
> never get the alternative re-evaluated.

## How it switches

`SubscriptionManager.setDefaultDataSubId()` is `@SystemApi` behind `MODIFY_PHONE_STATE`, which a
normal app cannot hold. Two mechanisms were built:

**Shizuku (removed — structurally unusable).** It worked, but Shizuku's privileged server is spawned
by `adbd`. It calls `setsid()` and reparents to init, yet stays in **adbd's cgroup**. Android
disables Wireless debugging when the phone leaves the network it was enabled on, `adbd` restarts,
`libprocessgroup` kills that cgroup, and the server dies with it. The privileged path was therefore
alive **only on Wi-Fi** — exactly when a data switch is never wanted. Every drive silently disarmed
it while the app logged confident holds. Don't reach for `rish` or any other adb-spawned helper
either; they all share this flaw.

**AccessibilityService (current).** Drives the SIM manager UI:

```
android.settings.MANAGE_ALL_SIM_PROFILES_SETTINGS  → NoPermissionSimCardMgrActivity
  read the SIM list (nicknames) before scrolling — the list recycles out of the tree
  scroll to "Mobile data" under "Preferred SIMs"
  tap it                                           → popup of checkable rows
  tap the target SIM's row
  poll getDefaultDataSubscriptionId() until it actually moves
```

Granted once, survives reboots, network changes and drives. The cost is that it can only act on UI
that is **rendered and unlocked**, so a sleeping or locked phone defers rather than queues.

See [docs/engineering-notes.md](docs/engineering-notes.md) for the gotchas — several cost days.

## Why not just write a settings key

On **OnePlus/OxygenOS** you can: `oplus_customize_multi_sim_network_primary_slot` is a real lever
under `WRITE_SECURE_SETTINGS`. There is no One UI equivalent. Samsung's
`Settings.Global.multi_sim_data_call` **is a mirror, not a lever** — tested directly: writing it
sticks, but `_slot` never moves, `dds_progressing` never leaves 0, and the DDS does not change. So
the `pm grant WRITE_SECURE_SETTINGS` route buys nothing on Samsung, and UI automation is what is
left.

## Architecture

| Piece | Job |
|---|---|
| `telephony/SignalMonitor` | Per-subscription radio metrics via `TelephonyCallback`, both SIMs |
| `location/PlaceTracker` | geohash7 (~150 m) place key, `LocationManager` only — no Play Services |
| `telephony/ThroughputMonitor` | Passive `TrafficStats` sampling; never probes, never spends data |
| `data/SimSwitchDb` | Plain `SQLiteOpenHelper` — samples, per-place stats, decision log |
| `data/CoverageSeed` | Public coverage prior, normalised 0..1, decays out as real samples arrive |
| `scoring/ScoreEngine` | SINR-anchored score per subscription |
| `scoring/SwitchPolicy` | Margin, streak, dwell, rate limit, urgency — pure functions, unit tested |
| `state/DeviceState` | The interlocks: is *now* a safe moment |
| `state/ProtectedApps` | Which apps a switch must not interrupt |
| `switching/AccessibilitySwitcher` | The mechanism, behind an interface |

### When it is allowed to switch

Never mid-call — cellular or VoIP. VoIP is detected via `AudioManager.mode` **and**
`activeRecordingConfigurations` (anything holding the mic counts), *not* throughput: a VoIP call is
~50–100 kbps and would read as idle. Never while tethering. Never while the screen is on and data is
actually flowing, or media is playing, or a **protected app** is in front.

Screen-off and on-Wi-Fi are both *safe* windows — nobody notices a reconnect they can't see.

An **urgent** switch (the current SIM has no service) overrides the protected-app hold, but never
the call interlock.

The rules are pure functions because some combinations can't be staged on the device at all —
Samsung blocks toggling Wi-Fi from adb (`svc wifi` and `cmd wifi set-wifi-enabled` both no-op).

## Setup — four grants, all permanent

None needs root, and none needs repeating after a reboot.

1. **Accessibility service** — Settings → Accessibility → Installed apps. This is the mechanism.
2. **Usage access** — Settings → Apps → Special access → Usage data access. Powers the
   protected-app rule. Fails *open* if missing: switching still works, just rudely.
3. **Display over other apps** — Settings → Apps → Special access. **Nothing is ever drawn.**
   Holding `SYSTEM_ALERT_WINDOW` is simply the exemption that permits a *background activity start*,
   and opening the SIM manager from the monitoring service is one. Without it Android silently drops
   the `startActivity` — no exception, no log — and every switch dies at the 15 s timeout.
4. **Arm it** — the app is disarmed by default and watches only.

## Privacy

Everything is processed and stored on the device. There is no account, no server, no analytics SDK
and no telemetry. The app does read `INTERNET` for the coverage-seed puller only; the seed itself is
generated on a PC and bundled as an asset.

## Building

```
./gradlew assembleDebug testDebugUnitTest
```

The coverage seed asset is **not** in this repo — it derives from CoverageMap's crowdsourced
dataset, which they sell access to. Set `BBOX` in `tools/fetch_coverage_seed.mjs` to your own area
and run `node tools/fetch_coverage_seed.mjs` to generate it. The app works without it; places simply
start with no prior.

Toolchain notes: AGP 9 rejects the `org.jetbrains.kotlin.android` plugin (Kotlin is built in), and
`org.gradle.java.home` needs to point at a JDK, not a JRE.

## Status

Working and in daily use. Ten days of continuous logging on the target device produced a healthy
mechanism — service and accessibility binding intact across reboots, the DDS never drifting from
what the app believed — and three fixes, all described in
[docs/engineering-notes.md](docs/engineering-notes.md):

- throughput was being misattributed whenever a **VPN** was in the path
- switches were being **silently dropped** as background activity starts
- the politeness rule was over-blocking, at roughly 35 blocks per switch

## Licence

MIT — see [LICENSE](LICENSE).
