package io.relite.home.ui

import android.content.ComponentName
import android.content.Intent
import android.content.pm.LauncherApps
import android.os.Bundle
import android.os.Process
import android.view.View
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

        val workspace = app.workspaceRepository.load()
        pageIndicator.pageCount = workspace.pageCount

        pager.adapter = HomePagerAdapter(this, workspace.pageCount) { fragment ->
            fragment.onAppLaunch = { componentKey -> launchApp(componentKey) }
            fragment.onFolderOpen = { folder -> openFolder(folder) }
        }
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                pageIndicator.currentPage = position
            }
        })

        val launcherApps = getSystemService(LauncherApps::class.java)
        val allApps = app.appRepository.loadAll().associateBy { it.componentKey }
        dock.bind(
            componentKeys = workspace.dockComponentKeys,
            allApps = allApps,
            launcherApps = launcherApps,
            onAppClick = { launchApp(it.componentKey) },
            onAppsButtonClick = { showAppDrawer() },
        )

        drawerContainer.visibility = View.GONE
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
        }
        supportFragmentManager.beginTransaction()
            .replace(R.id.drawer_container, fragment)
            .commit()
        drawerContainer.visibility = View.VISIBLE
    }

    private fun hideAppDrawer() {
        drawerContainer.visibility = View.GONE
    }

    override fun onBackPressed() {
        if (drawerContainer.visibility == View.VISIBLE) {
            hideAppDrawer()
        } else {
            // ReLite Home is the home screen — back should not exit to another app.
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        app.appRepository.stop()
    }
}
