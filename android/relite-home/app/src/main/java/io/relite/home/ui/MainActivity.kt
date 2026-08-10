package io.relite.home.ui

import android.content.ActivityNotFoundException
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
class MainActivity : AppCompatActivity(), LauncherHost {

    private lateinit var app: ReliteHomeApplication
    private lateinit var pager: ViewPager2
    private lateinit var pageIndicator: PageIndicatorView
    private lateinit var dock: WorkspaceDockView
    private lateinit var drawerContainer: FragmentContainerView
    private lateinit var dragOverlay: DragOverlay
    private lateinit var pagerAdapter: HomePagerAdapter
    private lateinit var editModeOverlay: View
    private lateinit var editModePageStrip: android.widget.LinearLayout

    private val wallpaperPickerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {}

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
        editModeOverlay = findViewById(R.id.edit_mode_overlay)
        editModePageStrip = findViewById(R.id.edit_mode_page_strip)
        editModeOverlay.setOnClickListener { exitEditMode() } // tap the scrim to exit
        findViewById<View>(R.id.edit_mode_wallpaper).setOnClickListener {
            wallpaperPickerLauncher.launch(Intent(Intent.ACTION_SET_WALLPAPER))
        }
        findViewById<View>(R.id.edit_mode_widgets).setOnClickListener {
            widgetPickerLauncher.launch(WidgetPickerActivity.newIntent(this))
        }
        findViewById<View>(R.id.edit_mode_settings).setOnClickListener {
            startActivity(Intent(this, io.relite.home.ui.settings.HomeSettingsActivity::class.java))
        }

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

        // Created exactly once and reused for this Activity's whole
        // lifetime — see HomePagerAdapter's kdoc for why replacing
        // pager.adapter on every edit corrupted FragmentStateAdapter's
        // fragment lifecycle (duplicate/overlapping page views, found
        // live on the RMX5303).
        // Section 5-10 (v0.5.0 completion pass): fragments resolve this
        // Activity as a LauncherHost themselves rather than being wired
        // with lambda fields here — see LauncherHost's kdoc for why that
        // wiring silently broke on process-death-restored pages.
        pagerAdapter = HomePagerAdapter(this, app.workspaceController.current().pageCount)
        pager.adapter = pagerAdapter

        // Section 80/119 (v0.5.0): the very first render opens on the
        // persisted default page rather than always page 0 — every
        // subsequent refreshWorkspace() call (edits, onResume) keeps
        // defaulting to wherever the user currently is, per its own kdoc.
        refreshWorkspace(app.workspaceController.current().defaultPage)

        drawerContainer.visibility = View.GONE

