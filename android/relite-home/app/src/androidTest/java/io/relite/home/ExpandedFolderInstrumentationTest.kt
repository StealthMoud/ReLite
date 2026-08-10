package io.relite.home

import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.longClick
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.relite.home.data.FolderSize
import io.relite.home.data.GridPosition
import io.relite.home.data.Workspace
import io.relite.home.data.WorkspaceItem
import io.relite.home.ui.MainActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Section 26-31 (v0.5.0 completion pass): a real 2x2 expanded folder,
 * exercised through the actual Enlarge/Shrink menu action and a direct
 * tap-to-launch on a member icon, not just the WorkspaceController math
 * already covered by JVM tests.
 */
@RunWith(AndroidJUnit4::class)
class ExpandedFolderInstrumentationTest {

    @Test
    fun enlargingAFolderRendersA2x2MemberGridAndShrinkingReturnsItToCompact() {
        lateinit var memberComponent: String

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val app = activity.application as ReliteHomeApplication
                app.workspaceController.replaceWorkspace(Workspace.empty())
                memberComponent = app.appRepository.loadAll().first().componentKey
                app.workspaceController.createFolder("Test Folder", listOf(memberComponent), GridPosition(0, 0, 0))
            }
            scenario.recreate()

            onView(withText("Test Folder")).perform(longClick())
            onView(withText(io.relite.home.R.string.action_enlarge_folder)).perform(click())

            scenario.onActivity { activity ->
                val app = activity.application as ReliteHomeApplication
                val folder = app.workspaceController.current().items.single() as WorkspaceItem.FolderIcon
                assertEquals(FolderSize.EXPANDED, folder.size)
            }

            onView(withId(io.relite.home.R.id.expanded_folder_members)).check(matches(isDisplayed()))

            // Shrink back via the same folder's long-press menu.
            onView(withId(io.relite.home.R.id.expanded_folder_title)).perform(longClick())
            onView(withText(io.relite.home.R.string.action_shrink_folder)).perform(click())

            scenario.onActivity { activity ->
                val app = activity.application as ReliteHomeApplication
                val folder = app.workspaceController.current().items.single() as WorkspaceItem.FolderIcon
                assertEquals(FolderSize.COMPACT, folder.size)
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
