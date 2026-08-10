package io.relite.home.util

import android.content.Context
import android.content.SharedPreferences
import kotlin.math.roundToInt

/**
 * Section 10-13 (v0.5.0 completion pass): the Home Settings "App icon size"
 * control. A scale factor applied to the base [io.relite.home.R.dimen.icon_size]
 * rather than a free slider — three concrete, reference-backed steps are
 * enough to matter visually without the extra complexity (and extra
 * icon-cache key fan-out) a continuous range would add.
 */
enum class IconSize(val scaleFactor: Float) {
    SMALL(0.85f),
    DEFAULT(1.0f),
    LARGE(1.15f),
}

object IconSizePreference {

    private const val PREFS_NAME = "relite_home_prefs"
    private const val KEY_ICON_SIZE = "icon_size"

    fun get(context: Context): IconSize = parse(prefs(context).getString(KEY_ICON_SIZE, null))

    fun set(context: Context, size: IconSize) {
        prefs(context).edit().putString(KEY_ICON_SIZE, size.name).apply()
    }

    /** Resolves the real pixel size to render/cache an icon at for the current preference. */
    fun resolvePx(context: Context, basePx: Int): Int = (basePx * get(context).scaleFactor).roundToInt()

    fun parse(stored: String?): IconSize = stored?.let { runCatching { IconSize.valueOf(it) }.getOrNull() } ?: IconSize.DEFAULT

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
