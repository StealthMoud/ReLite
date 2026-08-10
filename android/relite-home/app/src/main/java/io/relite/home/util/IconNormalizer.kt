package io.relite.home.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable

/**
 * Section 8-9 (v0.5.0 completion pass): renders a [Drawable] into a bitmap
 * of exactly [sizePx] pixels, optically balanced across icon shapes rather
 * than just stretching whatever bounds the source declares.
 *
 * [AdaptiveIconDrawable] (API 26+, the vast majority of real launcher
 * icons) already encodes its own safe-zone inset per the public Android
 * spec — drawing it at the full requested bounds is correct as-is. A
 * legacy (non-adaptive) icon has no such built-in inset and, drawn at the
 * same full bounds, reads visually larger/heavier than an adaptive icon
 * sitting next to it in the same grid. [LEGACY_ICON_SCALE] is ReLite's own
 * approximation to compensate — not a value extracted from any Samsung or
 * AOSP source — chosen to visually match a typical adaptive icon's
 * rendered footprint without needing per-icon content analysis.
 */
object IconNormalizer {

    fun renderToBitmap(source: Drawable, sizePx: Int): Bitmap? {
        if (sizePx <= 0) return null
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        if (source is AdaptiveIconDrawable) {
            source.setBounds(0, 0, sizePx, sizePx)
            source.draw(canvas)
        } else {
            val insetPx = ((sizePx - sizePx * LEGACY_ICON_SCALE) / 2f)
            val start = insetPx.toInt()
            val end = (sizePx - insetPx).toInt()
            source.setBounds(start, start, end, end)
            source.draw(canvas)
        }
        return bitmap
    }

    /** Package-visible for JVM testing without needing android.graphics. */
    internal const val LEGACY_ICON_SCALE = 0.82f
}
