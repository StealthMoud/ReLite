package io.relite.home.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GridRectTest {

    private val spec = LauncherGridSpec(columns = 4, rows = 5, dockCapacity = 5)

    @Test
    fun `identical rectangles intersect`() {
        val a = GridRect(0, 0, 0, 1, 1)
        assertTrue(a.intersects(a.copy()))
    }

    @Test
    fun `adjacent rectangles do not intersect`() {
        val a = GridRect(0, 0, 0, 1, 1)
        val b = GridRect(0, 1, 0, 1, 1)
        assertFalse(a.intersects(b))
        assertFalse(b.intersects(a))
    }

    @Test
    fun `a 2x2 widget overlaps a 1x1 icon anywhere inside it`() {
        val widget = GridRect(0, 0, 0, spanColumns = 2, spanRows = 2)
        assertTrue(widget.intersects(GridRect(0, 0, 0, 1, 1)))
        assertTrue(widget.intersects(GridRect(0, 1, 0, 1, 1)))
        assertTrue(widget.intersects(GridRect(0, 0, 1, 1, 1)))
        assertTrue(widget.intersects(GridRect(0, 1, 1, 1, 1)))
        assertFalse(widget.intersects(GridRect(0, 2, 0, 1, 1)))
        assertFalse(widget.intersects(GridRect(0, 0, 2, 1, 1)))
    }

    @Test
    fun `two widgets overlap only where their rectangles actually share a cell`() {
        val a = GridRect(0, 0, 0, spanColumns = 2, spanRows = 2)
        val touchingCorner = GridRect(0, 1, 1, spanColumns = 2, spanRows = 2)
        val disjoint = GridRect(0, 2, 2, spanColumns = 2, spanRows = 1)
        assertTrue(a.intersects(touchingCorner)) // shares cell (1,1)
        assertFalse(a.intersects(disjoint))
    }

    @Test
    fun `rectangles on different pages never intersect`() {
        val a = GridRect(0, 0, 0, 2, 2)
        val b = GridRect(1, 0, 0, 2, 2)
        assertFalse(a.intersects(b))
    }

    @Test
    fun `bounds check rejects a rectangle running past the right or bottom edge`() {
        assertTrue(GridRect(0, 2, 3, 2, 2).isWithinBounds(spec, pageCount = 1)) // columns 2-3, rows 3-4: fits exactly
        assertFalse(GridRect(0, 3, 3, 2, 2).isWithinBounds(spec, pageCount = 1)) // columns 3-4: column 4 is out of a 4-wide grid
        assertFalse(GridRect(0, 2, 4, 2, 2).isWithinBounds(spec, pageCount = 1)) // rows 4-5: row 5 is out of a 5-tall grid
    }

    @Test
    fun `bounds check rejects a zero or negative span`() {
        assertFalse(GridRect(0, 0, 0, 0, 1).isWithinBounds(spec, pageCount = 1))
        assertFalse(GridRect(0, 0, 0, 1, -1).isWithinBounds(spec, pageCount = 1))
    }

    @Test
    fun `bounds check rejects a page outside pageCount`() {
        assertFalse(GridRect(1, 0, 0, 1, 1).isWithinBounds(spec, pageCount = 1))
        assertFalse(GridRect(-1, 0, 0, 1, 1).isWithinBounds(spec, pageCount = 1))
    }

    @Test
    fun `GridRect of an icon is always 1x1`() {
        val icon = WorkspaceItem.AppIcon("a1", GridPosition(0, 1, 2), "io.relite.a/Main")
        assertTrue(GridRect.of(icon) == GridRect(0, 1, 2, 1, 1))
    }

    @Test
    fun `GridRect of a widget uses its own span`() {
        val widget = WorkspaceItem.WidgetIcon("w1", GridPosition(0, 1, 2), 5, spanColumns = 3, spanRows = 2, providerComponent = "a/B")
        assertTrue(GridRect.of(widget) == GridRect(0, 1, 2, 3, 2))
    }
}
