package com.simswitch.scoring

import android.content.Context

/**
 * The master switch for Phase 4.
 *
 * Kept explicit and off by default. Everything up to here only observes; this is the one setting
 * that lets SimSwitch interrupt a live connection, so turning it on should be a deliberate act and
 * turning it off again should be one tap.
 */
object Arming {

    private const val PREF = "simswitch"
    private const val KEY = "armed"

    fun isArmed(context: Context): Boolean =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean(KEY, false)

    fun setArmed(context: Context, armed: Boolean) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putBoolean(KEY, armed).apply()
    }
}
