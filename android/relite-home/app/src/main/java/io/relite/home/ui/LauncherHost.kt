package io.relite.home.ui

import android.view.View

/**
 * The contract a home-page/drawer/folder fragment uses to reach
 * [MainActivity] instead of holding activity-set lambda fields directly.
 *
 * Section 5-10 (v0.5.0 completion pass): fragments restored by
 * [androidx.viewpager2.adapter.FragmentStateAdapter] after process death
 * are *reused*, not recreated via `createFragment` — so a lambda field only
 * ever wired from `createFragment`'s configure block stays null forever on
 * a restored page, silently breaking every tap/drag on it after the process
 * comes back. Resolving the host by casting `requireActivity()` instead
 * works on every call, restored or not, because Android always reconnects
 * a fragment to its (possibly recreated) host Activity before `onAttach`.
 */
interface LauncherHost {
    fun launchComponent(componentKey: String)
    fun openFolder(folderId: String)
    fun workspaceChanged(targetPage: Int? = null)
    fun requestWidgetPicker()
    fun requestHomeEditMode()
    fun beginDrag(source: View): View
    fun moveDrag(proxy: View, dx: Float, dy: Float)
    fun endDrag(proxy: View)
    fun requestAdjacentPage(direction: Int): Int
}

/** Fragments call this from [androidx.fragment.app.Fragment.requireActivity] rather than storing the host. */
fun androidx.fragment.app.Fragment.launcherHost(): LauncherHost = requireActivity() as LauncherHost
