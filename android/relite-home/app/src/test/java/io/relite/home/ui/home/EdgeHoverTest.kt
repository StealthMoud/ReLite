package io.relite.home.ui.home

import org.junit.Assert.assertEquals
import org.junit.Test

class EdgeHoverTest {

    @Test
    fun `center of the screen is neither edge`() {
        assertEquals(0, EdgeHover.directionFor(500f, 1000, 40f))
    }

    @Test
    fun `inside the left zone reports -1`() {
        assertEquals(-1, EdgeHover.directionFor(10f, 1000, 40f))
    }

    @Test
    fun `inside the right zone reports +1`() {
        assertEquals(1, EdgeHover.directionFor(990f, 1000, 40f))
    }

    @Test
    fun `exactly on the zone boundary is not yet hovering`() {
        assertEquals(0, EdgeHover.directionFor(40f, 1000, 40f))
        assertEquals(0, EdgeHover.directionFor(960f, 1000, 40f))
    }

    @Test
    fun `a zero or negative screen width or edge zone never crashes and reports no hover`() {
        assertEquals(0, EdgeHover.directionFor(10f, 0, 40f))
        assertEquals(0, EdgeHover.directionFor(10f, 1000, 0f))
    }

    @Test
    fun `an off-screen touch coordinate still resolves to the nearer edge without crashing`() {
        assertEquals(-1, EdgeHover.directionFor(-5f, 1000, 40f))
        assertEquals(1, EdgeHover.directionFor(1005f, 1000, 40f))
    }
}
