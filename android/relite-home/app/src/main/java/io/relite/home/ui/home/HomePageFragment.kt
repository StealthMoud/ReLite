package io.relite.home.ui.home

import android.os.Bundle
import android.view.View
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
    var onIconLongPress: ((WorkspaceItem, View) -> Boolean)? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val pageIndex = requireArguments().getInt(ARG_PAGE_INDEX)
        val app = requireActivity().application as ReliteHomeApplication

        val pageItems = app.workspaceRepository.load().items.filter { it.position.page == pageIndex }
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
            onLongClick = { item, v -> onIconLongPress?.invoke(item, v) ?: false },
        )

        val recycler = view.findViewById<RecyclerView>(R.id.page_recycler)
        recycler.layoutManager = GridLayoutManager(requireContext(), COLUMN_COUNT)
        recycler.adapter = adapter
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
