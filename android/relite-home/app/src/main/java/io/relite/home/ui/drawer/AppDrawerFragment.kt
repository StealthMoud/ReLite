package io.relite.home.ui.drawer

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.Menu
import android.view.View
import android.widget.EditText
import android.widget.PopupMenu
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.relite.home.R
import io.relite.home.ReliteHomeApplication
import io.relite.home.data.AppEntry
import io.relite.home.data.AppRepository
import io.relite.home.util.AppSearch

/**
 * Alphabetical, searchable app drawer. No network, no ads, no "recommended
 * apps" section — the drawer only ever shows what LauncherApps reports.
 */
class AppDrawerFragment : Fragment(R.layout.fragment_app_drawer) {

    private lateinit var adapter: AppDrawerAdapter
    private var allApps: List<AppEntry> = emptyList()
    private var appsChangedSubscription: AppRepository.Subscription? = null

    var onLaunch: ((AppEntry) -> Unit)? = null
    var onWorkspaceChanged: (() -> Unit)? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val app = requireActivity().application as ReliteHomeApplication

        adapter = AppDrawerAdapter(
            iconCache = app.iconCache,
            onAppClick = { onLaunch?.invoke(it) },
            onAppLongClick = { entry, anchor -> showLongPressMenu(app, entry, anchor); true },
        )

        val recycler = view.findViewById<RecyclerView>(R.id.drawer_recycler)
        recycler.layoutManager = GridLayoutManager(requireContext(), app.workspaceController.gridSpec.columns)
        recycler.adapter = adapter
        recycler.setHasFixedSize(true)
        recycler.clipToPadding = false

        // Section 21: when the IME opens over the drawer, keep the last
        // rows of results reachable rather than permanently hidden behind
        // the keyboard.
        val recyclerBasePaddingBottom = recycler.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(view) { _, insets ->
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            val navBar = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
            recycler.setPadding(
                recycler.paddingLeft,
                recycler.paddingTop,
                recycler.paddingRight,
                recyclerBasePaddingBottom + maxOf(ime, navBar),
            )
            insets
        }

        allApps = AppSearch.alphabetical(app.appRepository.loadAll())
        adapter.submitList(allApps)

        appsChangedSubscription = app.appRepository.onAppsChanged {
            allApps = AppSearch.alphabetical(app.appRepository.loadAll())
            adapter.submitList(applyCurrentQuery())
        }

        val searchField = view.findViewById<EditText>(R.id.drawer_search)
        searchField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                currentQuery = s?.toString().orEmpty()
                adapter.submitList(applyCurrentQuery())
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private var currentQuery: String = ""

    override fun onDestroyView() {
        super.onDestroyView()
        appsChangedSubscription?.dispose()
        appsChangedSubscription = null
    }

    private fun applyCurrentQuery(): List<AppEntry> = AppSearch.search(allApps, currentQuery)

    /**
     * Minimum viable "Add to Home" affordance (section 15, v0.2.0):
     * placed in the first free grid cell via WorkspaceController rather
     * than a drag gesture, which is out of scope for this pass. App info
     * uses the standard system Settings intent — no custom permissions
     * manager (section 25).
     */
    private fun showLongPressMenu(app: ReliteHomeApplication, entry: AppEntry, anchor: View) {
        PopupMenu(requireContext(), anchor).apply {
            menu.add(Menu.NONE, MENU_ID_ADD_TO_HOME, Menu.NONE, R.string.action_add_to_home)
            menu.add(Menu.NONE, MENU_ID_PIN_TO_DOCK, Menu.NONE, R.string.action_pin_to_dock)
            menu.add(Menu.NONE, MENU_ID_ADD_TO_FOLDER, Menu.NONE, R.string.action_add_to_folder)
            menu.add(Menu.NONE, MENU_ID_APP_INFO, Menu.NONE, R.string.action_app_info)
            setOnMenuItemClickListener { item ->
                // Section 86: switch on the stable item id assigned above,
                // never on displayed title text — localization or two
                // menu entries sharing a label could otherwise pick the
                // wrong branch.
                when (item.itemId) {
                    MENU_ID_ADD_TO_HOME -> {
                        app.workspaceController.addApp(entry.componentKey)
                        onWorkspaceChanged?.invoke()
                        true
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
                        true
                    }
                    MENU_ID_ADD_TO_FOLDER -> {
                        io.relite.home.ui.folder.FolderPicker.show(
                            requireContext(),
                            app.workspaceController,
                            entry.componentKey,
                        ) { onWorkspaceChanged?.invoke() }
                        true
                    }
                    MENU_ID_APP_INFO -> {
                        openAppInfo(entry.packageName)
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
        private const val MENU_ID_ADD_TO_HOME = 1
        private const val MENU_ID_APP_INFO = 2
        private const val MENU_ID_PIN_TO_DOCK = 3
        private const val MENU_ID_ADD_TO_FOLDER = 4
    }
}
