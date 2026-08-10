package io.relite.home

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.pm.LauncherApps
import io.relite.home.data.AppRepository
import io.relite.home.data.FileStorage
import io.relite.home.data.LauncherGridSpec
import io.relite.home.data.WorkspaceController
import io.relite.home.data.WorkspaceRepository
import io.relite.home.ui.widget.ReliteAppWidgetHost
import io.relite.home.util.IconCache
import io.relite.home.util.ThemePreference
import java.io.File

/**
 * Holds the long-lived, dependency-free singletons the launcher needs.
 * No analytics, no crash reporter, no account/session state — see master
 * plan section 20.
 *
 * [appRepository] and [iconCache] are deliberately owned here, not by any
 * Activity/Fragment: both used to be created per-screen (a new IconCache
 * per drawer fragment, per home page, per folder dialog, each with its own
 * separate byte budget) or stopped from an Activity's onDestroy(), which
 * either wasted memory on duplicate caches or silently broke updates after
 * the first configuration change (section 11/13, v0.2.0).
 */
class ReliteHomeApplication : Application() {

    lateinit var appRepository: AppRepository
        private set

    lateinit var workspaceController: WorkspaceController
        private set

    // Exposed alongside workspaceController for callers that need direct
    // export/import serialization (HomeSettingsActivity) without going
    // through the mutation-tracking layer WorkspaceController adds.
    lateinit var workspaceRepository: WorkspaceRepository
        private set

    lateinit var iconCache: IconCache
        private set

    // Section 48/58 (v0.4.0): exactly one AppWidgetHost for the process's
    // lifetime — a host repeatedly created per screen would allocate a new
    // set of widget ids/bindings instead of reusing the ones already
    // recorded in the workspace.
    lateinit var appWidgetHost: ReliteAppWidgetHost
        private set

    override fun onCreate() {
        super.onCreate()
        // Applied before any Activity is created, so there's no flash of
        // the wrong theme (section 15).
        ThemePreference.applyToProcess(ThemePreference.get(this))

        appRepository = AppRepository(this)
        appRepository.start()

        workspaceRepository = WorkspaceRepository(
            FileStorage(File(filesDir, "workspace.json")),
            GRID_SPEC,
        )
        workspaceController = WorkspaceController(workspaceRepository, GRID_SPEC)
        appRepository.onAppsChanged {
            // A package can disappear (uninstall, or ReLite's own
            // `pm uninstall --user 0`) at any time; drop any shortcut, dock
            // entry, or folder membership pointing at it so the workspace
            // never renders a dead icon (section 20, v0.2.0). Reconciling
            // against every currently-launchable component on each change
            // is simpler and more robust than trying to diff exactly which
            // package went away from a LauncherApps callback's arguments.
            val launchable = appRepository.loadAll().map { it.packageName }.toSet()
            val referenced = workspaceController.current().let { ws ->
                (ws.items.filterIsInstance<io.relite.home.data.WorkspaceItem.AppIcon>().map { it.componentKey } +
                    ws.items.filterIsInstance<io.relite.home.data.WorkspaceItem.FolderIcon>()
                        .flatMap { it.itemComponentKeys } +
                    ws.dockComponentKeys)
                    .map { it.substringBefore("/") }
                    .toSet()
            }
            for (packageName in referenced - launchable) {
                workspaceController.removeShortcutsForPackage(packageName)
            }
        }

        val launcherApps = getSystemService(LauncherApps::class.java)
        val iconSizePx = resources.getDimensionPixelSize(R.dimen.icon_size)
        iconCache = IconCache(launcherApps, iconSizePx)

        appWidgetHost = ReliteAppWidgetHost(this)
    }

    companion object {
        // Section 18 (v0.4.0): the one authoritative grid geometry —
        // WorkspaceController and WorkspaceRepository both consume this
        // same LauncherGridSpec instance, rather than each independently
        // hardcoding column/row/dock-capacity constants.
        val GRID_SPEC = LauncherGridSpec.RMX5303
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // The whole point of a bounded-by-bytes cache is that it never
        // grows unboundedly on its own, but under real memory pressure
        // there's no reason to keep even the bounded amount — icons are
        // cheap to re-render on demand from LauncherApps.
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            iconCache.clear()
        }
    }
}
