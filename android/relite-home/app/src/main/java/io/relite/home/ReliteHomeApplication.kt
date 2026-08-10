package io.relite.home

import android.app.Application
import android.appwidget.AppWidgetManager
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

        // Section 7 (v0.5.0): every dependency the reconciliation listener
        // below can touch — workspaceController, iconCache, appWidgetHost —
        // must exist *before* appRepository.start() registers the
        // LauncherApps callback. The previous order started appRepository
        // first; a package broadcast delivered in that window would have
        // hit still-uninitialized `lateinit` properties from the listener
        // closure and crashed the whole process.
        appRepository = AppRepository(this)

        workspaceRepository = WorkspaceRepository(
            FileStorage(File(filesDir, "workspace.json")),
            GRID_SPEC,
        )
        workspaceController = WorkspaceController(workspaceRepository, GRID_SPEC)

        val launcherApps = getSystemService(LauncherApps::class.java)
        val iconSizePx = resources.getDimensionPixelSize(R.dimen.icon_size)
        iconCache = IconCache(launcherApps, iconSizePx)

        appWidgetHost = ReliteAppWidgetHost(this)

        appRepository.onAppsChanged {
            // Section 20/21 (v0.4.1): reconcile against the exact set of
            // currently-launchable package/activity components, not just
            // package names — a package that renamed its launcher activity
            // (still installed) used to leave a dead shortcut pointing at
            // the old activity forever. Reconciling on every change against
            // the full current LauncherApps snapshot is simpler and more
            // robust than diffing which package/activity changed from a
            // single callback's arguments.
            val launchableComponents = appRepository.loadAll().map { it.componentKey }.toSet()
            workspaceController.removeStaleComponents(launchableComponents)

            // Section 8/9 (v0.5.0): a valid widget provider does not need a
            // launcher Activity, so deriving "available provider packages"
            // from LauncherApps (as v0.4.1 did) is simply wrong — a
            // widget-only package with no launcher icon would have its
            // widgets reaped from the workspace on every reconciliation,
            // even though the provider is still installed and bindable.
            // AppWidgetManager.installedProviders is the only authoritative
            // source, compared as exact package/providerClass components.
            val availableProviderComponents = AppWidgetManager.getInstance(this)
                .installedProviders
                .map { it.provider.flattenToString() }
                .toSet()
            val removedWidgetIds = workspaceController.removeWidgetsForMissingProviders(availableProviderComponents)
            removedWidgetIds.forEach { appWidgetHost.removeWidget(it) }

            // Section 19 (v0.4.1): any Changed/Removed/Unavailable package
            // may have altered its icon; a bounded byte-capped cache makes
            // clearing on every reconciliation cheap enough to not need
            // per-package diffing here.
            iconCache.clear()
        }

        appRepository.start()
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
