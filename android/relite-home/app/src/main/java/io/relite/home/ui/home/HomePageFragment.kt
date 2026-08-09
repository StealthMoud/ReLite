package io.relite.home.ui.home

import android.appwidget.AppWidgetHostView
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.Menu
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.TextView
import androidx.fragment.app.Fragment
import io.relite.home.R
import io.relite.home.ReliteHomeApplication
import io.relite.home.data.AppEntry
import io.relite.home.data.GridPosition
import io.relite.home.data.WorkspaceItem
import kotlin.math.hypot

/**
 * One page of the home-screen workspace grid — renders directly into a
 * [WorkspaceGridLayout] using each item's real (column, row, span) instead
 * of a RecyclerView + GridLayoutManager that only ever placed cells in list
 * order and ignored a widget's span (section 26 of the v0.4.0 plan).
 */
class HomePageFragment : Fragment(R.layout.fragment_home_page) {

    var onAppLaunch: ((String) -> Unit)? = null
    var onFolderOpen: ((WorkspaceItem.FolderIcon) -> Unit)? = null
    var onWorkspaceChanged: (() -> Unit)? = null

    private lateinit var grid: WorkspaceGridLayout
    private var pageIndex: Int = 0
    private val touchSlop by lazy { ViewConfiguration.get(requireContext()).scaledTouchSlop }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        pageIndex = requireArguments().getInt(ARG_PAGE_INDEX)
        val app = requireActivity().application as ReliteHomeApplication
        grid = view.findViewById(R.id.page_grid)
        grid.gridSpec = app.workspaceController.gridSpec

        val pageItems = app.workspaceController.current().items.filter { it.position.page == pageIndex }
        val labelsByComponentKey = app.appRepository.loadAll().associateBy { it.componentKey }

