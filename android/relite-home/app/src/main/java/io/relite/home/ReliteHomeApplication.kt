package io.relite.home

import android.app.Application
import android.appwidget.AppWidgetManager
import android.content.ComponentCallbacks2
import android.content.pm.LauncherApps
import io.relite.home.data.AppRepository
import io.relite.home.data.FileStorage
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

    /**
     * Section 41-42 (v0.5.0): the real measured cell geometry of the last
     * Home page grid to lay out, kept here (not read fresh) because
     * [io.relite.home.ui.widget.WidgetPickerActivity] needs a real cell size
     * to derive a provider's initial span before it — a separate screen with
     * no grid view of its own — has ever shown one. Null only before Home
     * has laid out for the very first time in this process, which cannot
     * happen in practice since the widget picker is only reachable from an
     * already-visible Home page.
     */
    var lastGridMetrics: io.relite.home.ui.home.WorkspaceGridLayout.GridMetrics? = null

    // Section 15 (v0.5.0 completion pass): a tiny, deliberately minimal
    // pub/sub so MainActivity can ask to be refreshed the moment package
    // reconciliation changes the workspace, instead of only picking it up
    // on its next onResume(). Not AppRepository.onAppsChanged itself: this
    // fires strictly *after* this class's own reconciliation has already
    // run, so a listener always sees the already-cleaned-up workspace.
    private val homeRefreshListeners = mutableListOf<() -> Unit>()

    fun addHomeRefreshListener(listener: () -> Unit) {
        homeRefreshListeners.add(listener)
    }

    fun removeHomeRefreshListener(listener: () -> Unit) {
        homeRefreshListeners.remove(listener)
    }

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
            DOCK_CAPACITY,
        )
        workspaceController = WorkspaceController(workspaceRepository, DOCK_CAPACITY)

        val launcherApps = getSystemService(LauncherApps::class.java)
        val iconSizePx = resources.getDimensionPixelSize(R.dimen.icon_size)
        iconCache = IconCache(launcherApps, iconSizePx)

        appWidgetHost = ReliteAppWidgetHost(this)

        appRepository.onAppsChanged { event ->
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

            // Section 14/19 (v0.5.0 completion pass): the event always
            // names the specific package(s) that changed, so only those
            // packages' cached icons need to go — a full clear (still used
            // under real memory pressure, see onTrimMemory) is unnecessary
            // work on every single-package update.
            event.affectedPackages.forEach { iconCache.invalidate(it) }

            // Section 15 (v0.5.0 completion pass): reconciliation above can
            // drop workspace items and widgets while Home is already
            // visible (e.g. a background auto-update) — MainActivity's own
            // subscription (below) refreshes the on-screen grid immediately
            // rather than waiting for the next onResume.
            homeRefreshListeners.toList().forEach { it() }
        }

        appRepository.start()
    }

    companion object {
        // Section 55-57 (v0.5.0): Home grid geometry (columns/rows) is now
        // per-workspace (Workspace.homeGrid, a HomeGridPreset) rather than a
        // single process-wide constant — only dock capacity remains fixed
        // per device here.
        const val DOCK_CAPACITY = 5
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
