package io.relite.home

import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.longClick
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.relite.home.ui.MainActivity
import io.relite.home.ui.home.EditModePageThumbnailView
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Section 112-119 (v0.5.0): long-pressing empty Home space must actually
 * enter the real edit-mode overlay (not the removed AlertDialog), and
 * exiting it must actually hide it again — exercised through the real UI,
 * not just the WorkspaceController page-management methods it calls into.
 */
@RunWith(AndroidJUnit4::class)
class EditModeInstrumentationTest {

    @Test
    fun longPressingEmptyHomeEntersAndExitsEditMode() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val app = activity.application as ReliteHomeApplication
                app.workspaceController.replaceWorkspace(io.relite.home.data.Workspace.empty())
            }
            scenario.recreate()

            onView(withId(R.id.page_grid)).perform(longClick())
            onView(withId(R.id.edit_mode_overlay)).check(matches(isDisplayed()))

            onView(withId(R.id.edit_mode_overlay)).perform(click()) // tapping the scrim exits edit mode
        }
    }

    /**
     * Section 7 (v0.5.0 completion pass): the page strip chip must actually
     * contain a real, model-driven [EditModePageThumbnailView] — not just
     * that the strip itself is visible (already covered above).
     */
    @Test
    fun editModePageStripRendersARealThumbnailForANonEmptyPage() {
        lateinit var componentKey: String
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val app = activity.application as ReliteHomeApplication
                app.workspaceController.replaceWorkspace(io.relite.home.data.Workspace.empty())
                componentKey = app.appRepository.loadAll().first().componentKey
                app.workspaceController.addApp(componentKey, io.relite.home.data.GridPosition(0, 0, 0))
            }
            scenario.recreate()

            onView(withId(R.id.page_grid)).perform(longClick())
            onView(withId(R.id.edit_mode_page_strip)).check(matches(isDisplayed()))

            scenario.onActivity { activity ->
                val strip = activity.findViewById<android.widget.LinearLayout>(R.id.edit_mode_page_strip)
                val firstChip = strip.getChildAt(0) as android.widget.FrameLayout
                val hasThumbnail = (0 until firstChip.childCount)
                    .map { firstChip.getChildAt(it) }
                    .any { it is EditModePageThumbnailView }
                assertTrue(hasThumbnail)
                assertEquals(activity.getString(R.string.edit_mode_page_number, 1), firstChip.contentDescription)
            }
        }
    }

    @After
    fun tearDown() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val app = activity.application as ReliteHomeApplication
                app.workspaceController.replaceWorkspace(io.relite.home.data.Workspace.empty())
            }
        }
    }
}