        for (item in pageItems) {
            val cellView = buildCellView(app, item, labelsByComponentKey)
            val span = spanOf(item)
            grid.addCell(cellView, item.position.column, item.position.row, span.first, span.second)
        }
    }

    private fun spanOf(item: WorkspaceItem): Pair<Int, Int> = when (item) {
        is WorkspaceItem.WidgetIcon -> item.spanColumns to item.spanRows
        is WorkspaceItem.AppIcon, is WorkspaceItem.FolderIcon -> 1 to 1
    }

    private fun buildCellView(
        app: ReliteHomeApplication,
        item: WorkspaceItem,
        labels: Map<String, AppEntry>,
    ): View {
        val cellView: View = when (item) {
            is WorkspaceItem.WidgetIcon -> buildWidgetView(app, item)
            else -> buildIconView(app, item, labels)
        }
        wireInteractions(app, item, cellView)
        return cellView
    }

    private fun buildIconView(app: ReliteHomeApplication, item: WorkspaceItem, labels: Map<String, AppEntry>): View {
        val cellView = LayoutInflater.from(requireContext()).inflate(R.layout.item_workspace_icon, grid, false)
        cellView.findViewById<TextView>(R.id.label).text = labelFor(item, labels)
        cellView.findViewById<ImageView>(R.id.icon).setImageDrawable(iconFor(app, item))
        return cellView
    }

    /** A live [AppWidgetHostView] — not a placeholder icon (section 48/57). */
    private fun buildWidgetView(app: ReliteHomeApplication, item: WorkspaceItem.WidgetIcon): AppWidgetHostView =
        app.appWidgetHost.bindWidgetView(item.appWidgetId)

    private fun iconFor(app: ReliteHomeApplication, item: WorkspaceItem) = when (item) {
        is WorkspaceItem.AppIcon -> {
            val (pkg, activity) = item.componentKey.split("/", limit = 2)
            app.iconCache.get(pkg, activity)
        }
        is WorkspaceItem.FolderIcon -> null // folder icon is drawn from a themed background, not a package icon
        is WorkspaceItem.WidgetIcon -> null
    }

    private fun labelFor(item: WorkspaceItem, labels: Map<String, AppEntry>): String = when (item) {
        is WorkspaceItem.AppIcon -> labels[item.componentKey]?.label ?: ""
        is WorkspaceItem.FolderIcon -> item.label
        is WorkspaceItem.WidgetIcon -> ""
    }

    private fun wireInteractions(app: ReliteHomeApplication, item: WorkspaceItem, cellView: View) {
        cellView.setOnClickListener {
            when (item) {
                is WorkspaceItem.AppIcon -> onAppLaunch?.invoke(item.componentKey)
                is WorkspaceItem.FolderIcon -> onFolderOpen?.invoke(item)
                is WorkspaceItem.WidgetIcon -> Unit
            }
        }
        // Widgets manage their own touch input (scrolling lists, buttons, etc.) —
        // only apps/folders support the long-press-to-drag gesture below.
        if (item is WorkspaceItem.WidgetIcon) return

        var dragArmed = false
        var downRawX = 0f
        var downRawY = 0f

        cellView.setOnLongClickListener {
            dragArmed = true
            cellView.animate().scaleX(1.08f).scaleY(1.08f).alpha(0.85f).setDuration(120).start()
            true
        }
        cellView.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragArmed = false
                    downRawX = event.rawX
                    downRawY = event.rawY
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (dragArmed) {
                        v.translationX = event.rawX - downRawX
                        v.translationY = event.rawY - downRawY
                        updateDropFeedback(app, item, v)
                        true
                    } else {
                        false
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragArmed) {
                        dragArmed = false
                        finishDrag(app, item, v)
                        true
                    } else {
                        false
                    }
                }
                else -> false
            }
        }
    }

    private fun targetCellFor(v: View): Pair<Int, Int>? {
        val gridLocation = IntArray(2)
        grid.getLocationOnScreen(gridLocation)
        val viewLocation = IntArray(2)
        v.getLocationOnScreen(viewLocation)
        // Use the dragged view's current center, not the raw touch point, so
        // a drop is judged by where the icon visually is, not the finger.
        val centerX = viewLocation[0] + v.width / 2f
        val centerY = viewLocation[1] + v.height / 2f
        return grid.cellAt(centerX - gridLocation[0], centerY - gridLocation[1])
    }

    private fun updateDropFeedback(app: ReliteHomeApplication, item: WorkspaceItem, v: View) {
        val cell = targetCellFor(v)
        val valid = cell != null &&
            app.workspaceController.canMoveTo(item.id, GridPosition(pageIndex, cell.first, cell.second))
        v.alpha = if (valid) 0.85f else 0.4f
    }

    private fun finishDrag(app: ReliteHomeApplication, item: WorkspaceItem, v: View) {
        v.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
        val moved = hypot(v.translationX, v.translationY) > touchSlop
        if (!moved) {
            v.translationX = 0f
            v.translationY = 0f
            v.alpha = 1f
            showLongPressMenu(app, item, v)
            return
        }

        val cell = targetCellFor(v)
        val target = cell?.let { GridPosition(pageIndex, it.first, it.second) }
        val ok = target != null && app.workspaceController.moveItem(item.id, target)
        v.alpha = 1f
        if (ok) {
            onWorkspaceChanged?.invoke()
        } else {
            v.animate().translationX(0f).translationY(0f).setDuration(150).start()
        }
    }

    /**
     * Minimum viable "Remove from Home" / "App info" affordance (section
     * 15, v0.2.0), reached now by a long-press that's released without
     * being dragged away. Folders and widgets get "Remove from Home" only —
     * renaming exists at the WorkspaceController level (renameFolder) but
     * isn't wired to a UI gesture in this pass.
     */
    private fun showLongPressMenu(app: ReliteHomeApplication, item: WorkspaceItem, anchor: View) {
        PopupMenu(requireContext(), anchor).apply {
            menu.add(Menu.NONE, MENU_ID_REMOVE_FROM_HOME, Menu.NONE, R.string.action_remove_from_home)
            if (item is WorkspaceItem.AppIcon) {
                menu.add(Menu.NONE, MENU_ID_APP_INFO, Menu.NONE, R.string.action_app_info)
            }
            setOnMenuItemClickListener { menuItem ->
                when (menuItem.itemId) {
                    MENU_ID_REMOVE_FROM_HOME -> {
                        if (item is WorkspaceItem.WidgetIcon) {
                            app.appWidgetHost.removeWidget(item.appWidgetId)
                        }
                        app.workspaceController.removeItem(item.id)
                        onWorkspaceChanged?.invoke()
                        true
                    }
                    MENU_ID_APP_INFO -> {
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

    companion object {
        private const val ARG_PAGE_INDEX = "page_index"
        private const val MENU_ID_REMOVE_FROM_HOME = 1
        private const val MENU_ID_APP_INFO = 2

        fun newInstance(pageIndex: Int): HomePageFragment = HomePageFragment().apply {
            arguments = Bundle().apply { putInt(ARG_PAGE_INDEX, pageIndex) }
        }
    }
}
