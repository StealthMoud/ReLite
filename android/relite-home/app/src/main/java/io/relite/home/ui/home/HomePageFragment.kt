package io.relite.home.ui.home

import android.appwidget.AppWidgetHostView
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import io.relite.home.R
import io.relite.home.ReliteHomeApplication
import io.relite.home.data.AppEntry
import io.relite.home.data.GridPosition
import io.relite.home.data.WorkspaceItem
import io.relite.home.ui.LauncherHost
import io.relite.home.ui.launcherHost
import kotlin.math.hypot

/**
 * One page of the home-screen workspace grid — renders directly into a
 * [WorkspaceGridLayout] using each item's real (column, row, span) instead
 * of a RecyclerView + GridLayoutManager that only ever placed cells in list
 * order and ignored a widget's span (section 26 of the v0.4.0 plan).
 */
class HomePageFragment : Fragment(R.layout.fragment_home_page) {

    // Section 5-10 (v0.5.0 completion pass): resolved fresh on every call
    // via requireActivity() instead of activity-set lambda fields, which
    // silently stayed null on a page fragment reused (not recreated) by
    // FragmentStateAdapter after process-death restoration — see
    // LauncherHost's kdoc.
    private val host: LauncherHost get() = launcherHost()

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

        // Section 20-21/112 (v0.5.0): long-pressing empty grid space (not
        // consumed by any item's own listener) enters Home edit mode.
        grid.setOnLongClickListener { host.requestHomeEditMode(); true }

