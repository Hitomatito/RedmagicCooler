package com.hitomatito.redmagicooler.data

import android.content.Context

/**
 * Preferencias globales de la app que no pertenecen a un perfil de cooler.
 */
object AppPreferences {
    private const val PREFS_NAME = "app_prefs"
    private const val KEY_ONBOARDING_DONE = "onboarding_done"

    fun isOnboardingDone(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ONBOARDING_DONE, false)

    fun setOnboardingDone(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ONBOARDING_DONE, true)
            .apply()
    }
}
