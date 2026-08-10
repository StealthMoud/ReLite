package io.relite.home.ui.home

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import io.relite.home.data.GridRect
import io.relite.home.data.HomeGridPreset
import io.relite.home.data.WorkspaceItem

/**
 * Section 7 (v0.5.0 completion pass): a real, model-driven miniature of a
 * Home page's actual item layout, replacing the previous static "1"/"2"
 * numbered chip in the edit-mode page strip (see CHANGELOG's Known gaps).
 * Draws one small filled cell at each item's real grid position — a
 * widget's real multi-cell footprint becomes one larger rect rather than
 * one dot per covered cell, since [GridRect.of] already resolves the
 * correct footprint for every item kind (including an expanded 2x2
 * folder). Purely a live projection of [bind]'s input — this view holds no
 * state of its own beyond what it was last told to draw, so a page whose
 * layout changes just gets rebound and redrawn like any other view.
 */
class EditModePageThumbnailView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private var gridColumns = HomeGridPreset.FOUR_BY_SIX.columns
    private var gridRows = HomeGridPreset.FOUR_BY_SIX.rows
    private var footprints: List<RectF> = emptyList()

    private val cellPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        alpha = CELL_ALPHA
    }

    fun bind(items: List<WorkspaceItem>, grid: HomeGridPreset) {
        gridColumns = grid.columns
        gridRows = grid.rows
        footprints = items.map { item ->
            val rect = GridRect.of(item)
            RectF(
                rect.column.toFloat(),
                rect.row.toFloat(),
                (rect.column + rect.spanColumns).toFloat(),
                (rect.row + rect.spanRows).toFloat(),
            )
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (gridColumns <= 0 || gridRows <= 0 || width <= 0 || height <= 0) return

        // The chip's own background (bg_page_chip/bg_page_chip_default) is a
        // circle, but a real item commonly sits at column/row 0 — drawing
        // the grid across the full square view would place that cell right
        // at a corner, outside the circle. Map the grid onto the largest
        // square inscribed in that circle instead (side = size/sqrt(2)) so
        // every cell, including corners, stays fully visible; clipping to
        // the circle besides is just defense-in-depth, not load-bearing.
        val save = canvas.save()
        canvas.clipPath(Path().apply { addOval(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW) })

        val size = minOf(width, height).toFloat()
        val gridSide = size * INSCRIBED_SQUARE_FRACTION
        val gridMargin = (size - gridSide) / 2f
        val cellWidth = gridSide / gridColumns
        val cellHeight = gridSide / gridRows
        val inset = minOf(cellWidth, cellHeight) * INSET_FRACTION
        val corner = inset
        footprints.forEach { rect ->
            canvas.drawRoundRect(
                gridMargin + rect.left * cellWidth + inset,
                gridMargin + rect.top * cellHeight + inset,
                gridMargin + rect.right * cellWidth - inset,
                gridMargin + rect.bottom * cellHeight - inset,
                corner,
                corner,
                cellPaint,
            )
        }
        canvas.restoreToCount(save)
    }

    companion object {
        private const val CELL_ALPHA = 210
        private const val INSET_FRACTION = 0.14f

        /** 1/sqrt(2) — the side of the largest square inscribed in a unit circle. */
        private const val INSCRIBED_SQUARE_FRACTION = 0.7071f
    }
}
