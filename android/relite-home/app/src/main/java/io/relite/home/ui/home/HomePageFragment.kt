package io.relite.home.ui.home

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.PopupMenu
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.relite.home.R
import io.relite.home.ReliteHomeApplication
import io.relite.home.data.WorkspaceItem

/** One page of the home-screen workspace grid. */
class HomePageFragment : Fragment(R.layout.fragment_home_page) {

    var onAppLaunch: ((String) -> Unit)? = null
    var onFolderOpen: ((WorkspaceItem.FolderIcon) -> Unit)? = null
    var onWorkspaceChanged: (() -> Unit)? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val pageIndex = requireArguments().getInt(ARG_PAGE_INDEX)
        val app = requireActivity().application as ReliteHomeApplication

        val pageItems = app.workspaceController.current().items.filter { it.position.page == pageIndex }
        val labelsByComponentKey = app.appRepository.loadAll().associateBy { it.componentKey }

        val adapter = WorkspacePageAdapter(
            items = pageItems,
            iconCache = app.iconCache,
            labelFor = { item -> labelFor(item, labelsByComponentKey) },
            onClick = { item ->
                when (item) {
                    is WorkspaceItem.AppIcon -> onAppLaunch?.invoke(item.componentKey)
                    is WorkspaceItem.FolderIcon -> onFolderOpen?.invoke(item)
                    is WorkspaceItem.WidgetIcon -> Unit // widgets render as live overlay views, not grid taps
                }
            },
            onLongClick = { item, v -> showLongPressMenu(app, item, v); true },
        )

        val recycler = view.findViewById<RecyclerView>(R.id.page_recycler)
        recycler.layoutManager = GridLayoutManager(requireContext(), COLUMN_COUNT)
        recycler.adapter = adapter
    }

    /**
     * Minimum viable "Remove from Home" / "App info" affordance (section
     * 15, v0.2.0). Folders and widgets get "Remove from Home" only —
     * renaming/resizing exist at the WorkspaceController level
     * (renameFolder/resizeWidget) but aren't wired to a UI gesture in
     * this pass.
     */
    private fun showLongPressMenu(app: ReliteHomeApplication, item: WorkspaceItem, anchor: View) {
        PopupMenu(requireContext(), anchor).apply {
            menu.add(getString(R.string.action_remove_from_home))
            if (item is WorkspaceItem.AppIcon) {
                menu.add(getString(R.string.action_app_info))
            }
            setOnMenuItemClickListener { menuItem ->
                when (menuItem.title) {
                    getString(R.string.action_remove_from_home) -> {
                        app.workspaceController.removeItem(item.id)
                        onWorkspaceChanged?.invoke()
                        true
                    }
                    getString(R.string.action_app_info) -> {
                        if (item is WorkspaceItem.AppIcon) {
                            openAppInfo(item.componentKey.substringBefore("/"))
                        }
                        true
                    }
                    else -> false
                }
            }
        }.show()
    }

    private fun openAppInfo(packageName: String) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
        startActivity(intent)
    }

    private fun labelFor(item: WorkspaceItem, labels: Map<String, io.relite.home.data.AppEntry>): String =
        when (item) {
            is WorkspaceItem.AppIcon -> labels[item.componentKey]?.label ?: ""
            is WorkspaceItem.FolderIcon -> item.label
            is WorkspaceItem.WidgetIcon -> ""
        }

    companion object {
        private const val ARG_PAGE_INDEX = "page_index"
        private const val COLUMN_COUNT = 4

        fun newInstance(pageIndex: Int): HomePageFragment = HomePageFragment().apply {
            arguments = Bundle().apply { putInt(ARG_PAGE_INDEX, pageIndex) }
        }
    }
}
