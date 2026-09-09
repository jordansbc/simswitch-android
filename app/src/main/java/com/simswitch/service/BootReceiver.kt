package com.simswitch.service

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/**
 * Restarts monitoring after a reboot.
 *
 * Phase 2 is about accumulating days of data; a reboot that silently stops collection would leave
 * a hole that nobody notices until the learned map looks wrong. Permissions are re-checked because
 * the user can revoke them while the phone is off.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val granted = listOf(
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ).all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

        if (granted) SimSwitchService.start(context)
    }
}
