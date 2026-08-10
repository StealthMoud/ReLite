package io.relite.home.ui

import android.content.ComponentName
import android.content.Intent
import android.content.pm.LauncherApps
import android.net.Uri
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.view.View
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.FragmentContainerView
import androidx.viewpager2.widget.ViewPager2
import android.widget.FrameLayout
import io.relite.home.R
import io.relite.home.ReliteHomeApplication
import io.relite.home.data.WorkspaceItem
import io.relite.home.ui.drawer.AppDrawerFragment
import io.relite.home.ui.folder.FolderSheetDialog
import io.relite.home.ui.home.DragOverlay
import io.relite.home.ui.home.HomePagerAdapter
import io.relite.home.ui.home.PageIndicatorView
import io.relite.home.ui.home.WorkspaceDockView
import io.relite.home.ui.widget.WidgetPickerActivity

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
    private lateinit var dragOverlay: DragOverlay

    // Any result (OK or CANCELED) may have changed the workspace — a
    // cancellation mid-flow can still have consumed/freed a widget id, and
    // a plain refresh on cancel is a harmless no-op.
    private val widgetPickerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        refreshWorkspace()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        app = application as ReliteHomeApplication

        pager = findViewById(R.id.home_pager)
        pageIndicator = findViewById(R.id.page_indicator)
        dock = findViewById(R.id.dock)
        drawerContainer = findViewById(R.id.drawer_container)
        dragOverlay = DragOverlay(findViewById<FrameLayout>(R.id.drag_overlay))

        // Section 17-20 (v0.4.0): the home screen draws edge-to-edge behind
        // the status/nav bars (it shows the wallpaper there), so every
        // inset-sensitive edge is offset explicitly rather than relying on
        // the system to inset the whole window for us.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        applyWindowInsets()

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

    // HomeSettingsActivity (reset/import) mutates the same process-wide
    // WorkspaceController instance directly rather than returning a result —
    // simplest way to pick that up is to just re-render from its current
    // in-memory state whenever this Activity becomes visible again.
    override fun onResume() {
        super.onResume()
        refreshWorkspace()
    }

    /**
     * (Re-)builds the pager and dock from the current [io.relite.home.data.WorkspaceController]
     * state. Called on initial load and after any edit (add/remove/move,
     * dock/folder changes) — rebuilding the pager is the simplest correct
     * way to keep every page's RecyclerView in sync with the single
     * source of truth without a more elaborate observer/diffing layer
     * that this launcher's scope doesn't yet justify.
     *
     * Replacing `pager.adapter` resets ViewPager2's scroll position to 0
     * unless told otherwise, which would silently kick the user back to
     * page 0 after every single edit (add an app, pin to dock, drop a
     * cross-page drag on page 2...) — [targetPage] defaults to wherever
     * the user currently is so that doesn't happen; a caller that just
     * moved something to a specific page (the cross-page drag drop) can
     * pass that page explicitly instead.
     */
    private fun refreshWorkspace(targetPage: Int? = null) {
        val workspace = app.workspaceController.current()
        pageIndicator.pageCount = workspace.pageCount
        val resolvedTargetPage = (targetPage ?: pager.currentItem).coerceIn(0, workspace.pageCount - 1)

        pager.adapter = HomePagerAdapter(this, workspace.pageCount) { fragment ->
            fragment.onAppLaunch = { componentKey -> launchApp(componentKey) }
            fragment.onFolderOpen = { folder -> openFolder(folder) }
            fragment.onWorkspaceChanged = { refreshWorkspace() }
            fragment.onAddWidgetRequested = { widgetPickerLauncher.launch(WidgetPickerActivity.newIntent(this)) }
            fragment.onDragStart = { source -> dragOverlay.beginDrag(source) }
            fragment.onDragMove = { proxy, dx, dy -> dragOverlay.moveDrag(proxy, dx, dy) }
            fragment.onDragEnd = { proxy -> dragOverlay.endDrag(proxy) }
            fragment.onDragEdgeHover = { direction ->
                val itemCount = pager.adapter?.itemCount ?: 1
                val target = (pager.currentItem + direction).coerceIn(0, itemCount - 1)
                if (target != pager.currentItem) pager.setCurrentItem(target, true)
                pager.currentItem
            }
            fragment.onDragCommitted = { page -> refreshWorkspace(page) }
        }
        pager.setCurrentItem(resolvedTargetPage, false)

        val allApps = app.appRepository.loadAll().associateBy { it.componentKey }
        dock.bind(
            componentKeys = workspace.dockComponentKeys,
            allApps = allApps,
            iconCache = app.iconCache,
            onAppClick = { launchApp(it.componentKey) },
            onAppsButtonClick = { showAppDrawer() },
            onRemoveFromDock = { componentKey ->
                app.workspaceController.removeFromDock(componentKey)
                refreshWorkspace()
            },
            onAppInfo = { packageName -> openAppInfo(packageName) },
            onReorder = { newOrder ->
                app.workspaceController.reorderDock(newOrder)
                refreshWorkspace()
            },
            onAddToHome = { componentKey ->
                // Section 9: the dock entry stays in place — this only adds
                // a second, independent shortcut on the workspace.
                val added = app.workspaceController.addApp(componentKey)
                if (added == null) {
                    android.widget.Toast.makeText(this, R.string.workspace_full, android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    refreshWorkspace()
                }
            },
        )
    }

    /**
     * Section 17-20: keeps the workspace clear of the status bar/cutout at
     * the top and the dock clear of the gesture/nav-bar region at the
     * bottom, using the real inset values instead of a fixed padding
     * constant that would be wrong on a different device or navigation
     * mode. `pager`/`drawerContainer` re-render their own content (grid,
     * drawer list) so top padding is enough there; the dock's bottom
     * margin is nudged out by the extra system inset on top of its normal
     * design margin.
     */
    private fun applyWindowInsets() {
        val baseDockMarginBottom = resources.getDimensionPixelSize(R.dimen.dock_margin_bottom)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main_root)) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            val topInset = maxOf(systemBars.top, cutout.top)

            pager.setPadding(pager.paddingLeft, topInset, pager.paddingRight, pager.paddingBottom)
            drawerContainer.setPadding(
                drawerContainer.paddingLeft,
                topInset,
                drawerContainer.paddingRight,
                drawerContainer.paddingBottom,
            )
            dock.updateLayoutParams<android.view.ViewGroup.MarginLayoutParams> {
                bottomMargin = baseDockMarginBottom + systemBars.bottom
            }
            insets
        }
    }

    private fun openAppInfo(packageName: String) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
        startActivity(intent)
    }

    private fun launchApp(componentKey: String) {
        val (packageName, activityName) = componentKey.split("/", limit = 2)
        val component = ComponentName(packageName, activityName)
        val launcherApps = getSystemService(LauncherApps::class.java)
        launcherApps.startMainActivity(component, Process.myUserHandle(), null, null)
        hideAppDrawer()
    }

    private fun openFolder(folder: WorkspaceItem.FolderIcon) {
        val dialog = FolderSheetDialog.newInstance(folder.id)
        dialog.onAppLaunch = { launchApp(it.componentKey) }
        dialog.onWorkspaceChanged = { refreshWorkspace() }
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
