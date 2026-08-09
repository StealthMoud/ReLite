package io.relite.home.ui

import android.content.ComponentName
import android.content.Intent
import android.content.pm.LauncherApps
import android.os.Bundle
import android.os.Process
import android.view.View
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.FragmentContainerView
import androidx.viewpager2.widget.ViewPager2
import io.relite.home.R
import io.relite.home.ReliteHomeApplication
import io.relite.home.data.WorkspaceItem
import io.relite.home.ui.drawer.AppDrawerFragment
import io.relite.home.ui.folder.FolderSheetDialog
import io.relite.home.ui.home.HomePagerAdapter
import io.relite.home.ui.home.PageIndicatorView
import io.relite.home.ui.home.WorkspaceDockView

/**
 * ReLite Home's single top-level screen: workspace pages + page indicator +
 * dock, with the app drawer as a full-screen fragment that slides up on
 * swipe. No notification shade, no lock screen, no quick settings — those
 * remain stock SystemUI on the stock-ROM path (master plan section 21).
 */
class MainActivity : AppCompatActivity() {

    private lateinit var app: ReliteHomeApplication
    private lateinit var pager: ViewPager2
    private lateinit var pageIndicator: PageIndicatorView
    private lateinit var dock: WorkspaceDockView
    private lateinit var drawerContainer: FragmentContainerView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        app = application as ReliteHomeApplication

        pager = findViewById(R.id.home_pager)
        pageIndicator = findViewById(R.id.page_indicator)
        dock = findViewById(R.id.dock)
        drawerContainer = findViewById(R.id.drawer_container)

        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                pageIndicator.currentPage = position
            }
        })

        refreshWorkspace()

        drawerContainer.visibility = View.GONE

        // ReLite Home is the home screen — back should close the drawer if
        // it's open, and otherwise do nothing (never exit to another app).
        // Using OnBackPressedCallback rather than overriding the deprecated
        // Activity.onBackPressed() means there's no super call to forget
        // and no ambiguity about what "not consuming" the event would do.
        onBackPressedDispatcher.addCallback(this) {
            if (drawerContainer.visibility == View.VISIBLE) {
                hideAppDrawer()
            }
        }
    }

    // Section 48 (v0.4.0): the process-scoped AppWidgetHost only delivers
    // widget updates while "listening" — must start/stop with this
    // Activity's visible lifecycle, not the Application's, or widgets would
    // either never render or keep receiving updates while nothing is shown.
    override fun onStart() {
        super.onStart()
        app.appWidgetHost.startListening()
    }

    override fun onStop() {
        super.onStop()
        app.appWidgetHost.stopListening()
    }

    /**
     * (Re-)builds the pager and dock from the current [io.relite.home.data.WorkspaceController]
     * state. Called on initial load and after any edit (add/remove/move,
     * dock/folder changes) — rebuilding the pager is the simplest correct
     * way to keep every page's RecyclerView in sync with the single
     * source of truth without a more elaborate observer/diffing layer
     * that this launcher's scope doesn't yet justify.
     */
    private fun refreshWorkspace() {
        val workspace = app.workspaceController.current()
        pageIndicator.pageCount = workspace.pageCount

        pager.adapter = HomePagerAdapter(this, workspace.pageCount) { fragment ->
            fragment.onAppLaunch = { componentKey -> launchApp(componentKey) }
            fragment.onFolderOpen = { folder -> openFolder(folder) }
            fragment.onWorkspaceChanged = { refreshWorkspace() }
        }

        val allApps = app.appRepository.loadAll().associateBy { it.componentKey }
        dock.bind(
            componentKeys = workspace.dockComponentKeys,
            allApps = allApps,
            iconCache = app.iconCache,
            onAppClick = { launchApp(it.componentKey) },
            onAppsButtonClick = { showAppDrawer() },
        )
    }

    private fun launchApp(componentKey: String) {
        val (packageName, activityName) = componentKey.split("/", limit = 2)
        val component = ComponentName(packageName, activityName)
        val launcherApps = getSystemService(LauncherApps::class.java)
        launcherApps.startMainActivity(component, Process.myUserHandle(), null, null)
        hideAppDrawer()
    }

    private fun openFolder(folder: WorkspaceItem.FolderIcon) {
        val dialog = FolderSheetDialog.newInstance(folder.label, folder.itemComponentKeys)
        dialog.onAppLaunch = { launchApp(it.componentKey) }
        dialog.show(supportFragmentManager, "folder")
    }

    private fun showAppDrawer() {
        if (drawerContainer.visibility == View.VISIBLE) return
        val fragment = AppDrawerFragment().apply {
            onLaunch = { launchApp(it.componentKey) }
            onWorkspaceChanged = { refreshWorkspace() }
        }
        supportFragmentManager.beginTransaction()
            .replace(R.id.drawer_container, fragment)
            .commit()
        drawerContainer.visibility = View.VISIBLE
    }

    private fun hideAppDrawer() {
        drawerContainer.visibility = View.GONE
    }

    // No onDestroy() override here on purpose: appRepository is owned by
    // ReliteHomeApplication for the process's entire lifetime, not by any
    // one Activity instance (section 11, v0.2.0). Activity destruction can
    // happen from a configuration change or task recreation while the
    // process — and the callback registration — stays alive; a prior
    // version called appRepository.stop() from here, which unregistered
    // the LauncherApps callback on the first recreation and silently
    // stopped the drawer from ever refreshing again for the rest of the
    // process's life.
}
