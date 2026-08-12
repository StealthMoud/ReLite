package io.relite.home.ui.home

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import io.relite.home.R

/**
 * Page indicator. Deliberately a plain Canvas draw instead of a dependency
 * on a third-party indicator library — a handful of shapes doesn't need one.
 *
 * The active page is drawn as an elongated **pill**, not a larger dot: that
 * is the shape One UI uses, and it also reads as "you are here, and there
 * is more either side" far better than a same-shaped dot in a different
 * colour. Over an arbitrary wallpaper the inactive dots additionally need
 * to survive both light and dark backdrops, hence the white/translucent
 * pairing rather than the previous theme-coloured one, which disappeared
 * entirely against a matching wallpaper.
 */
class PageIndicatorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var pageCount: Int = 1
        set(value) { field = value; invalidate() }

    var currentPage: Int = 0
        set(value) { field = value; invalidate() }

    private val dotRadius = resources.getDimension(R.dimen.page_indicator_dot_radius)
    private val dotSpacing = resources.getDimension(R.dimen.page_indicator_dot_spacing)

    private val activePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = resources.getColor(R.color.page_indicator_active, context.theme)
    }
    private val inactivePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = resources.getColor(R.color.page_indicator_inactive, context.theme)
    }

    /**
     * Switches to the on-surface palette for an indicator that draws over
     * one of the app's own opaque backgrounds (the Apps screen) rather than
     * over the wallpaper (Home).
     *
     * The default wallpaper-safe white is deliberately fixed rather than
     * theme-derived, which is correct on Home but would render an invisible
     * white-on-white indicator on the Apps screen in the light theme.
     */
    fun useOnSurfaceColors() {
        activePaint.color = resources.getColor(R.color.page_indicator_active_on_surface, context.theme)
        inactivePaint.color = resources.getColor(R.color.page_indicator_inactive_on_surface, context.theme)
        invalidate()
    }

    /** Extra length the active pill gains over a plain dot's diameter. */
    private val activeExtent = dotRadius * 2.4f

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (pageCount <= 1) return

        // The active pill is wider than a dot, so the run's total width is
        // the dots' spacing plus that extra — computed rather than assumed,
        // or the whole row drifts off-centre as soon as it isn't page 0.
        val totalWidth = (pageCount - 1) * dotSpacing + activeExtent
        var x = (width / 2f) - (totalWidth / 2f)
        val y = height / 2f

        for (page in 0 until pageCount) {
            if (page == currentPage) {
                canvas.drawRoundRect(
                    x - dotRadius,
                    y - dotRadius,
                    x + dotRadius + activeExtent,
                    y + dotRadius,
                    dotRadius,
                    dotRadius,
                    activePaint,
                )
                x += activeExtent
            } else {
                canvas.drawCircle(x, y, dotRadius, inactivePaint)
            }
            x += dotSpacing
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val height = (dotRadius * 4).toInt()
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), height)
    }
}
