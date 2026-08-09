package io.relite.home

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.pm.LauncherApps
import io.relite.home.data.AppRepository
import io.relite.home.data.FileStorage
import io.relite.home.data.WorkspaceRepository
import io.relite.home.util.IconCache
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

    lateinit var workspaceRepository: WorkspaceRepository
        private set

    lateinit var iconCache: IconCache
        private set

    override fun onCreate() {
        super.onCreate()
        appRepository = AppRepository(this)
        appRepository.start()
        workspaceRepository = WorkspaceRepository(FileStorage(File(filesDir, "workspace.json")))

        val launcherApps = getSystemService(LauncherApps::class.java)
        val iconSizePx = resources.getDimensionPixelSize(R.dimen.icon_size)
        iconCache = IconCache(launcherApps, iconSizePx)
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
