package io.relite.home.ui.home

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import io.relite.home.R

/**
 * Minimal dot page indicator. Deliberately a plain Canvas draw instead of a
 * dependency on a third-party indicator library — a handful of circles
 * doesn't need one.
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

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (pageCount <= 1) return

        val totalWidth = (pageCount - 1) * dotSpacing
        var x = (width / 2f) - (totalWidth / 2f)
        val y = height / 2f

        for (page in 0 until pageCount) {
            canvas.drawCircle(x, y, dotRadius, if (page == currentPage) activePaint else inactivePaint)
            x += dotSpacing
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val height = (dotRadius * 3).toInt()
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), height)
    }
}
