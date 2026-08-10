package io.relite.home.data

/**
 * The rectangle of cells a workspace item occupies on one page (section 19
 * of the v0.4.0 plan). An [WorkspaceItem.AppIcon]/[WorkspaceItem.FolderIcon]
 * is always 1x1; a [WorkspaceItem.WidgetIcon] can span multiple
 * columns/rows. Collision checking (add/move/resize) operates on rectangle
 * intersection, not single-cell equality — the previous implementation
 * only ever compared `position == position`, so two overlapping widgets
 * (or a widget overlapping a 1x1 icon) were never actually detected as
 * conflicting (section 20).
 */
data class GridRect(
    val page: Int,
    val column: Int,
    val row: Int,
    val spanColumns: Int,
    val spanRows: Int,
) {
    val lastColumn: Int get() = column + spanColumns - 1
    val lastRow: Int get() = row + spanRows - 1

    /** True if every cell of this rectangle is within a [spec]'s bounds on a valid page. */
    fun isWithinBounds(spec: LauncherGridSpec, pageCount: Int): Boolean {
        if (spanColumns <= 0 || spanRows <= 0) return false
        if (page < 0 || page >= pageCount) return false
        if (column < 0 || row < 0) return false
        return lastColumn < spec.columns && lastRow < spec.rows
    }

    /** True if `this` and [other] share at least one cell on the same page. */
    fun intersects(other: GridRect): Boolean {
        if (page != other.page) return false
        val columnsOverlap = column <= other.lastColumn && other.column <= lastColumn
        val rowsOverlap = row <= other.lastRow && other.row <= lastRow
        return columnsOverlap && rowsOverlap
    }

    companion object {
        /** The rectangle a [WorkspaceItem] occupies — 1x1 for an app icon or compact folder, 2x2 for an expanded folder, its own span for a widget. */
        fun of(item: WorkspaceItem): GridRect {
            val (spanColumns, spanRows) = when (item) {
                is WorkspaceItem.WidgetIcon -> item.spanColumns to item.spanRows
                is WorkspaceItem.FolderIcon -> if (item.size == FolderSize.EXPANDED) 2 to 2 else 1 to 1
                is WorkspaceItem.AppIcon -> 1 to 1
            }
            return GridRect(item.position.page, item.position.column, item.position.row, spanColumns, spanRows)
        }

        fun of(position: GridPosition, spanColumns: Int, spanRows: Int): GridRect =
            GridRect(position.page, position.column, position.row, spanColumns, spanRows)
    }
}
