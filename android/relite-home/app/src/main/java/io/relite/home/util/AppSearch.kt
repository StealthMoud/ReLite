package io.relite.home.util

import io.relite.home.data.AppEntry
import java.util.Locale

/**
 * Local, offline app-drawer search and alphabetical sort. Deliberately
 * has no network dependency (master plan section 19: "Local application
 * search only. No network service.").
 */
object AppSearch {

    fun alphabetical(apps: List<AppEntry>): List<AppEntry> =
        apps.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })

    /**
     * Matches if every whitespace-separated query token is a prefix of the
     * label, or a substring of the label, in that preference order — so
     * "cam" ranks "Camera" above an app that merely contains "cam" mid-word.
     */
    fun search(apps: List<AppEntry>, query: String): List<AppEntry> {
        val trimmed = query.trim().lowercase(Locale.ROOT)
        if (trimmed.isEmpty()) return alphabetical(apps)

        val prefixMatches = mutableListOf<AppEntry>()
        val substringMatches = mutableListOf<AppEntry>()
        for (app in apps) {
            val label = app.label.lowercase(Locale.ROOT)
            when {
                label.startsWith(trimmed) -> prefixMatches.add(app)
                label.contains(trimmed) -> substringMatches.add(app)
            }
        }
        return alphabetical(prefixMatches) + alphabetical(substringMatches)
    }
}
