package io.relite.home.data

/**
 * Section 11-14 (v0.5.0 completion pass): what actually changed, not just
 * "something changed" — [AppRepository] used to collapse every
 * [android.content.pm.LauncherApps.Callback] method into the same
 * zero-argument notification, which forced every listener to react as if
 * the *whole* app set might be different (a full [AppRepository.loadAll]
 * reload, a full icon-cache clear) even for a single package's update.
 */
sealed interface AppChangeEvent {
    data class Added(val packageName: String) : AppChangeEvent
    data class Removed(val packageName: String) : AppChangeEvent
    data class Changed(val packageName: String) : AppChangeEvent
    data class Available(val packageNames: List<String>) : AppChangeEvent
    data class Unavailable(val packageNames: List<String>) : AppChangeEvent

    /** The package name(s) this event concerns, for targeted icon-cache invalidation. */
    val affectedPackages: List<String> get() = when (this) {
        is Added -> listOf(packageName)
        is Removed -> listOf(packageName)
        is Changed -> listOf(packageName)
        is Available -> packageNames
        is Unavailable -> packageNames
    }
}
