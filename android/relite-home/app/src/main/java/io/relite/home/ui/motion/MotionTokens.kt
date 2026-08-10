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
}