        // ReLite Home is the home screen — back should close the drawer if
        // it's open, and otherwise do nothing (never exit to another app).
        // Using OnBackPressedCallback rather than overriding the deprecated
        // Activity.onBackPressed() means there's no super call to forget
        // and no ambiguity about what "not consuming" the event would do.
        onBackPressedDispatcher.addCallback(this) {
            if (editModeOverlay.visibility == View.VISIBLE) {
                exitEditMode()
            } else if (drawerContainer.visibility == View.VISIBLE) {
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
     * (Re-)binds the pager and dock from the current [io.relite.home.data.WorkspaceController]
     * state. Called on initial load and after any edit (add/remove/move,
     * dock/folder changes) via [pagerAdapter]'s `update`, which forces every
     * currently-bound page fragment to be recreated with fresh data — see
     * [HomePagerAdapter]'s kdoc for why the adapter object itself is
     * created exactly once instead of being replaced on every call here.
     *
     * [targetPage] defaults to wherever the user currently is, coerced into
     * the new page count, so an edit doesn't silently kick them back to
     * page 0; a caller that just moved something to a specific page (the
     * cross-page drag drop) can pass that page explicitly instead.
     */
    private fun refreshWorkspace(targetPage: Int? = null) {
        val workspace = app.workspaceController.current()
        pageIndicator.pageCount = workspace.pageCount
        val resolvedTargetPage = (targetPage ?: pager.currentItem).coerceIn(0, workspace.pageCount - 1)

        pagerAdapter.update(workspace.pageCount)
        pager.setCurrentItem(resolvedTargetPage, false)

        if (editModeOverlay.visibility == View.VISIBLE) rebuildEditModePageStrip()

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
            showAppsButton = io.relite.home.util.HomePreference.getShowAppsButton(this),
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

    /**
     * Section 13 (v0.5.0): the canonical safe launch path. A component that
     * looked launchable at reconciliation time can still fail here — a race
     * with an in-progress uninstall, a security-restricted target, or a
     * malformed componentKey a caller shouldn't have produced but that must
     * not be trusted blindly. On any failure this shows feedback, drops the
     * dead component from the workspace immediately (rather than waiting for
     * the next LauncherApps callback, which may never fire for a race like
     * this), and never crashes the launcher itself.
     */
    private fun launchApp(componentKey: String) {
        val parts = componentKey.split("/", limit = 2)
        if (parts.size != 2) {
            onLaunchFailed(componentKey)
            return
        }
        val component = ComponentName(parts[0], parts[1])
        val launcherApps = getSystemService(LauncherApps::class.java)
        try {
            launcherApps.startMainActivity(component, Process.myUserHandle(), null, null)
            hideAppDrawer()
        } catch (e: ActivityNotFoundException) {
            onLaunchFailed(componentKey)
        } catch (e: SecurityException) {
            onLaunchFailed(componentKey)
        } catch (e: IllegalArgumentException) {
            onLaunchFailed(componentKey)
        } catch (e: IllegalStateException) {
            onLaunchFailed(componentKey)
        }
    }

    private fun onLaunchFailed(componentKey: String) {
        android.widget.Toast.makeText(this, R.string.app_launch_failed, android.widget.Toast.LENGTH_SHORT).show()
        val stillLaunchable = app.appRepository.loadAll().map { it.componentKey }.toSet() - componentKey
        app.workspaceController.removeStaleComponents(stillLaunchable)
        refreshWorkspace()
    }

    private fun showAppDrawer() {
        if (drawerContainer.visibility == View.VISIBLE) return
        supportFragmentManager.beginTransaction()
            .replace(R.id.drawer_container, AppDrawerFragment())
            .commit()
        drawerContainer.visibility = View.VISIBLE
    }

    private fun hideAppDrawer() {
        drawerContainer.visibility = View.GONE
    }

    // --- LauncherHost -------------------------------------------------
    // Resolved by fragments via requireActivity() as LauncherHost, not by
    // activity-set lambda fields — see LauncherHost's kdoc.

    override fun launchComponent(componentKey: String) = launchApp(componentKey)

    override fun openFolder(folderId: String) {
        FolderSheetDialog.newInstance(folderId).show(supportFragmentManager, "folder")
    }

    override fun workspaceChanged(targetPage: Int?) = refreshWorkspace(targetPage)

    override fun requestWidgetPicker() {
        widgetPickerLauncher.launch(WidgetPickerActivity.newIntent(this))
    }

    override fun requestHomeEditMode() = enterEditMode()

    override fun beginDrag(source: View): View = dragOverlay.beginDrag(source)

    override fun moveDrag(proxy: View, dx: Float, dy: Float) = dragOverlay.moveDrag(proxy, dx, dy)

    override fun endDrag(proxy: View) = dragOverlay.endDrag(proxy)

    override fun requestAdjacentPage(direction: Int): Int {
        val itemCount = pagerAdapter.itemCount
        val target = (pager.currentItem + direction).coerceIn(0, itemCount - 1)
        if (target != pager.currentItem) pager.setCurrentItem(target, true)
        return pager.currentItem
    }

    /**
     * Section 112-119 (v0.5.0): replaces the previous plain AlertDialog on
     * long-press-empty-Home. The pager scales down slightly (Samsung-like
     * "workspace shrinks" cue) while the real overlay — page strip +
     * Wallpaper and style / Widgets / Home screen settings — sits on top.
     */
    private fun enterEditMode() {
        rebuildEditModePageStrip()
        editModeOverlay.visibility = View.VISIBLE
        pager.animate().scaleX(EDIT_MODE_SCALE).scaleY(EDIT_MODE_SCALE).setDuration(EDIT_MODE_ANIM_MS).start()
        dock.animate().scaleX(EDIT_MODE_SCALE).scaleY(EDIT_MODE_SCALE).setDuration(EDIT_MODE_ANIM_MS).start()
    }

    private fun exitEditMode() {
        editModeOverlay.visibility = View.GONE
        pager.animate().scaleX(1f).scaleY(1f).setDuration(EDIT_MODE_ANIM_MS).start()
        dock.animate().scaleX(1f).scaleY(1f).setDuration(EDIT_MODE_ANIM_MS).start()
    }

    /**
     * Section 80/119: one chip per page — tap jumps there, long-press offers
     * "Set as default page" / "Remove this empty page", a trailing "+" chip
     * adds a page. Real page thumbnails (rendered page content, not just a
     * number) are not implemented this pass — see CHANGELOG's Known gaps.
     */
    private fun rebuildEditModePageStrip() {
        editModePageStrip.removeAllViews()
        val workspace = app.workspaceController.current()
        val chipSize = resources.getDimensionPixelSize(R.dimen.edit_mode_chip_size)
        val chipMargin = resources.getDimensionPixelSize(R.dimen.edit_mode_chip_margin)

        for (page in 0 until workspace.pageCount) {
            val chip = android.widget.TextView(this).apply {
                text = getString(R.string.edit_mode_page_number, page + 1)
                gravity = android.view.Gravity.CENTER
                setTextColor(resources.getColor(R.color.relite_text_primary, theme))
                setBackgroundResource(if (page == workspace.defaultPage) R.drawable.bg_page_chip_default else R.drawable.bg_page_chip)
                layoutParams = android.widget.LinearLayout.LayoutParams(chipSize, chipSize).apply {
                    marginEnd = chipMargin
                }
                setOnClickListener {
                    pager.setCurrentItem(page, true)
                    exitEditMode()
                }
                setOnLongClickListener {
                    showPageChipMenu(page)
                    true
                }
            }
            editModePageStrip.addView(chip)
        }

        val addChip = android.widget.TextView(this).apply {
            text = "+"
            gravity = android.view.Gravity.CENTER
            setTextColor(resources.getColor(R.color.relite_text_primary, theme))
            setBackgroundResource(R.drawable.bg_page_chip)
            layoutParams = android.widget.LinearLayout.LayoutParams(chipSize, chipSize)
            contentDescription = getString(R.string.edit_mode_add_page)
            setOnClickListener {
                app.workspaceController.addPage()
                refreshWorkspace()
            }
        }
        editModePageStrip.addView(addChip)
    }

    private fun showPageChipMenu(page: Int) {
        val canRemove = page > 0 &&
            app.workspaceController.current().items.none { it.position.page == page }
        val options = buildList {
            add(getString(R.string.edit_mode_set_default_page))
            if (canRemove) add(getString(R.string.edit_mode_remove_page))
        }
        android.app.AlertDialog.Builder(this)
            .setItems(options.toTypedArray()) { _, which ->
                when (options[which]) {
                    getString(R.string.edit_mode_set_default_page) -> {
                        app.workspaceController.setDefaultPage(page)
                        android.widget.Toast.makeText(this, R.string.edit_mode_default_page_set, android.widget.Toast.LENGTH_SHORT).show()
                        refreshWorkspace()
                    }
                    getString(R.string.edit_mode_remove_page) -> {
                        if (app.workspaceController.removeEmptyPage(page)) {
                            android.widget.Toast.makeText(this, R.string.edit_mode_page_removed, android.widget.Toast.LENGTH_SHORT).show()
                            refreshWorkspace()
                        } else {
                            android.widget.Toast.makeText(this, R.string.edit_mode_page_not_removable, android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
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

    private companion object {
        // Section 113: "workspace scales down slightly" — subtle, not a
        // dramatic zoom-out; section 68's ~100-500ms motion baseline.
        const val EDIT_MODE_SCALE = 0.92f
        const val EDIT_MODE_ANIM_MS = 200L
    }
}
