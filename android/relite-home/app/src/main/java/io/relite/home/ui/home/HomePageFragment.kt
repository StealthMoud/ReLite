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
import android.widget.Toast
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
    var onAddWidgetRequested: (() -> Unit)? = null

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

        // Section 20-21: long-pressing empty grid space (not consumed by any
        // item's own listener) offers page management — the required minimum
        // UI for the addPage/removeEmptyPage controller methods.
        grid.setOnLongClickListener { showPageManagementMenu(app, pageItems.isEmpty()); true }
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
        // Widgets manage their own touch input (scrolling lists, buttons, etc.), so
        // they don't get the drag-via-ACTION_MOVE gesture below — but they still need
        // a long-press entry point into the same Remove/Resize/Move-to-page menu, or
        // those actions would be permanently unreachable for every placed widget.
        if (item is WorkspaceItem.WidgetIcon) {
            cellView.setOnLongClickListener { showLongPressMenu(app, item, cellView); true }
            return
        }

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
            menu.add(Menu.NONE, MENU_ID_MOVE_TO_PAGE, Menu.NONE, R.string.action_move_to_page)
            if (item is WorkspaceItem.AppIcon) {
                menu.add(Menu.NONE, MENU_ID_PIN_TO_DOCK, Menu.NONE, R.string.action_pin_to_dock)
                menu.add(Menu.NONE, MENU_ID_ADD_TO_FOLDER, Menu.NONE, R.string.action_add_to_folder)
                menu.add(Menu.NONE, MENU_ID_APP_INFO, Menu.NONE, R.string.action_app_info)
            }
            if (item is WorkspaceItem.WidgetIcon) {
                menu.add(Menu.NONE, MENU_ID_RESIZE_WIDGET, Menu.NONE, R.string.action_resize_widget)
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
                    MENU_ID_PIN_TO_DOCK -> {
                        if (item is WorkspaceItem.AppIcon) {
                            val pinned = app.workspaceController.addToDock(item.componentKey)
                            if (!pinned) {
                                Toast.makeText(requireContext(), R.string.dock_full, Toast.LENGTH_SHORT).show()
                            } else {
                                onWorkspaceChanged?.invoke()
                            }
                        }
                        true
                    }
                    MENU_ID_MOVE_TO_PAGE -> {
                        showMoveToPageDialog(app, item)
                        true
                    }
                    MENU_ID_RESIZE_WIDGET -> {
                        if (item is WorkspaceItem.WidgetIcon) showResizeDialog(app, item)
                        true
                    }
                    MENU_ID_ADD_TO_FOLDER -> {
                        if (item is WorkspaceItem.AppIcon) {
                            io.relite.home.ui.folder.FolderPicker.show(
                                requireContext(),
                                app.workspaceController,
                                item.componentKey,
                                existingHomeItemId = item.id,
                            ) { onWorkspaceChanged?.invoke() }
                        }
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

    /**
     * Section 53-55: simple, reliable +/- controls rather than animated drag
     * handles — each tap validates the candidate rectangle (bounds + no
     * collision) via WorkspaceController.resizeWidget before committing, so
     * an invalid tap is simply rejected rather than silently clamped.
     */
    private fun showResizeDialog(app: ReliteHomeApplication, widget: WorkspaceItem.WidgetIcon) {
        val view = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_widget_resize, null)
        val sizeLabel = view.findViewById<TextView>(R.id.resize_current_size)
        var spanColumns = widget.spanColumns
        var spanRows = widget.spanRows

        fun refreshLabel() {
            sizeLabel.text = getString(R.string.resize_current_size, spanColumns, spanRows)
        }
        fun applyResize(newColumns: Int, newRows: Int) {
            if (app.workspaceController.resizeWidget(widget.id, newColumns, newRows)) {
                spanColumns = newColumns
                spanRows = newRows
                refreshLabel()
                onWorkspaceChanged?.invoke()
            } else {
                Toast.makeText(requireContext(), R.string.resize_no_room, Toast.LENGTH_SHORT).show()
            }
        }
        refreshLabel()

        view.findViewById<View>(R.id.resize_width_plus).setOnClickListener {
            applyResize((spanColumns + 1).coerceAtMost(app.workspaceController.gridSpec.columns), spanRows)
        }
        view.findViewById<View>(R.id.resize_width_minus).setOnClickListener {
            applyResize((spanColumns - 1).coerceAtLeast(1), spanRows)
        }
        view.findViewById<View>(R.id.resize_height_plus).setOnClickListener {
            applyResize(spanColumns, (spanRows + 1).coerceAtMost(app.workspaceController.gridSpec.rows))
        }
        view.findViewById<View>(R.id.resize_height_minus).setOnClickListener {
            applyResize(spanColumns, (spanRows - 1).coerceAtLeast(1))
        }

        android.app.AlertDialog.Builder(requireContext())
            .setTitle(R.string.action_resize_widget)
            .setView(view)
            .setPositiveButton(R.string.done, null)
            .show()
    }

    /** Section 19: accessibility/precision fallback to hover-based cross-page drag — always available. */
    private fun showMoveToPageDialog(app: ReliteHomeApplication, item: WorkspaceItem) {
        val pageCount = app.workspaceController.current().pageCount
        val labels = (0 until pageCount).map { getString(R.string.page_label, it + 1) } +
            getString(R.string.new_page_option)

        android.app.AlertDialog.Builder(requireContext())
            .setTitle(R.string.action_move_to_page)
            .setItems(labels.toTypedArray()) { _, which ->
                val targetPage = if (which < pageCount) which else app.workspaceController.addPage()
                if (targetPage < 0 || !app.workspaceController.moveToPage(item.id, targetPage)) {
                    android.widget.Toast.makeText(requireContext(), R.string.move_no_room, android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    onWorkspaceChanged?.invoke()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Sections 20-21: minimal page management, reached from a long press on empty grid space. */
    private fun showPageManagementMenu(app: ReliteHomeApplication, pageIsEmpty: Boolean) {
        val canRemove = pageIsEmpty && pageIndex > 0
        val options = buildList {
            add(getString(R.string.action_widgets))
            add(getString(R.string.action_add_page))
            if (canRemove) add(getString(R.string.action_remove_page))
            add(getString(R.string.action_home_settings))
        }
        android.app.AlertDialog.Builder(requireContext())
            .setItems(options.toTypedArray()) { _, which ->
                when (options[which]) {
                    getString(R.string.action_widgets) -> onAddWidgetRequested?.invoke()
                    getString(R.string.action_add_page) -> {
                        app.workspaceController.addPage()
                        onWorkspaceChanged?.invoke()
                    }
                    getString(R.string.action_remove_page) -> {
                        app.workspaceController.removeEmptyPage(pageIndex)
                        onWorkspaceChanged?.invoke()
                    }
                    getString(R.string.action_home_settings) -> startActivity(
                        Intent(requireContext(), io.relite.home.ui.settings.HomeSettingsActivity::class.java),
                    )
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    companion object {
        private const val ARG_PAGE_INDEX = "page_index"
        private const val MENU_ID_REMOVE_FROM_HOME = 1
        private const val MENU_ID_APP_INFO = 2
        private const val MENU_ID_PIN_TO_DOCK = 3
        private const val MENU_ID_ADD_TO_FOLDER = 4
        private const val MENU_ID_MOVE_TO_PAGE = 5
        private const val MENU_ID_RESIZE_WIDGET = 6

        fun newInstance(pageIndex: Int): HomePageFragment = HomePageFragment().apply {
            arguments = Bundle().apply { putInt(ARG_PAGE_INDEX, pageIndex) }
        }
    }
}
