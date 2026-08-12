package io.relite.home.ui.motion

import android.view.animation.PathInterpolator

/**
 * Section 5/73 (v0.5.0 completion pass): the shared duration/easing values
 * every transition in this launcher should use instead of ad hoc numbers
 * scattered per-screen. The curves are ReLite's own approximation of the
 * commonly documented "standard" / "emphasized" motion shapes described in
 * public Android and One UI design guidance — not extracted from any
 * Samsung asset or proprietary resource.
 */
object MotionTokens {
    const val DURATION_FAST_MS = 150L
    const val DURATION_STANDARD_MS = 200L
    const val DURATION_EMPHASIZED_MS = 350L

    /** Gentle accelerate-then-decelerate — small, local transitions (menus, chip taps). */
    val STANDARD_EASING = PathInterpolator(0.4f, 0f, 0.2f, 1f)

    /** A pronounced decelerate for larger, full-screen transitions (Home<->Apps, edit mode). */
    val EMPHASIZED_EASING = PathInterpolator(0.05f, 0.7f, 0.1f, 1f)

    /**
     * Section 5/73 (v0.5.0 completion pass): the scale a surface grows *from*
     * when it appears anchored to something the user just touched (context
     * menu, folder). Small enough to read as "this came out of that", never
     * so small it reads as a zoom.
     */
    const val POP_IN_START_SCALE = 0.85f

    /**
     * Applies the shared appear transition — grow-and-fade from
     * [POP_IN_START_SCALE] toward the given pivot. Used instead of each
     * surface hand-rolling its own numbers, which is exactly the "no shared
     * motion-token system" gap the parity matrix recorded.
     *
     * The pivot is set by the caller rather than defaulted to centre, so a
     * menu anchored under a long-pressed icon visibly emerges from *that*
     * icon's corner instead of from its own middle.
     */
    fun popIn(view: android.view.View, pivotX: Float, pivotY: Float) {
        view.pivotX = pivotX
        view.pivotY = pivotY
        view.scaleX = POP_IN_START_SCALE
        view.scaleY = POP_IN_START_SCALE
        view.alpha = 0f
        view.animate()
            .scaleX(1f).scaleY(1f).alpha(1f)
            .setDuration(DURATION_FAST_MS)
            .setInterpolator(STANDARD_EASING)
            .start()
    }
}
