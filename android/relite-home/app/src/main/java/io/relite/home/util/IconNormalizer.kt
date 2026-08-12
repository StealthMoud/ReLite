package io.relite.home.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Path
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable

/**
 * Renders a [Drawable] into a bitmap of exactly [sizePx] pixels, masked to a
 * single consistent icon silhouette.
 *
 * ## Why a shared mask at all
 *
 * This is the most recognizable thing about a One UI home screen, and the
 * thing whose absence made ReLite read as "not Samsung" no matter how
 * correct its margins and colors were: **every** app icon is presented in
 * the same rounded-square silhouette. Samsung's design guide describes it
 * directly — "One UI app icons have square backgrounds with smooth rounded
 * corners and outlines. Rounded squares allow enough room to showcase
 * easily-recognizable symbols, while the curved corners make the icons seem
 * softer and more inviting" (Visual Design, Iconography).
 *
 * Before this, icons were passed through untouched: an adaptive icon got
 * whatever mask the OEM system happened to apply, and a legacy icon got no
 * mask at all. On the RMX5303 that produced a drawer of visibly mixed
 * shapes — hard squares next to circles next to differing corner radii —
 * which is precisely what One UI does not look like.
 *
 * ## The shape
 *
 * A true **squircle** (superellipse), not a plain rounded rectangle. The
 * difference is the whole point: a rounded rect meets its straight edge at
 * an abrupt curvature change, while a squircle's curvature varies
 * continuously, which is what reads as "soft" rather than "a rectangle with
 * its corners cut". Approximated here with cubic Béziers, the standard
 * technique. [CORNER_RATIO] is ReLite's own value — Samsung publishes the
 * shape's description but not its corner geometry.
 *
 * ## Two icon kinds, handled differently
 *
 * - **Adaptive** ([AdaptiveIconDrawable]): its background and foreground
 *   layers are drawn *manually* rather than via `draw()`. `draw()` applies
 *   the system's own mask, which is exactly what this class exists to
 *   override. The layers are laid out on the spec's 108dp canvas with the
 *   inner 72dp as the visible viewport, hence [ADAPTIVE_VIEWPORT_SCALE].
 * - **Legacy**: has no separate background layer, so masking it directly
 *   would slice content off an already full-bleed square artwork. It is
 *   instead composited onto a neutral plate and inset ([LEGACY_ICON_SCALE]),
 *   which is how One UI presents non-adaptive icons — and which also fixes
 *   the older problem this class was written for, that a legacy icon drawn
 *   at full bounds reads heavier than an adaptive one beside it.
 */
object IconNormalizer {

    fun renderToBitmap(source: Drawable, sizePx: Int): Bitmap? {
        if (sizePx <= 0) return null
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val mask = squirclePath(sizePx.toFloat())
        canvas.save()
        canvas.clipPath(mask)

        if (source is AdaptiveIconDrawable) {
            drawAdaptive(source, canvas, sizePx)
        } else {
            drawLegacy(source, canvas, sizePx)
        }

        canvas.restore()
        return bitmap
    }

    /**
     * Draws an adaptive icon's own layers into [canvas], scaled so the
     * spec's 72dp-of-108dp safe viewport fills the requested size. The
     * background layer can legitimately be null (a foreground-only adaptive
     * icon), in which case the plate below stands in so the silhouette is
     * still filled rather than showing a transparent corner.
     */
    private fun drawAdaptive(source: AdaptiveIconDrawable, canvas: Canvas, sizePx: Int) {
        val overscan = sizePx * (ADAPTIVE_VIEWPORT_SCALE - 1f) / 2f
        val left = -overscan.toInt()
        val top = -overscan.toInt()
        val right = (sizePx + overscan).toInt()
        val bottom = (sizePx + overscan).toInt()

        val background = source.background
        if (background != null) {
            background.setBounds(left, top, right, bottom)
            background.draw(canvas)
        } else {
            canvas.drawColor(PLATE_COLOR)
        }
        source.foreground?.let {
            it.setBounds(left, top, right, bottom)
            it.draw(canvas)
        }
    }

    private fun drawLegacy(source: Drawable, canvas: Canvas, sizePx: Int) {
        canvas.drawColor(PLATE_COLOR)
        val insetPx = ((sizePx - sizePx * LEGACY_ICON_SCALE) / 2f)
        val start = insetPx.toInt()
        val end = (sizePx - insetPx).toInt()
        source.setBounds(start, start, end, end)
        source.draw(canvas)
    }

    /**
     * A superellipse approximated with four cubic Béziers. [CORNER_RATIO] of
     * the edge is given to the corner, and [SMOOTHING] controls how far the
     * control points sit along the straight edge — the larger it is, the
     * earlier the curve begins and the more continuous the transition.
     */
    internal fun squirclePath(size: Float): Path {
        val corner = size * CORNER_RATIO
        val handle = corner * SMOOTHING
        return Path().apply {
            moveTo(corner, 0f)
            lineTo(size - corner, 0f)
            cubicTo(size - corner + handle, 0f, size, corner - handle, size, corner)
            lineTo(size, size - corner)
            cubicTo(size, size - corner + handle, size - corner + handle, size, size - corner, size)
            lineTo(corner, size)
            cubicTo(corner - handle, size, 0f, size - corner + handle, 0f, size - corner)
            lineTo(0f, corner)
            cubicTo(0f, corner - handle, corner - handle, 0f, corner, 0f)
            close()
        }
    }

    /**
     * The same silhouette as a standalone drawable, for surfaces that need
     * the shape without an icon in it (folder previews, placeholders).
     */
    fun squircleDrawable(color: Int, sizePx: Int): Drawable? {
        if (sizePx <= 0) return null
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            clipPath(squirclePath(sizePx.toFloat()))
            drawColor(color)
        }
        return BitmapDrawable(null, bitmap)
    }

    /** Package-visible for JVM testing without needing android.graphics. */
    internal const val LEGACY_ICON_SCALE = 0.82f

    /** 108dp adaptive canvas / 72dp visible viewport, per the Android adaptive-icon spec. */
    internal const val ADAPTIVE_VIEWPORT_SCALE = 108f / 72f

    /** Corner size as a fraction of the icon edge. */
    internal const val CORNER_RATIO = 0.26f

    /** How far corner control points reach along the edge; >0 gives the continuous-curvature squircle. */
    internal const val SMOOTHING = 0.42f

    /** Neutral plate behind legacy (and background-less adaptive) icons. */
    private const val PLATE_COLOR = Color.WHITE
}
