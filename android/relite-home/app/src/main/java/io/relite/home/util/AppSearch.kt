package io.relite.home.util

import io.relite.home.data.AppEntry
import io.relite.home.data.DrawerFolder
import io.relite.home.data.DrawerItem
import java.util.Locale

/**
 * Local, offline app-drawer search and alphabetical sort. Deliberately
 * has no network dependency (master plan section 19: "Local application
 * search only. No network service.").
 *
 * Section 85-88: deterministic tiered scoring (exact > full-label prefix >
 * any-word prefix > every query token present somewhere, in any order),
 * alphabetical as the tie-break within a tier. `String.lowercase(Locale.ROOT)`
 * plus ordinary `startsWith`/`contains`/`split` are all Unicode-safe already
 * (no crash on accented, RTL, or emoji labels) — this file doesn't need any
 * script-specific handling to satisfy that requirement.
 */
object AppSearch {

    private val WHITESPACE = Regex("\\s+")

    private const val SCORE_EXACT = 500
    private const val SCORE_FULL_PREFIX = 400
    private const val SCORE_WORD_PREFIX = 300
    private const val SCORE_ALL_TOKENS = 200

    fun alphabetical(apps: List<AppEntry>): List<AppEntry> =
        apps.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })

    /**
     * Section 85-92 (v0.5.0): the Apps screen's Custom order — every
     * component key in [order] that's still installed, in that sequence,
     * followed by any newly-installed app not yet in the stored order
     * (alphabetically, appended at the end) rather than dropping it from
     * the list entirely.
     */
    fun customOrder(apps: List<AppEntry>, order: List<String>): List<AppEntry> {
        val byKey = apps.associateBy { it.componentKey }
        val ordered = order.mapNotNull { byKey[it] }
        val orderedKeys = ordered.map { it.componentKey }.toSet()
        val remainder = alphabetical(apps.filterNot { it.componentKey in orderedKeys })
        return ordered + remainder
    }

    /**
     * Section 6 (v0.5.0 completion pass): same slot-filling logic as
     * [customOrder], but folder slots (see [AppsPreference.FOLDER_SLOT_PREFIX])
     * become a [DrawerItem.FolderItem] instead of being silently dropped, and
     * every app already claimed by a folder is excluded from both the
     * ordered and the alphabetical-remainder passes — it's only reachable
     * by opening that folder, never as a second top-level tile.
     */
    fun customOrderItems(apps: List<AppEntry>, order: List<String>, folders: List<DrawerFolder>): List<DrawerItem> {
        val byKey = apps.associateBy { it.componentKey }
        val foldersById = folders.associateBy { it.id }
        val claimedByFolders = folders.flatMap { it.memberComponentKeys }.toSet()

        val ordered = order.mapNotNull { slot ->
            if (slot.startsWith(AppsPreference.FOLDER_SLOT_PREFIX)) {
                foldersById[slot.removePrefix(AppsPreference.FOLDER_SLOT_PREFIX)]?.let { DrawerItem.FolderItem(it) }
            } else {
                byKey[slot]?.takeIf { it.componentKey !in claimedByFolders }?.let { DrawerItem.AppItem(it) }
            }
        }
        val orderedAppKeys = ordered.filterIsInstance<DrawerItem.AppItem>().map { it.entry.componentKey }.toSet()
        val remainder = alphabetical(
            apps.filterNot { it.componentKey in orderedAppKeys || it.componentKey in claimedByFolders },
        ).map { DrawerItem.AppItem(it) }
        return ordered + remainder
    }

    fun search(apps: List<AppEntry>, query: String): List<AppEntry> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return alphabetical(apps)

        val normalizedQuery = trimmed.lowercase(Locale.ROOT)
        val queryTokens = normalizedQuery.split(WHITESPACE).filter { it.isNotEmpty() }

        return apps
            .map { app -> app to scoreOf(app.label.lowercase(Locale.ROOT), normalizedQuery, queryTokens) }
            .filter { (_, score) -> score > 0 }
            .sortedWith(
                compareByDescending<Pair<AppEntry, Int>> { it.second }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.first.label },
            )
            .map { it.first }
    }

    private fun scoreOf(label: String, normalizedQuery: String, queryTokens: List<String>): Int {
        if (label == normalizedQuery) return SCORE_EXACT
        if (label.startsWith(normalizedQuery)) return SCORE_FULL_PREFIX

        val labelWords = label.split(WHITESPACE).filter { it.isNotEmpty() }
        if (labelWords.any { it.startsWith(normalizedQuery) }) return SCORE_WORD_PREFIX

        // Every token of the query must appear somewhere in the label, regardless
        // of order — "google ph" and "ph google" both match "Google Photos", and a
        // single-token query that's merely a mid-word substring lands here too.
        if (queryTokens.isNotEmpty() && queryTokens.all { token -> label.contains(token) }) return SCORE_ALL_TOKENS

        return 0
    }
}
