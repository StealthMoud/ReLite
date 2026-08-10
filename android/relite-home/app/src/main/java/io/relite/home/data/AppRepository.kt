package io.relite.home.data

import android.content.Context
import android.content.pm.LauncherApps
import android.os.Handler
import android.os.Looper
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
    private val mainHandler = Handler(Looper.getMainLooper())
    // Section 46 (v0.4.1): ReLite Home's own HOME activity is not
    // CATEGORY_LAUNCHER (see AndroidManifest.xml), so LauncherApps already
    // excludes it from getActivityList(); this is a second, explicit line
    // of defense in case that ever changes.
    private val ownPackageName = appContext.packageName

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

    /**
     * Section 17/18 (v0.4.1): delivered on the main thread (LauncherApps.Callback
     * itself already fires there, but this makes the contract explicit rather
     * than relying on caller registration order) and iterates a snapshot —
     * a listener unsubscribing mid-dispatch (e.g. a Fragment torn down as a
     * direct result of the very change being delivered) must not throw
     * ConcurrentModificationException or skip a still-registered listener.
     */
    private fun notifyChanged() {
        val runDispatch = {
            val snapshot = listeners.toList()
            snapshot.forEach { it() }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) runDispatch() else mainHandler.post(runDispatch)
    }

    fun loadAll(): List<AppEntry> =
        launcherApps.getActivityList(null, user)
            .asSequence()
            .filter { it.applicationInfo.packageName != ownPackageName }
            .map { info ->
                AppEntry(
                    packageName = info.applicationInfo.packageName,
                    activityName = info.componentName.className,
                    label = info.label.toString(),
                )
            }
            .toList()
}
