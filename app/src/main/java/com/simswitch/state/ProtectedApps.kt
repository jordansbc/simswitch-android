package com.simswitch.state

import android.content.Context

/**
 * The apps a switch must not interrupt.
 *
 * This replaces the original rule, which was "anything that isn't the launcher counts as in use".
 * That rule was safe and completely impractical: over ten days on device it produced 13 switches
 * against 475 blocks, with streaks of up to 654 consecutive windows wanting to switch and never
 * doing it. The blockers were dominated by apps a two-second interruption does not actually spoil —
 * a Bible reader (27), WhatsApp (14), Claude (13), 9gag (9) — while the one app that genuinely
 * matters, Maps, accounted for 20.
 *
 * So the question "would an interruption here be bad?" is answered by the user, not guessed from
 * the fact that something is on screen. A small seed list covers the obvious cases on day one and
 * everything else is switchable until the owner says otherwise.
 *
 * This governs *optimisation* switches only. A rescue switch — the current SIM has no service —
 * overrides [DeviceState.Blocker.APP_IN_USE] regardless of what is in front; that split already
 * lives in `SwitchPolicy`, and it is the reason a short protected list is safe rather than reckless.
 */
class ProtectedApps(private val context: Context) {

    private val prefs by lazy {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    /**
     * Packages currently protected.
     *
     * The seed only applies until the user saves a selection. After that an empty set means
     * "nothing protected" and is honoured — otherwise clearing the list would silently re-seed it,
     * and the user would have no way to say "interrupt me anywhere".
     */
    fun packages(): Set<String> =
        if (prefs.contains(KEY)) prefs.getStringSet(KEY, emptySet()).orEmpty() else SEED

    fun setPackages(packages: Set<String>) {
        prefs.edit().putStringSet(KEY, packages).apply()
    }

    /** Whether the user has ever edited the list, i.e. whether [packages] is still the seed. */
    fun isSeeded(): Boolean = !prefs.contains(KEY)

    fun isProtected(pkg: String?): Boolean = pkg != null && pkg in packages()

    companion object {
        private const val PREFS = "simswitch.protected"
        private const val KEY = "packages"

        /**
         * Turn-by-turn navigation and full-screen video: the two cases where losing the screen for
         * two seconds is genuinely disruptive rather than merely rude.
         *
         * Deliberately short. Music is absent because audio already has its own interlock
         * ([DeviceState.Blocker.MEDIA_PLAYING]) that does not depend on this list, and calls are
         * covered by [DeviceState.Blocker.CALL], which nothing overrides.
         */
        val SEED: Set<String> = setOf(
            "com.google.android.apps.maps",
            "com.waze",
            "com.google.android.youtube",
            "com.netflix.mediaclient",
        )
    }
}
