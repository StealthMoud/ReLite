package io.relite.home.ui.home

import android.appwidget.AppWidgetHostView
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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

    // Section 4-6 (v0.4.0 cross-page drag): the dragged icon is rendered as
    // a proxy in a full-screen overlay owned by MainActivity, not as this
    // page's own child view, because ViewPager2 may swap this fragment's
    // page out from under the drag mid-gesture. onDragEdgeHover switches
    // the pager's current page and returns whichever page ends up current;
    // onDragCommitted replaces the plain onWorkspaceChanged call for a
    // successful drop so MainActivity can restore the pager to the page the
    // item was actually dropped on (a full adapter rebuild would otherwise
    // reset the scroll position to page 0).
    var onDragStart: ((source: View) -> View)? = null
    var onDragMove: ((proxy: View, dx: Float, dy: Float) -> Unit)? = null
    var onDragEnd: ((proxy: View) -> Unit)? = null
    var onDragEdgeHover: ((direction: Int) -> Int)? = null
    var onDragCommitted: ((page: Int) -> Unit)? = null

    private lateinit var grid: WorkspaceGridLayout
    private var pageIndex: Int = 0
    private val touchSlop by lazy { ViewConfiguration.get(requireContext()).scaledTouchSlop }
    private val edgeHoverHandler = Handler(Looper.getMainLooper())
    private var edgeHoverRunnable: Runnable? = null

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
        // Section 24: a folder's icon has no visible label text of its own
        // elsewhere to read, so TalkBack needs an explicit description with
        // the member count; a plain app icon's own label is enough on its own.
        cellView.contentDescription = when (item) {
            is WorkspaceItem.FolderIcon -> getString(R.string.folder_content_description, item.label, item.itemComponentKeys.size)
            else -> null
        }
        return cellView
    }

    /**
     * A live [AppWidgetHostView] when the binding is still valid, or a
     * removable placeholder when it isn't (section 10, v0.4.1) — a stale
     * binding (provider uninstalled, or an id the host never actually
     * bound) must not crash, and must still be removable via the normal
     * long-press "Remove from Home" menu like any other item.
     */
    private fun buildWidgetView(app: ReliteHomeApplication, item: WorkspaceItem.WidgetIcon): View {
        val hostView = app.appWidgetHost.bindWidgetView(item.appWidgetId)
        if (hostView == null) {
            val placeholder = LayoutInflater.from(requireContext()).inflate(R.layout.item_workspace_icon, grid, false)
            placeholder.findViewById<TextView>(R.id.label).text = getString(R.string.stale_widget_label)
            placeholder.findViewById<ImageView>(R.id.icon).setImageResource(android.R.drawable.ic_dialog_alert)
            placeholder.contentDescription = getString(R.string.stale_widget_label)
            return placeholder
        }
        // The provider's own view usually announces its content, but the
        // hosting cell itself still benefits from a label — falls back to
        // the provider's component name when no friendlier label is known.
        hostView.contentDescription = item.providerComponent.substringAfterLast(".").ifBlank { item.providerComponent }

        // Section 13-14: notify the provider of the current rendered size
        // whenever its view is (re)built — including right after a resize,
        // since a resize always triggers a full page rebuild via
        // onWorkspaceChanged rather than mutating the live view in place.
        val gridSpec = app.workspaceController.gridSpec
        val density = requireContext().resources.displayMetrics.density
        val cellSizeDp = (requireContext().resources.displayMetrics.widthPixels / gridSpec.columns) / density
        app.appWidgetHost.notifyResized(
            hostView,
            (item.spanColumns * cellSizeDp).toInt(),
            (item.spanRows * cellSizeDp).toInt(),
        )
        return hostView
    }

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
        var proxy: View? = null
        var dragTargetPage = pageIndex

        cellView.setOnLongClickListener {
            dragArmed = true
            dragTargetPage = pageIndex
            // Deferred via post{}: adding the proxy to DragOverlay mutates
            // a sibling ViewGroup's child list, and doing that synchronously
            // from inside a long-press callback — itself firing mid
            // touch-dispatch — corrupted the ConstraintLayout root's touch
            // dispatch state and crashed with "IllegalStateException:
            // already recycled once" in ViewGroup$TouchTarget.recycle,
            // found testing this drag live on the RMX5303. post{} runs
            // after the current dispatch cycle finishes, which is safe.
            cellView.post {
                if (dragArmed) {
                    proxy = onDragStart?.invoke(cellView)
                    cellView.alpha = 0f
                }
            }
            true
        }
        cellView.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragArmed = false
                    downRawX = event.rawX
                    downRawY = event.rawY
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (dragArmed) {
                        val dx = event.rawX - downRawX
                        val dy = event.rawY - downRawY
                        val currentProxy = proxy
                        if (currentProxy != null) {
                            onDragMove?.invoke(currentProxy, dx, dy)
                            updateDropFeedback(app, item, currentProxy, dragTargetPage)
                            updateEdgeHover(event.rawX) { newPage -> dragTargetPage = newPage }
                        }
                        true
                    } else {
                        false
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragArmed) {
                        dragArmed = false
                        cancelEdgeHoverTimer()
                        val moved = hypot(event.rawX - downRawX, event.rawY - downRawY) > touchSlop
                        val capturedProxy = proxy
                        proxy = null
                        // Deferred for the same reason as the drag start
                        // above: finishDrag can remove the proxy and (on a
                        // successful drop) trigger a full pager rebuild,
                        // both view-hierarchy mutations that must not run
                        // synchronously inside this touch dispatch.
                        cellView.post {
                            finishDrag(app, item, cellView, capturedProxy, dragTargetPage, moved)
                        }
                        true
                    } else {
                        false
                    }
                }
                else -> false
            }
        }
    }

    /** Section 2-3: arms/cancels a one-shot debounce timer while hovering an edge zone — never a continuous poll (section 97). */
    private fun updateEdgeHover(rawX: Float, onPageChanged: (Int) -> Unit) {
        val screenWidth = resources.displayMetrics.widthPixels
        val edgeZonePx = resources.getDimension(R.dimen.edge_hover_zone_width)
        val direction = EdgeHover.directionFor(rawX, screenWidth, edgeZonePx)
        if (direction == 0) {
            cancelEdgeHoverTimer()
            return
        }
        if (edgeHoverRunnable != null) return // already armed for this hover
        val runnable = Runnable {
            edgeHoverRunnable = null
            val newPage = onDragEdgeHover?.invoke(direction)
            if (newPage != null) onPageChanged(newPage)
        }
        edgeHoverRunnable = runnable
        edgeHoverHandler.postDelayed(runnable, EDGE_HOVER_DELAY_MS)
    }

    private fun cancelEdgeHoverTimer() {
        edgeHoverRunnable?.let { edgeHoverHandler.removeCallbacks(it) }
        edgeHoverRunnable = null
    }

    override fun onPause() {
        super.onPause()
        cancelEdgeHoverTimer()
    }

    private fun targetCellFor(proxy: View): Pair<Int, Int>? {
        val gridLocation = IntArray(2)
        grid.getLocationOnScreen(gridLocation)
        val viewLocation = IntArray(2)
        proxy.getLocationOnScreen(viewLocation)
        // Use the dragged proxy's current center, not the raw touch point,
        // so a drop is judged by where the icon visually is, not the finger.
        val centerX = viewLocation[0] + proxy.width / 2f
        val centerY = viewLocation[1] + proxy.height / 2f
        return grid.cellAt(centerX - gridLocation[0], centerY - gridLocation[1])
    }

    private fun updateDropFeedback(app: ReliteHomeApplication, item: WorkspaceItem, proxy: View, targetPage: Int) {
        val cell = targetCellFor(proxy)
        val valid = cell != null &&
            app.workspaceController.canMoveTo(item.id, GridPosition(targetPage, cell.first, cell.second))
        proxy.alpha = if (valid) 0.9f else 0.4f
    }

    private fun finishDrag(
        app: ReliteHomeApplication,
        item: WorkspaceItem,
        original: View,
        proxy: View?,
        targetPage: Int,
        moved: Boolean,
    ) {
        if (proxy == null) {
            original.alpha = 1f
            return
        }
        if (!moved) {
            onDragEnd?.invoke(proxy)
            original.alpha = 1f
            showLongPressMenu(app, item, original)
            return
        }

        val cell = targetCellFor(proxy)
        val target = cell?.let { GridPosition(targetPage, it.first, it.second) }
        val ok = target != null && app.workspaceController.moveItem(item.id, target)
        onDragEnd?.invoke(proxy)
        if (ok) {
            onDragCommitted?.invoke(targetPage)
        } else {
            original.alpha = 1f
            if (targetPage != pageIndex) {
                // Dropped somewhere invalid after hovering to another page —
                // land back on the page the drag actually started from
                // rather than stranding the user on a page with nothing to
                // show for the failed drag.
                onDragCommitted?.invoke(pageIndex)
            }
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
                            io.relite.home.ui.widget.WidgetLifecycle.removeWidgetSafely(
                                app.workspaceController,
                                app.appWidgetHost,
                                item,
                            )
                        } else {
                            app.workspaceController.removeItem(item.id)
                        }
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
                val moved = if (which < pageCount) {
                    app.workspaceController.moveToPage(item.id, which)
                } else {
                    app.workspaceController.moveToNewPage(item.id)
                }
                if (!moved) {
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

        // Section 2: long enough that grazing the edge mid-drag doesn't
        // accidentally flip a page, short enough to feel responsive when
        // the user actually means to hover there.
        private const val EDGE_HOVER_DELAY_MS = 650L

        fun newInstance(pageIndex: Int): HomePageFragment = HomePageFragment().apply {
            arguments = Bundle().apply { putInt(ARG_PAGE_INDEX, pageIndex) }
        }
    }
}