        // Section 41 (v0.5.0): keep the process-wide last-known grid metrics
        // fresh on every measure pass (rotation, grid-preset change, first
        // layout) so WidgetPickerActivity always has a real cell size to
        // derive an initial span from, not just whichever page happened to
        // measure first.
        grid.viewTreeObserver.addOnGlobalLayoutListener {
            grid.currentMetrics()?.let { app.lastGridMetrics = it }
        }
    }

    private fun spanOf(item: WorkspaceItem): Pair<Int, Int> =
        io.relite.home.data.GridRect.of(item).let { it.spanColumns to it.spanRows }

    private fun buildCellView(
        app: ReliteHomeApplication,
        item: WorkspaceItem,
        labels: Map<String, AppEntry>,
    ): View {
        val cellView: View = when {
            item is WorkspaceItem.WidgetIcon -> buildWidgetView(app, item)
            item is WorkspaceItem.FolderIcon && item.size == io.relite.home.data.FolderSize.EXPANDED -> buildExpandedFolderView(app, item, labels)
            else -> buildIconView(app, item, labels)
        }
        wireInteractions(app, item, cellView)
        return cellView
    }

    /**
     * Section 30-31 (v0.5.0 completion pass): a real 2x2-cell view showing
     * up to 4 member icons directly — each independently launchable without
     * opening the full editor. Members each own their click listener, so a
     * tap that lands on one never reaches wireInteractions' outer click
     * listener on [cellView]; a tap on the header/empty background falls
     * through to that outer listener, which still opens the full editor
     * (host.openFolder), same as a compact folder's single-tap behavior.
     */
    private fun buildExpandedFolderView(app: ReliteHomeApplication, folder: WorkspaceItem.FolderIcon, labels: Map<String, AppEntry>): View {
        val container = LayoutInflater.from(requireContext())
            .inflate(R.layout.item_workspace_folder_expanded, grid, false) as android.widget.FrameLayout
        container.findViewById<TextView>(R.id.expanded_folder_title).text = folder.label

        val membersGrid = container.findViewById<android.widget.GridLayout>(R.id.expanded_folder_members)
        membersGrid.removeAllViews()
        folder.itemComponentKeys.take(4).forEach { componentKey ->
            val entry = labels[componentKey] ?: return@forEach
            val (pkg, activity) = componentKey.split("/", limit = 2)
            val memberIcon = ImageView(requireContext()).apply {
                layoutParams = android.widget.GridLayout.LayoutParams().apply {
                    width = 0
                    height = 0
                    columnSpec = android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, 1f)
                    rowSpec = android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, 1f)
                }
                setImageDrawable(app.iconCache.get(pkg, activity, resolvedIconSizePx()))
                contentDescription = entry.label
                setOnClickListener { host.launchComponent(componentKey) }
            }
            membersGrid.addView(memberIcon)
        }
        container.contentDescription = getString(R.string.folder_content_description, folder.label, folder.itemComponentKeys.size)
        return container
    }

    private fun buildIconView(app: ReliteHomeApplication, item: WorkspaceItem, labels: Map<String, AppEntry>): View {
        val cellView = LayoutInflater.from(requireContext()).inflate(R.layout.item_workspace_icon, grid, false)
        val labelView = cellView.findViewById<TextView>(R.id.label)
        // Section 74 (v0.5.0): Home Settings "Show app labels" toggle.
        if (io.relite.home.util.HomePreference.getShowAppLabels(requireContext())) {
            labelView.visibility = View.VISIBLE
            labelView.text = labelFor(item, labels)
        } else {
            labelView.visibility = View.GONE
        }
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

        // Section 13-14/43 (v0.5.0): notify the provider of the current
        // rendered size whenever its view is (re)built — including right
        // after a resize, since a resize always triggers a full page
        // rebuild via onWorkspaceChanged rather than mutating the live view
        // in place. Uses the grid's real measured (non-square) cell
        // rectangle rather than a width-derived square approximation — see
        // WorkspaceGridLayout.GridMetrics's kdoc. The grid may not have
        // measured yet on this very first build (the widget's cell view is
        // added during onViewCreated, before layout), so this defers to the
        // next global layout pass rather than notifying with a stale/zero
        // size.
        notifyWidgetResizeWhenMeasured(app, hostView, item)
        return hostView
    }

    private fun notifyWidgetResizeWhenMeasured(
        app: ReliteHomeApplication,
        hostView: AppWidgetHostView,
        item: WorkspaceItem.WidgetIcon,
    ) {
        val density = requireContext().resources.displayMetrics.density
        fun notifyWithCurrentMetrics(): Boolean {
            val metrics = grid.currentMetrics() ?: return false
            val widthDp = (metrics.cellWidthPx * item.spanColumns / density).toInt()
            val heightDp = (metrics.cellHeightPx * item.spanRows / density).toInt()
            app.appWidgetHost.notifyResized(hostView, widthDp, heightDp)
            return true
        }
        if (!notifyWithCurrentMetrics()) {
            grid.viewTreeObserver.addOnGlobalLayoutListener(object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    grid.viewTreeObserver.removeOnGlobalLayoutListener(this)
                    notifyWithCurrentMetrics()
                }
            })
        }
    }

    private fun resolvedIconSizePx(): Int =
        io.relite.home.util.IconSizePreference.resolvePx(requireContext(), resources.getDimensionPixelSize(R.dimen.icon_size))

    private fun iconFor(app: ReliteHomeApplication, item: WorkspaceItem) = when (item) {
        is WorkspaceItem.AppIcon -> {
            val (pkg, activity) = item.componentKey.split("/", limit = 2)
            app.iconCache.get(pkg, activity, resolvedIconSizePx())
        }
        is WorkspaceItem.FolderIcon -> {
            FolderPreview.render(requireContext(), app.iconCache, item, resolvedIconSizePx())
        }
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
                is WorkspaceItem.AppIcon -> host.launchComponent(item.componentKey)
                is WorkspaceItem.FolderIcon -> host.openFolder(item.id)
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
        val hover = FolderHoverState()

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
                    proxy = host.beginDrag(cellView)
                    cellView.alpha = 0f
                    // Section 21 (v0.5.0 completion pass): without this, a
                    // drag whose path is close enough to purely horizontal
                    // (same-row rearrange, or dragging one app onto its
                    // neighbor to fold into it) can be stolen mid-gesture by
                    // ViewPager2's own internal RecyclerView page-swipe
                    // detector once cumulative horizontal movement passes
                    // *its* touch slop — this cellView already committed to
                    // handling the whole gesture itself once armed.
                    cellView.parent?.requestDisallowInterceptTouchEvent(true)
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
                            host.moveDrag(currentProxy, dx, dy)
                            updateFolderHoverFeedback(app, item, currentProxy, dragTargetPage, hover)
                            updateDropFeedback(app, item, currentProxy, dragTargetPage, hover)
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
                        // Read the armed target BEFORE canceling — cancelFolderHover
                        // clears hover.armedTargetId as part of its own cleanup.
                        val armedFolderTargetId = hover.armedTargetId
                        cancelFolderHover(hover)
                        val moved = hypot(event.rawX - downRawX, event.rawY - downRawY) > touchSlop
                        val capturedProxy = proxy
                        proxy = null
                        // Deferred for the same reason as the drag start
                        // above: finishDrag can remove the proxy and (on a
                        // successful drop) trigger a full pager rebuild,
                        // both view-hierarchy mutations that must not run
                        // synchronously inside this touch dispatch.
                        cellView.post {
                            finishDrag(app, item, cellView, capturedProxy, dragTargetPage, moved, armedFolderTargetId)
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
            onPageChanged(host.requestAdjacentPage(direction))
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

    private fun updateDropFeedback(app: ReliteHomeApplication, item: WorkspaceItem, proxy: View, targetPage: Int, hover: FolderHoverState) {
        if (hover.armedTargetId != null) {
            proxy.alpha = 0.9f // an armed folder-create/add-to-folder target is always a valid drop
            return
        }
        val cell = targetCellFor(proxy)
        val valid = cell != null &&
            app.workspaceController.canMoveTo(item.id, GridPosition(targetPage, cell.first, cell.second))
        proxy.alpha = if (valid) 0.9f else 0.4f
    }

    /**
     * Section 20-22 (v0.5.0 completion pass): mutable per-gesture state for
     * the drag-onto-app/drag-onto-folder hover affordance — a plain local
     * var block, not a class field, so a concurrent drag on a different
     * page's cell (impossible today, but not assumed) can never cross-talk.
     */
    private class FolderHoverState {
        var targetItemId: String? = null
        var armedTargetId: String? = null
        var runnable: Runnable? = null
        var highlightedView: View? = null
    }

    /**
     * Section 21 (v0.5.0 completion pass): hovering the drag proxy over
     * another app (or an existing folder) for [FOLDER_HOVER_ARM_DELAY_MS]
     * without moving to a different target arms a folder-create/add-to-
     * folder drop — a deliberate dwell, not a passing touch, so simply
     * dragging *across* another icon on the way to an empty cell never
     * creates a folder by accident.
     */
    private fun updateFolderHoverFeedback(
        app: ReliteHomeApplication,
        item: WorkspaceItem,
        proxy: View,
        targetPage: Int,
        hover: FolderHoverState,
    ) {
        if (item !is WorkspaceItem.AppIcon) return // only a dragged app can create/join a folder
        val cell = targetCellFor(proxy)
        val occupant = cell?.let { (column, row) ->
            app.workspaceController.current().items.find {
                it.id != item.id && it.position.page == targetPage && it.position.column == column && it.position.row == row
            }
        }
        val targetId = when (occupant) {
            is WorkspaceItem.AppIcon, is WorkspaceItem.FolderIcon -> occupant.id
            else -> null
        }
        if (targetId == hover.targetItemId) return // still hovering the same (or no) target — timer already running or already armed

        cancelFolderHover(hover)
        hover.targetItemId = targetId
        if (targetId == null || cell == null) return

        val runnable = Runnable {
            hover.runnable = null
            hover.armedTargetId = targetId
            proxy.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            val targetView = grid.viewAt(cell.first, cell.second)
            hover.highlightedView = targetView
            targetView?.animate()?.scaleX(FOLDER_HOVER_TARGET_SCALE)?.scaleY(FOLDER_HOVER_TARGET_SCALE)
                ?.setDuration(io.relite.home.ui.motion.MotionTokens.DURATION_FAST_MS)?.start()
        }
        hover.runnable = runnable
        edgeHoverHandler.postDelayed(runnable, FOLDER_HOVER_ARM_DELAY_MS)
    }

    private fun cancelFolderHover(hover: FolderHoverState) {
        hover.runnable?.let { edgeHoverHandler.removeCallbacks(it) }
        hover.runnable = null
        hover.targetItemId = null
        hover.armedTargetId = null
        hover.highlightedView?.animate()?.scaleX(1f)?.scaleY(1f)?.setDuration(io.relite.home.ui.motion.MotionTokens.DURATION_FAST_MS)?.start()
        hover.highlightedView = null
    }

    private fun finishDrag(
        app: ReliteHomeApplication,
        item: WorkspaceItem,
        original: View,
        proxy: View?,
        targetPage: Int,
        moved: Boolean,
        armedFolderTargetId: String?,
    ) {
        if (proxy == null) {
            original.alpha = 1f
            return
        }
        if (!moved) {
            host.endDrag(proxy)
            original.alpha = 1f
            showLongPressMenu(app, item, original)
            return
        }

        if (armedFolderTargetId != null && item is WorkspaceItem.AppIcon) {
            val targetItem = app.workspaceController.current().items.find { it.id == armedFolderTargetId }
            val ok = when (targetItem) {
                is WorkspaceItem.AppIcon -> app.workspaceController.createFolderFromApps(item.id, targetItem.id) != null
                is WorkspaceItem.FolderIcon -> app.workspaceController.moveHomeAppIntoFolder(item.id, targetItem.id)
                else -> false
            }
            host.endDrag(proxy)
            if (ok) {
                host.workspaceChanged(targetPage)
            } else {
                original.alpha = 1f
            }
            return
        }

        val cell = targetCellFor(proxy)
        val target = cell?.let { GridPosition(targetPage, it.first, it.second) }
        val ok = target != null && app.workspaceController.moveItem(item.id, target)
        host.endDrag(proxy)
        if (ok) {
            host.workspaceChanged(targetPage)
        } else {
            original.alpha = 1f
            if (targetPage != pageIndex) {
                // Dropped somewhere invalid after hovering to another page —
                // land back on the page the drag actually started from
                // rather than stranding the user on a page with nothing to
                // show for the failed drag.
                host.workspaceChanged(pageIndex)
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
        val actions = buildList {
            add(io.relite.home.ui.menu.LauncherAction(MENU_ID_REMOVE_FROM_HOME, getString(R.string.action_remove_from_home)))
            add(io.relite.home.ui.menu.LauncherAction(MENU_ID_MOVE_TO_PAGE, getString(R.string.action_move_to_page)))
            if (item is WorkspaceItem.AppIcon) {
                add(io.relite.home.ui.menu.LauncherAction(MENU_ID_PIN_TO_DOCK, getString(R.string.action_pin_to_dock)))
                add(io.relite.home.ui.menu.LauncherAction(MENU_ID_ADD_TO_FOLDER, getString(R.string.action_add_to_folder)))
                add(io.relite.home.ui.menu.LauncherAction(MENU_ID_APP_INFO, getString(R.string.action_app_info)))
            }
            if (item is WorkspaceItem.WidgetIcon) {
                add(io.relite.home.ui.menu.LauncherAction(MENU_ID_RESIZE_WIDGET, getString(R.string.action_resize_widget)))
            }
            if (item is WorkspaceItem.FolderIcon) {
                if (item.size == io.relite.home.data.FolderSize.COMPACT) {
                    add(io.relite.home.ui.menu.LauncherAction(MENU_ID_ENLARGE_FOLDER, getString(R.string.action_enlarge_folder)))
                } else {
                    add(io.relite.home.ui.menu.LauncherAction(MENU_ID_SHRINK_FOLDER, getString(R.string.action_shrink_folder)))
                }
            }
        }
        io.relite.home.ui.menu.LauncherContextMenu.show(anchor, actions) { actionId ->
            when (actionId) {
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
                    host.workspaceChanged()
                }
                MENU_ID_PIN_TO_DOCK -> {
                    if (item is WorkspaceItem.AppIcon) {
                        val pinned = app.workspaceController.addToDock(item.componentKey)
                        if (!pinned) {
                            Toast.makeText(requireContext(), R.string.dock_full, Toast.LENGTH_SHORT).show()
                        } else {
                            host.workspaceChanged()
                        }
                    }
                }
                MENU_ID_MOVE_TO_PAGE -> showMoveToPageDialog(app, item)
                MENU_ID_RESIZE_WIDGET -> if (item is WorkspaceItem.WidgetIcon) showResizeDialog(app, item)
                MENU_ID_ADD_TO_FOLDER -> {
                    if (item is WorkspaceItem.AppIcon) {
                        io.relite.home.ui.folder.FolderPicker.show(
                            requireContext(),
                            app.workspaceController,
                            item.componentKey,
                            existingHomeItemId = item.id,
                        ) { host.workspaceChanged() }
                    }
                }
                MENU_ID_APP_INFO -> {
                    if (item is WorkspaceItem.AppIcon) {
                        openAppInfo(item.componentKey.substringBefore("/"))
                    }
                }
                MENU_ID_ENLARGE_FOLDER -> {
                    if (item is WorkspaceItem.FolderIcon) {
                        if (app.workspaceController.enlargeFolder(item.id)) {
                            host.workspaceChanged()
                        } else {
                            Toast.makeText(requireContext(), R.string.folder_enlarge_no_room, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                MENU_ID_SHRINK_FOLDER -> {
                    if (item is WorkspaceItem.FolderIcon) {
                        app.workspaceController.shrinkFolder(item.id)
                        host.workspaceChanged()
                    }
                }
            }
        }
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
                host.workspaceChanged()
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
                    host.workspaceChanged()
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
        private const val MENU_ID_ENLARGE_FOLDER = 7
        private const val MENU_ID_SHRINK_FOLDER = 8

        // Section 2: long enough that grazing the edge mid-drag doesn't
        // accidentally flip a page, short enough to feel responsive when
        // the user actually means to hover there.
        private const val EDGE_HOVER_DELAY_MS = 650L

        // Section 21: shorter than the page-edge hover delay — hovering
        // over an icon to fold into it is the whole point of the gesture
        // once the user has settled there, not an incidental brush-past
        // like the edge-of-screen case.
        private const val FOLDER_HOVER_ARM_DELAY_MS = 400L
        private const val FOLDER_HOVER_TARGET_SCALE = 1.15f

        fun newInstance(pageIndex: Int): HomePageFragment = HomePageFragment().apply {
            arguments = Bundle().apply { putInt(ARG_PAGE_INDEX, pageIndex) }
        }
    }
}
