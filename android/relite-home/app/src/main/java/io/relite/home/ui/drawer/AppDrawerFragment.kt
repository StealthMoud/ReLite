package io.relite.home.ui.drawer

import io.relite.home.ui.LauncherHost
import io.relite.home.ui.launcherHost

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import io.relite.home.R
import io.relite.home.ReliteHomeApplication
import io.relite.home.data.AppEntry
import io.relite.home.data.AppRepository
import io.relite.home.data.DrawerFolder
import io.relite.home.ui.settings.HomeSettingsActivity
import io.relite.home.util.AppSearch
import io.relite.home.util.AppsPreference
import io.relite.home.util.AppsSortMode

/**
 * Alphabetical, searchable app drawer. No network, no ads, no "recommended
 * apps" section — the drawer only ever shows what LauncherApps reports.
 */
class AppDrawerFragment : Fragment(R.layout.fragment_app_drawer) {

    private lateinit var adapter: DrawerGridAdapter
    private lateinit var recycler: RecyclerView
    private lateinit var pageIndicator: io.relite.home.ui.home.PageIndicatorView
    private lateinit var dragHelper: ItemTouchHelper
    private var allApps: List<AppEntry> = emptyList()
    private var folders: List<DrawerFolder> = emptyList()
    private var appsChangedSubscription: AppRepository.Subscription? = null
    private var sortMode: AppsSortMode = AppsSortMode.CUSTOM

    // Section 5-10 (v0.5.0 completion pass): the drawer is added via a plain
    // FragmentTransaction (not a FragmentStateAdapter), so it has the same
    // restoration hazard as home pages did — a process-death-restored
    // instance never runs through the caller's configure block again.
    // Resolving the host via requireActivity() works regardless.
    private val host: LauncherHost get() = launcherHost()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val app = requireActivity().application as ReliteHomeApplication
        sortMode = AppsPreference.getSortMode(requireContext())

        adapter = DrawerGridAdapter(
            iconCache = app.iconCache,
            iconSizePx = io.relite.home.util.IconSizePreference.resolvePx(requireContext(), resources.getDimensionPixelSize(R.dimen.icon_size)),
            onAppClick = { host.launchComponent(it.componentKey) },
            // Section 92 (v0.5.0): in Custom order, long-press starts a
            // drag instead of opening the action menu — Alphabetical order
            // (and search results, which are always alphabetically ranked
            // regardless of sort mode per section 94) is where the action
            // menu remains reachable.
            onAppLongClick = { entry, anchor ->
                if (sortMode == AppsSortMode.CUSTOM && currentQuery.isEmpty()) {
                    false
                } else {
                    showLongPressMenu(app, entry, anchor)
                    true
                }
            },
            onFolderClick = { folder ->
                DrawerFolderSheetDialog.show(requireActivity().supportFragmentManager, folder.id) { refreshAppsAndFolders(app) }
            },
            onFolderLongClick = { folder, anchor -> showFolderMenu(app, folder, anchor); true },
        )

        recycler = view.findViewById(R.id.drawer_recycler)
        recycler.adapter = adapter
        recycler.setHasFixedSize(true)
        recycler.clipToPadding = false

