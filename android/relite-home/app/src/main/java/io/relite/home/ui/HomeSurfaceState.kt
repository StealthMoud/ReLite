package io.relite.home.ui

/** Section 3 (v0.5.0 completion pass): the Home<->Apps gesture's explicit state, so a second gesture can never start mid-transition. */
enum class HomeSurfaceState {
    HOME,
    DRAGGING_TO_APPS,
    APPS,
    DRAGGING_TO_HOME,
}
