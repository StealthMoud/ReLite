package io.relite.home

import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.relite.home.data.GridPosition
import io.relite.home.data.Workspace
import io.relite.home.data.WorkspaceItem
import io.relite.home.ui.MainActivity
import org.hamcrest.Matcher
import org.hamcrest.Matchers.any
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Section 19-25 (v0.5.0 completion pass): the real drag-app-onto-app
 * folder-create gesture, driven by a raw long-press -> drag -> dwell ->
 * release [MotionEvent] sequence injected through Espresso's
 * [UiController] — a plain `adb shell input swipe` can't express this (it
 * interpolates linearly for its whole duration with no stationary hold
 * phase, which cancels Android's own long-press detection before it ever
 * fires — confirmed live while developing this test), and separate
 * `adb shell input motionevent DOWN/MOVE/UP` invocations turned out not to
 * behave as one continuous gesture either. This test is also what caught a
 * real, previously-latent bug: a drag whose path is close to purely
 * horizontal could be stolen mid-gesture by ViewPager2's own page-swipe
 * detector, because nothing called requestDisallowInterceptTouchEvent once
 * a drag armed — fixed in HomePageFragment alongside this test.
 */
@RunWith(AndroidJUnit4::class)
class HomeDragToFolderInstrumentationTest {

    @Test
    fun draggingOneHomeAppOntoAnotherCreatesAFolder() {
        lateinit var sourceComponent: String
        lateinit var targetComponent: String

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val app = activity.application as ReliteHomeApplication
                app.workspaceController.replaceWorkspace(Workspace.empty())
                val apps = app.appRepository.loadAll().take(2)
                sourceComponent = apps[0].componentKey
                targetComponent = apps[1].componentKey
                // Adjacent cells: (0,0) is the drag source, (1,0) the target.
                app.workspaceController.addApp(sourceComponent)
                app.workspaceController.addApp(targetComponent)
            }
            scenario.recreate()

            onView(withId(R.id.page_grid)).check(matches(isDisplayed()))
            onView(withId(R.id.page_grid)).perform(dragFirstCellOntoSecondCell())

            scenario.onActivity { activity ->
                val app = activity.application as ReliteHomeApplication
                val items = app.workspaceController.current().items
                assertEquals(1, items.size)
                val folder = items.single() as WorkspaceItem.FolderIcon
                assertEquals(GridPosition(0, 1, 0), folder.position)
                assertEquals(setOf(sourceComponent, targetComponent), folder.itemComponentKeys.toSet())
            }
        }
    }

    /**
     * Long-presses the grid's (0,0) cell, drags to (1,0), and holds there
     * long enough to arm the folder-create target (HomePageFragment's
     * FOLDER_HOVER_ARM_DELAY_MS) before releasing.
     */
    private fun dragFirstCellOntoSecondCell(): ViewAction = object : ViewAction {
        override fun getConstraints(): Matcher<View> = any(View::class.java)
        override fun getDescription() = "long-press cell (0,0), drag onto cell (1,0), dwell, release"

        override fun perform(uiController: UiController, view: View) {
            val grid = view as io.relite.home.ui.home.WorkspaceGridLayout
            val sourceCell = grid.viewAt(0, 0) ?: error("no view at (0,0)")
            val targetCell = grid.viewAt(1, 0) ?: error("no view at (1,0)")

            val sourceLoc = IntArray(2).also { sourceCell.getLocationOnScreen(it) }
            val targetLoc = IntArray(2).also { targetCell.getLocationOnScreen(it) }
            val startX = sourceLoc[0] + sourceCell.width / 2f
            val startY = sourceLoc[1] + sourceCell.height / 2f
            val endX = targetLoc[0] + targetCell.width / 2f
            val endY = targetLoc[1] + targetCell.height / 2f

            val downTime = SystemClock.uptimeMillis()
            fun event(action: Int, x: Float, y: Float, eventTime: Long): MotionEvent =
                MotionEvent.obtain(downTime, eventTime, action, x, y, 0)

            uiController.injectMotionEvent(event(MotionEvent.ACTION_DOWN, startX, startY, downTime))
            // Long enough for the OS's own long-press timeout to fire while
            // stationary — this app's OnLongClickListener is what arms the drag.
            uiController.loopMainThreadForAtLeast(700)

            // A few interpolated MOVE steps rather than one jump, matching a
            // real finger drag closely enough for HomePageFragment's own
            // touch-slop/edge-hover logic to behave the same as it does live.
            val steps = 6
            for (i in 1..steps) {
                val t = i / steps.toFloat()
                val x = startX + (endX - startX) * t
                val y = startY + (endY - startY) * t
                uiController.injectMotionEvent(event(MotionEvent.ACTION_MOVE, x, y, SystemClock.uptimeMillis()))
                uiController.loopMainThreadForAtLeast(30)
            }

            // Dwell on the target long enough to arm the folder-create drop
            // (FOLDER_HOVER_ARM_DELAY_MS = 400ms) with margin.
            uiController.loopMainThreadForAtLeast(600)

            uiController.injectMotionEvent(event(MotionEvent.ACTION_UP, endX, endY, SystemClock.uptimeMillis()))
            uiController.loopMainThreadForAtLeast(300)
        }
    }

    @After
    fun tearDown() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val app = activity.application as ReliteHomeApplication
                app.workspaceController.replaceWorkspace(Workspace.empty())
            }
        }
    }
}
