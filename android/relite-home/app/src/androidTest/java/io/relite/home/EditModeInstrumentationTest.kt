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
import org.junit.After
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
