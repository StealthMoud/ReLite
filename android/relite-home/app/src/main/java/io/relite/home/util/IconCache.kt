package io.relite.home.util

import android.content.ComponentName
import android.content.pm.LauncherApps
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Process

/**
 * Bounded, byte-size-aware icon cache.
 *
 * Real-device finding (RMX5303, 2026-08-08): the first version of this
 * cache stored the raw Drawable returned by `LauncherActivityInfo.getIcon()`
 * and bounded the LruCache by *entry count* (200), not memory. On this
 * device that Drawable is a full-resolution AdaptiveIconDrawable — caching
 * even a few dozen of them pushed ReLite Home's Native Heap to ~136 MB
 * (`dumpsys meminfo io.relite.home`), roughly 2x the stock launcher's
 * total PSS. Rendering each icon once into a bitmap sized to what's
 * actually displayed, normalized via [IconNormalizer], and bounding the
 * cache by real byte size, is what "cache app icons responsibly" (master
 * plan section 20) has to mean in practice, not just "put a number on the
 * LruCache".
 *
 * Section 9 (v0.5.0 completion pass): [get] takes the pixel size per call
 * rather than a single value fixed at construction, so the Home Settings
 * icon-size preference can change at runtime without recreating this
 * process-wide singleton. The cache key includes the size, so switching
 * sizes doesn't invalidate anything — the previous size's entries simply
 * age out of the byte budget like any other unused entry.
 */
class IconCache(
    private val launcherApps: LauncherApps,
    maxBytes: Int = DEFAULT_MAX_BYTES,
) {

    private val cache = BoundedByteCache<String, Bitmap>(maxBytes) { it.byteCount }

    fun get(packageName: String, activityName: String, sizePx: Int): Drawable? {
        val key = "$packageName/$activityName@$sizePx"
        cache.get(key)?.let { return BitmapDrawable(null, it) }

        val component = ComponentName(packageName, activityName)
        val info = launcherApps
            .getActivityList(packageName, Process.myUserHandle())
            .firstOrNull { it.componentName == component }
            ?: return null

        val bitmap = IconNormalizer.renderToBitmap(info.getIcon(0), sizePx) ?: return null
        cache.put(key, bitmap)
        return BitmapDrawable(null, bitmap)
    }

    fun invalidate(packageName: String) {
        val prefix = "$packageName/"
        val staleKeys = cache.keys().filter { it.startsWith(prefix) }
        staleKeys.forEach { cache.remove(it) }
    }

    /** Drop every cached icon — called from [io.relite.home.ReliteHomeApplication]'s
     * onTrimMemory() under real memory pressure. Icons are cheap to
     * re-render on demand, so there's no reason to hold onto them once the
     * system says memory is tight. */
    fun clear() {
        cache.clear()
    }

    companion object {
        // ~6 MB: enough for several screens' worth of icons at typical
        // launcher icon sizes without letting the cache grow unbounded.
        private const val DEFAULT_MAX_BYTES = 6 * 1024 * 1024
    }
}
