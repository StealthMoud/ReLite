package io.relite.home.util

import android.content.Context
import android.content.SharedPreferences

/**
 * Section 73-78/82 (v0.5.0): Home screen presentation prefs — app label
 * visibility and the dock's dedicated Apps button. Local-only, same as
 * [ThemePreference]/[AppsPreference]. [getShowAppsButton] defaults to
 * `true`: the master plan recommends defaulting it off in favor of a
 * Home->Apps swipe gesture, but that gesture (section 82) isn't implemented
 * this pass — defaulting the button off first would leave no way to reach
 * the drawer at all, so the safer default is kept until the gesture exists.
 * See CHANGELOG's Known gaps.
 */
object HomePreference {

    private const val PREFS_NAME = "relite_home_prefs"
    private const val KEY_SHOW_APP_LABELS = "show_app_labels"
    private const val KEY_SHOW_APPS_BUTTON = "show_apps_button"

    fun getShowAppLabels(context: Context): Boolean = prefs(context).getBoolean(KEY_SHOW_APP_LABELS, true)

    fun setShowAppLabels(context: Context, show: Boolean) {
        prefs(context).edit().putBoolean(KEY_SHOW_APP_LABELS, show).apply()
    }

    fun getShowAppsButton(context: Context): Boolean = prefs(context).getBoolean(KEY_SHOW_APPS_BUTTON, true)

    fun setShowAppsButton(context: Context, show: Boolean) {
        prefs(context).edit().putBoolean(KEY_SHOW_APPS_BUTTON, show).apply()
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
