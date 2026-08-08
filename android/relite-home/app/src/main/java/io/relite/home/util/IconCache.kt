package io.relite.home.util

import android.content.pm.LauncherApps
import android.graphics.drawable.Drawable
import android.os.Process
import android.util.LruCache

/**
 * Bounded in-memory icon cache. Deliberately small and LRU-bounded — the
 * master plan calls out "cache app icons responsibly" as opposed to
 * holding every icon bitmap in memory on a 6 GB device indefinitely.
 */
class IconCache(private val launcherApps: LauncherApps, maxEntries: Int = 200) {

    private val cache = object : LruCache<String, Drawable>(maxEntries) {}

    fun get(packageName: String, activityName: String): Drawable? {
        val key = "$packageName/$activityName"
        cache.get(key)?.let { return it }

        val component = android.content.ComponentName(packageName, activityName)
        val info = launcherApps
            .getActivityList(packageName, Process.myUserHandle())
            .firstOrNull { it.componentName == component }
            ?: return null

        val icon = info.getIcon(0)
        cache.put(key, icon)
        return icon
    }

    fun invalidate(packageName: String) {
        val prefix = "$packageName/"
        val staleKeys = cache.snapshot().keys.filter { it.startsWith(prefix) }
        staleKeys.forEach { cache.remove(it) }
    }
}
