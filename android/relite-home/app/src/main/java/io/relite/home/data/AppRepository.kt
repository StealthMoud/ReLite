package io.relite.home.data

import android.content.Context
import android.content.pm.LauncherApps
import android.os.Process
import android.os.UserHandle

/**
 * Thin wrapper over LauncherApps: turns the platform API into plain
 * AppEntry values and keeps them updated as packages install/update/remove.
 * Deliberately does the framework-facing work only — matching/sorting logic
 * lives in [io.relite.home.util.AppSearch] where it can be unit tested.
 */
class AppRepository(context: Context) {

    private val appContext = context.applicationContext
    private val launcherApps = appContext.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
    private val user: UserHandle = Process.myUserHandle()

    /** Dispose to stop receiving [AppRepository] change notifications. */
    fun interface Subscription {
        fun dispose()
    }

    private val listeners = mutableListOf<() -> Unit>()

    private val callback = object : LauncherApps.Callback() {
        override fun onPackageAdded(packageName: String?, user: UserHandle?) = notifyChanged()
        override fun onPackageRemoved(packageName: String?, user: UserHandle?) = notifyChanged()
        override fun onPackageChanged(packageName: String?, user: UserHandle?) = notifyChanged()
        override fun onPackagesAvailable(packageNames: Array<out String>?, user: UserHandle?, replacing: Boolean) =
            notifyChanged()
        override fun onPackagesUnavailable(packageNames: Array<out String>?, user: UserHandle?, replacing: Boolean) =
            notifyChanged()
    }

    fun start() {
        launcherApps.registerCallback(callback)
    }

    fun stop() {
        launcherApps.unregisterCallback(callback)
    }

    /**
     * Registers [listener] and returns a [Subscription] the caller must
     * dispose when it no longer wants updates — a fragment view being
     * destroyed and recreated (e.g. every time the app drawer is shown)
     * without disposing its old listener used to grow this list forever,
     * each entry holding a closure over the fragment/adapter it belonged
     * to, so destroyed drawer instances kept receiving — and being kept
     * alive by — every future package-change callback.
     */
    fun onAppsChanged(listener: () -> Unit): Subscription {
        listeners.add(listener)
        return Subscription { listeners.remove(listener) }
    }

    private fun notifyChanged() {
        listeners.forEach { it() }
    }

    fun loadAll(): List<AppEntry> =
        launcherApps.getActivityList(null, user).map { info ->
            AppEntry(
                packageName = info.applicationInfo.packageName,
                activityName = info.componentName.className,
                label = info.label.toString(),
            )
        }
}