        pageIndicator = view.findViewById(R.id.drawer_page_indicator)
        // This one draws over the drawer's own opaque background, not the
        // wallpaper — see PageIndicatorView.useOnSurfaceColors.
        pageIndicator.useOnSurfaceColors()
        recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                updatePageIndicator()
            }
        })

        dragHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT,
            0,
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder,
            ): Boolean {
                adapter.moveItem(viewHolder.bindingAdapterPosition, target.bindingAdapterPosition)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                // Section 92/6: persist the settled order once the drag ends,
                // not on every intermediate onMove — a real device can fire
                // many onMove calls per gesture. A folder tile persists as
                // its "folder:<id>" slot marker, not its member keys.
                AppsPreference.setCustomOrder(requireContext(), adapter.currentList.map(::slotOf))
            }
        })

        applySortModeToLayout()

        // Section 21/88 (v0.5.0): when the IME opens over the drawer, keep
        // the last rows of results reachable rather than permanently hidden
        // behind the keyboard. Since the search bar itself now sits at the
        // bottom of the screen (section 88), it also needs the real
        // gesture-nav/system-bar inset added to its own margin — a fixed dp
        // margin alone can land its tappable area inside the system's
        // bottom edge-swipe gesture zone, which swallows the touch instead
        // of delivering it to the field (found live on the RMX5303: Espresso
        // could no longer focus the field to type into it once the search
        // bar moved here, same class of bug as the dock's margin in
        // MainActivity.applyWindowInsets).
        val recyclerBasePaddingBottom = recycler.paddingBottom
        val searchBar = view.findViewById<View>(R.id.drawer_search_bar)
        val searchBarBaseMarginBottom =
            (searchBar.layoutParams as android.view.ViewGroup.MarginLayoutParams).bottomMargin
        ViewCompat.setOnApplyWindowInsetsListener(view) { _, insets ->
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            val navBar = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
            recycler.setPadding(
                recycler.paddingLeft,
                recycler.paddingTop,
                recycler.paddingRight,
                recyclerBasePaddingBottom + maxOf(ime, navBar),
            )
            (searchBar.layoutParams as android.view.ViewGroup.MarginLayoutParams).bottomMargin =
                searchBarBaseMarginBottom + navBar
            searchBar.requestLayout()
            insets
        }

        allApps = app.appRepository.loadAll()
        folders = AppsPreference.getFolders(requireContext())
        adapter.submitList(applyCurrentQuery()) { updatePageIndicator() }

        appsChangedSubscription = app.appRepository.onAppsChanged {
            refreshAppsAndFolders(app)
        }

        val searchField = view.findViewById<EditText>(R.id.drawer_search)
        searchField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                currentQuery = s?.toString().orEmpty()
                applySortModeToLayout() // search always renders vertically, sort mode or not
                adapter.submitList(applyCurrentQuery()) { updatePageIndicator() }
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        view.findViewById<View>(R.id.drawer_more).setOnClickListener { showMoreMenu() }
    }

    private var currentQuery: String = ""

    /** Re-reads both apps and folders and re-binds — the folder editor and picker mutate [AppsPreference] directly. */
    private fun refreshAppsAndFolders(app: ReliteHomeApplication) {
        allApps = app.appRepository.loadAll()
        folders = AppsPreference.getFolders(requireContext())
        adapter.submitList(applyCurrentQuery()) { updatePageIndicator() }
    }

    private fun slotOf(item: io.relite.home.data.DrawerItem): String = when (item) {
        is io.relite.home.data.DrawerItem.AppItem -> item.entry.componentKey
        is io.relite.home.data.DrawerItem.FolderItem -> AppsPreference.FOLDER_SLOT_PREFIX + item.folder.id
    }

    override fun onDestroyView() {
        super.onDestroyView()
        appsChangedSubscription?.dispose()
        appsChangedSubscription = null
    }

    /**
     * Section 85-88 (v0.5.0): Custom order pages horizontally with drag
     * reorder enabled; Alphabetical order (and any active search, which is
     * always alphabetically ranked) scrolls vertically with no reordering —
     * see docs/design/one-ui-current-reference.md for the sourced axis
     * coupling this mirrors.
     */
    private fun applySortModeToLayout() {
        val app = requireActivity().application as ReliteHomeApplication
        val showingCustomGrid = sortMode == AppsSortMode.CUSTOM && currentQuery.isEmpty()
        val orientation = if (showingCustomGrid) RecyclerView.HORIZONTAL else RecyclerView.VERTICAL
        val columns = app.workspaceController.gridSpec.columns
        val rows = app.workspaceController.gridSpec.rows

        // GridLayoutManager's span count is measured across the axis
        // *perpendicular* to scrolling. Vertically that is the column count;
        // horizontally — which is what Custom order uses — it is the ROW
        // count. Passing `columns` in both cases (as this did) gave the
        // paged Apps screen only 4 rows instead of 6, so a page held 16 apps
        // in four sparse bands with large gaps rather than a dense 4x6 of
        // 24. Visible immediately on the RMX5303 next to any real One UI
        // Apps screen.
        val spanCount = if (showingCustomGrid) rows else columns
        recycler.layoutManager = GridLayoutManager(requireContext(), spanCount, orientation, false)
        dragHelper.attachToRecyclerView(if (showingCustomGrid) recycler else null)

        // Custom order pages horizontally, where the tile width is *not*
        // derived from the span count and has to be set explicitly, or every
        // page renders as one full-width column — see
        // DrawerGridAdapter.itemWidthPx.
        //
        // Computed from the display width rather than the RecyclerView's own
        // measured width, deliberately: this runs before the first layout
        // (and again on every sort-mode change), so waiting for a real
        // measurement meant setting the width from inside a layout pass,
        // where the adapter's notifyDataSetChanged is ignored and the fix
        // silently did nothing — observed on the RMX5303. The recycler is
        // match_parent with a known horizontal padding, so the display width
        // gives the same answer without the timing hazard.
        adapter.itemWidthPx = if (showingCustomGrid) {
            val horizontalPadding = resources.getDimensionPixelSize(R.dimen.grid_cell_padding) * 2
            ((resources.displayMetrics.widthPixels - horizontalPadding) / columns).coerceAtLeast(1)
        } else {
            0
        }
        updatePageIndicator()
    }

    /**
     * Keeps the Apps screen's page indicator in step with the paged grid.
     *
     * Only Custom order pages horizontally; Alphabetical order (and any
     * active search) is a vertical scroll with no pages to indicate, so the
     * indicator hides entirely rather than showing a meaningless single dot.
     */
    private fun updatePageIndicator() {
        if (!::pageIndicator.isInitialized) return
        val app = requireActivity().application as ReliteHomeApplication
        val spec = app.workspaceController.gridSpec
        val perPage = spec.columns * spec.rows
        val horizontal = (recycler.layoutManager as? GridLayoutManager)?.orientation == RecyclerView.HORIZONTAL

        if (!horizontal || perPage <= 0 || adapter.itemCount == 0) {
            pageIndicator.visibility = View.GONE
            return
        }
        val pages = ((adapter.itemCount + perPage - 1) / perPage).coerceAtLeast(1)
        if (pages <= 1) {
            pageIndicator.visibility = View.GONE
            return
        }
        pageIndicator.visibility = View.VISIBLE
        pageIndicator.pageCount = pages
        // Derived from scroll offset rather than the first-visible item, so
        // the indicator tracks the drag continuously instead of jumping only
        // once a whole new column has scrolled in.
        val extent = recycler.computeHorizontalScrollExtent().coerceAtLeast(1)
        pageIndicator.currentPage =
            ((recycler.computeHorizontalScrollOffset() + extent / 2) / extent).coerceIn(0, pages - 1)
    }

    /**
     * Section 2 (v0.5.0 completion pass): a swipe-down-to-close gesture must
     * not fight with scrolling the Alphabetical/search list — only Custom
     * order's horizontal paging has no vertical scroll to conflict with, so
     * the vertical list only allows the close gesture once it's already
     * scrolled to its top (the header is then the only thing left to pull).
     */
    fun canSwipeDownToClose(): Boolean {
        val lm = recycler.layoutManager as? GridLayoutManager ?: return true
        return lm.orientation == RecyclerView.HORIZONTAL || !recycler.canScrollVertically(-1)
    }

    /**
     * Section 6 (v0.5.0 completion pass): folders only ever appear in the
     * Custom-order grid with no active search — Alphabetical order and
     * search results are always a flat list of individual apps (a folder's
     * members included), matching how search/alphabetical already ignore
     * Custom order's own sequence.
     */
    private fun applyCurrentQuery(): List<io.relite.home.data.DrawerItem> {
        if (currentQuery.isNotEmpty()) {
            return AppSearch.search(allApps, currentQuery).map { io.relite.home.data.DrawerItem.AppItem(it) }
        }
        return when (sortMode) {
            AppsSortMode.ALPHABETICAL ->
                AppSearch.alphabetical(allApps).map { io.relite.home.data.DrawerItem.AppItem(it) }
            AppsSortMode.CUSTOM ->
                AppSearch.customOrderItems(allApps, AppsPreference.getCustomOrder(requireContext()), folders)
        }
    }

    /** Section 89-90 (v0.5.0): minimum viable "More" menu — Sort and Home screen settings. */
    /**
     * The Apps screen's "More options" menu.
     *
     * Uses the shared rounded-card [io.relite.home.ui.menu.LauncherContextMenu]
     * rather than a system `AlertDialog.setItems`. The v0.5.0 pass replaced
     * the system menu at every *long-press* site but missed this one and
     * [showSortChooser], because both open on a plain click — so the Apps
     * screen still popped a square-cornered system dialog in the middle of
     * an otherwise fully rounded UI. Caught by looking at the running app on
     * the RMX5303, not by any test.
     */
    private fun showMoreMenu() {
        val anchor = requireView().findViewById<View>(R.id.drawer_more)
        val actions = listOf(
            io.relite.home.ui.menu.LauncherAction(MENU_ID_SORT, getString(R.string.action_sort)),
            io.relite.home.ui.menu.LauncherAction(MENU_ID_HOME_SETTINGS, getString(R.string.action_home_settings)),
        )
        io.relite.home.ui.menu.LauncherContextMenu.show(anchor, actions) { actionId ->
            when (actionId) {
                MENU_ID_SORT -> showSortChooser()
                MENU_ID_HOME_SETTINGS -> startActivity(Intent(requireContext(), HomeSettingsActivity::class.java))
            }
        }
    }

    private fun showSortChooser() {
        val anchor = requireView().findViewById<View>(R.id.drawer_more)
        val modes = listOf(
            AppsSortMode.CUSTOM to getString(R.string.sort_custom_order),
            AppsSortMode.ALPHABETICAL to getString(R.string.sort_alphabetical_order),
        )
        val actions = modes.mapIndexed { index, (_, label) ->
            io.relite.home.ui.menu.LauncherAction(index, label)
        }
        io.relite.home.ui.menu.LauncherContextMenu.show(anchor, actions) { actionId ->
            sortMode = modes[actionId].first
            AppsPreference.setSortMode(requireContext(), sortMode)
            applySortModeToLayout()
            adapter.submitList(applyCurrentQuery()) { updatePageIndicator() }
        }
    }

    /**
     * Minimum viable "Add to Home" affordance (section 15, v0.2.0):
     * placed in the first free grid cell via WorkspaceController rather
     * than a drag gesture, which is out of scope for this pass. App info
     * uses the standard system Settings intent — no custom permissions
     * manager (section 25).
     */
    private fun showLongPressMenu(app: ReliteHomeApplication, entry: AppEntry, anchor: View) {
        val actions = listOf(
            io.relite.home.ui.menu.LauncherAction(MENU_ID_ADD_TO_HOME, getString(R.string.action_add_to_home)),
            io.relite.home.ui.menu.LauncherAction(MENU_ID_PIN_TO_DOCK, getString(R.string.action_pin_to_dock)),
            io.relite.home.ui.menu.LauncherAction(MENU_ID_ADD_TO_FOLDER, getString(R.string.action_add_to_folder)),
            // Section 6 (v0.5.0 completion pass): reachable from Alphabetical
            // order/search rather than Custom order, since Custom order's
            // long-press already starts a reorder drag (see onAppLongClick
            // above) — a real, non-drag alternative to drag-app-onto-app
            // merge, consistent with every other drag affordance in this
            // launcher. The resulting folder shows up the next time Custom
            // order is viewed.
            io.relite.home.ui.menu.LauncherAction(MENU_ID_ADD_TO_APPS_FOLDER, getString(R.string.action_add_to_apps_folder)),
            io.relite.home.ui.menu.LauncherAction(MENU_ID_APP_INFO, getString(R.string.action_app_info)),
        )
        io.relite.home.ui.menu.LauncherContextMenu.show(anchor, actions) { actionId ->
            when (actionId) {
                MENU_ID_ADD_TO_HOME -> {
                    app.workspaceController.addApp(entry.componentKey)
                    host.workspaceChanged()
                }
                MENU_ID_PIN_TO_DOCK -> {
                    // Section 28: pinning leaves any existing home shortcut for the
                    // same app untouched — home and dock are independent surfaces.
                    val pinned = app.workspaceController.addToDock(entry.componentKey)
                    if (!pinned) {
                        Toast.makeText(requireContext(), R.string.dock_full, Toast.LENGTH_SHORT).show()
                    } else {
                        host.workspaceChanged()
                    }
                }
                MENU_ID_ADD_TO_FOLDER -> {
                    io.relite.home.ui.folder.FolderPicker.show(
                        requireContext(),
                        app.workspaceController,
                        entry.componentKey,
                    ) { host.workspaceChanged() }
                }
                MENU_ID_ADD_TO_APPS_FOLDER -> {
                    DrawerFolderPicker.show(requireContext(), entry.componentKey) { refreshAppsAndFolders(app) }
                }
                MENU_ID_APP_INFO -> openAppInfo(entry.packageName)
            }
        }
    }

    private fun openAppInfo(packageName: String) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
        startActivity(intent)
    }

    /** Section 6 (v0.5.0 completion pass): a Custom-grid folder tile's own long-press menu — rename/add/delete via [DrawerFolderSheetDialog], or a quick delete right from here. */
    private fun showFolderMenu(app: ReliteHomeApplication, folder: DrawerFolder, anchor: View) {
        val actions = listOf(
            io.relite.home.ui.menu.LauncherAction(MENU_ID_OPEN_APPS_FOLDER, getString(R.string.action_open_folder)),
            io.relite.home.ui.menu.LauncherAction(MENU_ID_DELETE_APPS_FOLDER, getString(R.string.action_delete_folder)),
        )
        io.relite.home.ui.menu.LauncherContextMenu.show(anchor, actions) { actionId ->
            when (actionId) {
                MENU_ID_OPEN_APPS_FOLDER ->
                    DrawerFolderSheetDialog.show(requireActivity().supportFragmentManager, folder.id) { refreshAppsAndFolders(app) }
                MENU_ID_DELETE_APPS_FOLDER -> {
                    val remaining = AppsPreference.getFolders(requireContext()).filterNot { it.id == folder.id }
                    AppsPreference.setFolders(requireContext(), remaining)
                    val order = AppsPreference.getCustomOrder(requireContext())
                        .filterNot { it == AppsPreference.FOLDER_SLOT_PREFIX + folder.id }
                    val toAppend = folder.memberComponentKeys.filterNot { it in order }
                    AppsPreference.setCustomOrder(requireContext(), order + toAppend)
                    refreshAppsAndFolders(app)
                }
            }
        }
    }

    companion object {
        private const val MENU_ID_ADD_TO_HOME = 1
        private const val MENU_ID_APP_INFO = 2
        private const val MENU_ID_PIN_TO_DOCK = 3
        private const val MENU_ID_ADD_TO_FOLDER = 4
        private const val MENU_ID_ADD_TO_APPS_FOLDER = 5
        private const val MENU_ID_OPEN_APPS_FOLDER = 6
        private const val MENU_ID_DELETE_APPS_FOLDER = 7
        private const val MENU_ID_SORT = 8
        private const val MENU_ID_HOME_SETTINGS = 9
    }
}
