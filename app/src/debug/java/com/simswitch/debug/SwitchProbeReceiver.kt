package com.simswitch.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.SubscriptionManager
import com.simswitch.switching.AccessibilitySwitcher

/**
 * Debug builds only: run the real switch path on demand from adb.
 *
 * With no extra it targets the SIM that already holds the data subscription — the no-op switch
 * that exercises the whole flow (open the SIM manager, read the nicknames, open the picker, tap,
 * verify) without changing anything. Needs the phone unlocked, like every switch.
 *
 * ```
 * adb shell am broadcast -n com.simswitch/.debug.SwitchProbeReceiver [--ei sub <id>]
 * adb logcat -s SwitchProbe
 * ```
 *
 * Guarded by DUMP, which the shell holds and no ordinary app can, so nothing else on the phone
 * can trigger a switch through this.
 */
class SwitchProbeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val target = intent.getIntExtra("sub", SubscriptionManager.getDefaultDataSubscriptionId())
        val pending = goAsync()
        Thread {
            val result = runCatching { AccessibilitySwitcher(context.applicationContext).switchTo(target) }
                .getOrElse { com.simswitch.switching.SimSwitcher.Result(false, "threw: $it") }
            android.util.Log.i(TAG, "switchTo($target) success=${result.success} — ${result.detail}")
            pending.finish()
        }.start()
    }

    private companion object {
        const val TAG = "SwitchProbe"
    }
}
