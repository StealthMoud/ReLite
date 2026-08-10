package io.relite.home.ui.drawer

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
import io.relite.home.ui.settings.HomeSettingsActivity
import io.relite.home.util.AppSearch
import io.relite.home.util.AppsPreference
import io.relite.home.util.AppsSortMode

/**
 * Alphabetical, searchable app drawer. No network, no ads, no "recommended
 * apps" section — the drawer only ever shows what LauncherApps reports.
 */
class AppDrawerFragment : Fragment(R.layout.fragment_app_drawer) {

    private lateinit var adapter: AppDrawerAdapter
    private lateinit var recycler: RecyclerView
    private lateinit var dragHelper: ItemTouchHelper
    private var allApps: List<AppEntry> = emptyList()
    private var appsChangedSubscription: AppRepository.Subscription? = null
    private var sortMode: AppsSortMode = AppsSortMode.CUSTOM

    var onLaunch: ((AppEntry) -> Unit)? = null
    var onWorkspaceChanged: (() -> Unit)? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val app = requireActivity().application as ReliteHomeApplication
        sortMode = AppsPreference.getSortMode(requireContext())

        adapter = AppDrawerAdapter(
            iconCache = app.iconCache,
            onAppClick = { onLaunch?.invoke(it) },
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
        )

        recycler = view.findViewById(R.id.drawer_recycler)
        recycler.adapter = adapter
        recycler.setHasFixedSize(true)
        recycler.clipToPadding = false

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
                // Section 92: persist the settled order once the drag ends,
                // not on every intermediate onMove — a real device can fire
                // many onMove calls per gesture.
                AppsPreference.setCustomOrder(requireContext(), adapter.currentList.map { it.componentKey })
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
        adapter.submitList(applyCurrentQuery())

        appsChangedSubscription = app.appRepository.onAppsChanged {
            allApps = app.appRepository.loadAll()
            adapter.submitList(applyCurrentQuery())
        }

        val searchField = view.findViewById<EditText>(R.id.drawer_search)
        searchField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                currentQuery = s?.toString().orEmpty()
                applySortModeToLayout() // search always renders vertically, sort mode or not
                adapter.submitList(applyCurrentQuery())
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        view.findViewById<View>(R.id.drawer_more).setOnClickListener { showMoreMenu() }
    }

    private var currentQuery: String = ""

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
        recycler.layoutManager = GridLayoutManager(requireContext(), app.workspaceController.gridSpec.columns, orientation, false)
        dragHelper.attachToRecyclerView(if (showingCustomGrid) recycler else null)
    }

    private fun applyCurrentQuery(): List<AppEntry> {
        if (currentQuery.isNotEmpty()) return AppSearch.search(allApps, currentQuery)
        return when (sortMode) {
            AppsSortMode.ALPHABETICAL -> AppSearch.alphabetical(allApps)
            AppsSortMode.CUSTOM -> AppSearch.customOrder(allApps, AppsPreference.getCustomOrder(requireContext()))
        }
    }

    /** Section 89-90 (v0.5.0): minimum viable "More" menu — Sort and Home screen settings. */
    private fun showMoreMenu() {
        val options = listOf(getString(R.string.action_sort), getString(R.string.action_home_settings))
        AlertDialog.Builder(requireContext())
            .setItems(options.toTypedArray()) { _, which ->
                when (which) {
                    0 -> showSortChooser()
                    1 -> startActivity(Intent(requireContext(), HomeSettingsActivity::class.java))
                }
            }
            .show()
    }

    private fun showSortChooser() {
        val modes = listOf(
            AppsSortMode.CUSTOM to getString(R.string.sort_custom_order),
            AppsSortMode.ALPHABETICAL to getString(R.string.sort_alphabetical_order),
        )
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.action_sort)
            .setItems(modes.map { it.second }.toTypedArray()) { _, which ->
                sortMode = modes[which].first
                AppsPreference.setSortMode(requireContext(), sortMode)
                applySortModeToLayout()
                adapter.submitList(applyCurrentQuery())
            }
            .show()
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
            io.relite.home.ui.menu.LauncherAction(MENU_ID_APP_INFO, getString(R.string.action_app_info)),
        )
        io.relite.home.ui.menu.LauncherContextMenu.show(anchor, actions) { actionId ->
            when (actionId) {
                MENU_ID_ADD_TO_HOME -> {
                    app.workspaceController.addApp(entry.componentKey)
                    onWorkspaceChanged?.invoke()
                }
                MENU_ID_PIN_TO_DOCK -> {
                    // Section 28: pinning leaves any existing home shortcut for the
                    // same app untouched — home and dock are independent surfaces.
                    val pinned = app.workspaceController.addToDock(entry.componentKey)
                    if (!pinned) {
                        Toast.makeText(requireContext(), R.string.dock_full, Toast.LENGTH_SHORT).show()
                    } else {
                        onWorkspaceChanged?.invoke()
                    }
                }
                MENU_ID_ADD_TO_FOLDER -> {
                    io.relite.home.ui.folder.FolderPicker.show(
                        requireContext(),
                        app.workspaceController,
                        entry.componentKey,
                    ) { onWorkspaceChanged?.invoke() }
                }
                MENU_ID_APP_INFO -> openAppInfo(entry.packageName)
            }
        }
    }

    private fun openAppInfo(packageName: String) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
        startActivity(intent)
    }

    companion object {
        private const val MENU_ID_ADD_TO_HOME = 1
        private const val MENU_ID_APP_INFO = 2
        private const val MENU_ID_PIN_TO_DOCK = 3
        private const val MENU_ID_ADD_TO_FOLDER = 4
    }
}
