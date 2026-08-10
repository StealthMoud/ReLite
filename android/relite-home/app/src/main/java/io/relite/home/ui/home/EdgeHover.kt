package io.relite.home.ui.home

/**
 * Section 2-3 (v0.4.0 cross-page drag): pure geometry for "is a drag
 * pointer currently hovering the left/right edge zone." Kept free of any
 * Android view/timer types so it's testable without instrumentation —
 * only the actual Handler-based debounce built on top of this (in
 * [HomePageFragment]) needs a device/emulator to exercise.
 */
object EdgeHover {

    /** -1 = hovering the left edge zone, +1 = the right edge zone, 0 = neither. */
    fun directionFor(rawX: Float, screenWidth: Int, edgeZonePx: Float): Int = when {
        screenWidth <= 0 || edgeZonePx <= 0 -> 0
        rawX < edgeZonePx -> -1
        rawX > screenWidth - edgeZonePx -> 1
        else -> 0
    }
}
