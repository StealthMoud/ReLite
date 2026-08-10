package io.relite.home.util

import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatDelegate

/**
 * Section 11-16 (v0.4.0): explicit System/Light/Dark theme choice, persisted
 * locally (no account/cloud sync — section 20) and applied via
 * [AppCompatDelegate.setDefaultNightMode], which AppCompat propagates to
 * every Activity's configuration without ReLite needing its own recreation
 * bookkeeping. Applied from [ReliteHomeApplication.onCreate], i.e. before any
 * Activity is created, so there's no visible flash of the wrong theme.
 */
enum class ThemeMode(val nightMode: Int) {
    SYSTEM(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM),
    LIGHT(AppCompatDelegate.MODE_NIGHT_NO),
    DARK(AppCompatDelegate.MODE_NIGHT_YES),
}

object ThemePreference {

    private const val PREFS_NAME = "relite_theme_prefs"
    private const val KEY_MODE = "theme_mode"

    fun get(context: Context): ThemeMode = parseMode(prefs(context).getString(KEY_MODE, null))

    /** Pure parsing logic, split out so it's testable without a Context/SharedPreferences. */
    internal fun parseMode(stored: String?): ThemeMode {
        if (stored == null) return ThemeMode.SYSTEM
        return runCatching { ThemeMode.valueOf(stored) }.getOrDefault(ThemeMode.SYSTEM)
    }

    fun set(context: Context, mode: ThemeMode) {
        prefs(context).edit().putString(KEY_MODE, mode.name).apply()
        applyToProcess(mode)
    }

    fun applyToProcess(mode: ThemeMode) {
        AppCompatDelegate.setDefaultNightMode(mode.nightMode)
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
