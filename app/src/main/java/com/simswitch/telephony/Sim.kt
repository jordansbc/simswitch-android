package com.simswitch.telephony

import android.content.Context

/**
 * Process-wide handle on the radio monitor.
 *
 * The service owns its lifecycle (start/stop) while the UI only observes, so both need to reach the
 * same instance without the activity binding to the service for what is purely a read.
 */
object Sim {

    @Volatile
    private var instance: SignalMonitor? = null

    fun monitor(context: Context): SignalMonitor =
        instance ?: synchronized(this) {
            instance ?: SignalMonitor(context.applicationContext).also { instance = it }
        }
}
