package io.relite.home.util

import android.content.Context
import android.content.SharedPreferences
import io.relite.home.data.DrawerFolder
import org.json.JSONArray
import org.json.JSONObject

/**
 * Section 85-95 (v0.5.0): the two Apps screen sort modes current One UI
 * offers via its own "Sort" menu — see docs/design/one-ui-current-reference.md.
 * CUSTOM pages horizontally with a user-reorderable sequence; ALPHABETICAL
 * scrolls vertically and is always alphabetically derived (not reorderable),
 * matching the axis coupling described there. CUSTOM is the default.
 */
enum class AppsSortMode {
    CUSTOM,
    ALPHABETICAL,
}

/**
 * Persists the Apps screen's sort mode and, for [AppsSortMode.CUSTOM], the
 * user's drag-reordered sequence of component keys — local-only, same as
 * [ThemePreference], no account/cloud sync.
 */
object AppsPreference {

    private const val PREFS_NAME = "relite_apps_prefs"
    private const val KEY_SORT_MODE = "apps_sort_mode"
    private const val KEY_CUSTOM_ORDER = "apps_custom_order"
    private const val KEY_FOLDERS = "apps_folders"

    /**
     * Section 6 (v0.5.0 completion pass): a slot in [getCustomOrder] is
     * either a plain component key (an ungrouped app) or, prefixed with
     * this marker, a [DrawerFolder] id — letting a folder occupy a single
     * position in the existing order sequence without a data-model change
     * to the order storage itself.
     */
    internal const val FOLDER_SLOT_PREFIX = "folder:"

    fun getSortMode(context: Context): AppsSortMode = parseSortMode(prefs(context).getString(KEY_SORT_MODE, null))

    /** Pure parsing logic, split out so it's testable without a Context/SharedPreferences. */
    internal fun parseSortMode(stored: String?): AppsSortMode {
        if (stored == null) return AppsSortMode.CUSTOM
        return runCatching { AppsSortMode.valueOf(stored) }.getOrDefault(AppsSortMode.CUSTOM)
    }

    fun setSortMode(context: Context, mode: AppsSortMode) {
        prefs(context).edit().putString(KEY_SORT_MODE, mode.name).apply()
    }

    fun getCustomOrder(context: Context): List<String> = decodeOrder(prefs(context).getString(KEY_CUSTOM_ORDER, null))

    /** Newline-delimited component keys — safe since `package/activity` component syntax never contains a newline. */
    internal fun decodeOrder(stored: String?): List<String> =
        stored?.split("\n")?.filter { it.isNotEmpty() } ?: emptyList()

    internal fun encodeOrder(order: List<String>): String = order.joinToString("\n")

    fun setCustomOrder(context: Context, order: List<String>) {
        prefs(context).edit().putString(KEY_CUSTOM_ORDER, encodeOrder(order)).apply()
    }

    fun getFolders(context: Context): List<DrawerFolder> = decodeFolders(prefs(context).getString(KEY_FOLDERS, null))

    fun setFolders(context: Context, folders: List<DrawerFolder>) {
        prefs(context).edit().putString(KEY_FOLDERS, encodeFolders(folders)).apply()
    }

    internal fun decodeFolders(stored: String?): List<DrawerFolder> {
        if (stored.isNullOrEmpty()) return emptyList()
        val array = runCatching { JSONArray(stored) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            val obj = array.optJSONObject(i) ?: return@mapNotNull null
            val id = obj.optString("id").ifEmpty { return@mapNotNull null }
            val label = obj.optString("label").ifEmpty { return@mapNotNull null }
            val members = obj.optJSONArray("members") ?: JSONArray()
            val memberKeys = (0 until members.length()).map { members.getString(it) }
            DrawerFolder(id, label, memberKeys)
        }
    }

    internal fun encodeFolders(folders: List<DrawerFolder>): String {
        val array = JSONArray()
        folders.forEach { folder ->
            val obj = JSONObject()
                .put("id", folder.id)
                .put("label", folder.label)
                .put("members", JSONArray(folder.memberComponentKeys))
            array.put(obj)
        }
        return array.toString()
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
