package io.relite.home.ui.home

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import io.relite.home.data.LauncherGridSpec
import kotlin.math.max
import kotlin.math.min

/**
 * A real cell-aware layout for one home-screen page (master plan v0.4.0,
 * section 26): every child occupies the exact pixel rectangle its
 * [LayoutParams] column/row/span describe, computed against a fixed
 * [LauncherGridSpec] cell size — unlike the RecyclerView+GridLayoutManager
 * approach it replaces, which only ever placed items in list order and
 * ignored each item's actual persisted (column, row) and a widget's span
 * entirely.
 */
class WorkspaceGridLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : ViewGroup(context, attrs) {

    var gridSpec: LauncherGridSpec = LauncherGridSpec.RMX5303
        set(value) {
            field = value
            requestLayout()
        }

    private var cellWidth = 0
    private var cellHeight = 0

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        val usableWidth = max(0, width - paddingLeft - paddingRight)
        val usableHeight = max(0, height - paddingTop - paddingBottom)
        cellWidth = usableWidth / max(1, gridSpec.columns)
        cellHeight = usableHeight / max(1, gridSpec.rows)

        for (i in 0 until childCount) {
            val child = getChildAt(i)
            val lp = child.layoutParams as LayoutParams
            val spanCols = min(lp.spanColumns, gridSpec.columns)
            val spanRows = min(lp.spanRows, gridSpec.rows)
            val childWidthSpec = MeasureSpec.makeMeasureSpec(cellWidth * spanCols, MeasureSpec.EXACTLY)
            val childHeightSpec = MeasureSpec.makeMeasureSpec(cellHeight * spanRows, MeasureSpec.EXACTLY)
            child.measure(childWidthSpec, childHeightSpec)
        }

        setMeasuredDimension(width, height)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            val lp = child.layoutParams as LayoutParams
            val left = paddingLeft + lp.column * cellWidth
            val top = paddingTop + lp.row * cellHeight
            child.layout(left, top, left + child.measuredWidth, top + child.measuredHeight)
        }
    }

    /** Pixel rectangle a cell at (column, row) with the given span currently occupies — used by drag/drop hit-testing. */
    fun cellRectPx(column: Int, row: Int, spanColumns: Int = 1, spanRows: Int = 1): android.graphics.Rect {
        val left = paddingLeft + column * cellWidth
        val top = paddingTop + row * cellHeight
        return android.graphics.Rect(left, top, left + cellWidth * spanColumns, top + cellHeight * spanRows)
    }

    /** Converts a raw touch point (in this view's coordinates) into the grid cell under it, or null if outside the grid. */
    fun cellAt(x: Float, y: Float): Pair<Int, Int>? {
        if (cellWidth <= 0 || cellHeight <= 0) return null
        val column = ((x - paddingLeft) / cellWidth).toInt()
        val row = ((y - paddingTop) / cellHeight).toInt()
        if (column < 0 || column >= gridSpec.columns || row < 0 || row >= gridSpec.rows) return null
        return column to row
    }

    override fun generateLayoutParams(attrs: AttributeSet?): ViewGroup.LayoutParams = LayoutParams(0, 0, 1, 1)

    override fun generateLayoutParams(p: ViewGroup.LayoutParams?): ViewGroup.LayoutParams = LayoutParams(0, 0, 1, 1)

    override fun generateDefaultLayoutParams(): ViewGroup.LayoutParams = LayoutParams(0, 0, 1, 1)

    override fun checkLayoutParams(p: ViewGroup.LayoutParams?): Boolean = p is LayoutParams

    fun addCell(child: View, column: Int, row: Int, spanColumns: Int = 1, spanRows: Int = 1) {
        addView(child, LayoutParams(column, row, spanColumns, spanRows))
    }

    class LayoutParams(
        val column: Int,
        val row: Int,
        val spanColumns: Int,
        val spanRows: Int,
    ) : ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT)
}
