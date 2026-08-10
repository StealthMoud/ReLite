package io.relite.home.util

import io.relite.home.data.AppEntry
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
