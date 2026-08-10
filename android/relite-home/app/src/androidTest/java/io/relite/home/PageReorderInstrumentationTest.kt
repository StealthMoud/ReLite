package io.relite.home

import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.longClick
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.relite.home.data.Workspace
import io.relite.home.ui.MainActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Section 27-28 (v0.5.0 completion pass): page reordering, exercised
 * through the real edit-mode page-strip menu rather than only the
 * WorkspaceController unit tests already covering the permutation math.
 */
@RunWith(AndroidJUnit4::class)
class PageReorderInstrumentationTest {

    @Test
    fun movingThePageStripChipRightReordersPagesAndRemapsTheDefaultPage() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val app = activity.application as ReliteHomeApplication
                app.workspaceController.replaceWorkspace(Workspace.empty())
                app.workspaceController.addPage()
            }
            scenario.recreate()

            onView(withId(R.id.page_grid)).perform(longClick())
            onView(withId(R.id.edit_mode_page_strip)).check(androidx.test.espresso.assertion.ViewAssertions.matches(androidx.test.espresso.matcher.ViewMatchers.isDisplayed()))
            onView(withText("1")).perform(longClick())
            onView(withText(R.string.edit_mode_move_page_right)).perform(click())

            scenario.onActivity { activity ->
                val app = activity.application as ReliteHomeApplication
                assertEquals(2, app.workspaceController.current().pageCount)
                assertEquals(1, app.workspaceController.current().defaultPage)
            }
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
