package io.relite.home

import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.relite.home.data.GridPosition
import io.relite.home.ui.MainActivity
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Section 5-10 (v0.5.0 completion pass): FragmentStateAdapter reuses an
 * already-existing page Fragment across ActivityScenario.recreate() rather
 * than calling createFragment again — the exact path where the previous
 * lambda-field wiring (fragment.onFolderOpen = { ... } set only inside
 * createFragment's configure block) went permanently null. This taps a
 * folder on the actual Home grid *after* a recreate() to prove
 * HomePageFragment resolves LauncherHost fresh on every call instead of
 * depending on a field that only ever got set once.
 */
@RunWith(AndroidJUnit4::class)
class HomePageRestorationTest {

    @Test
    fun aHomeFolderStaysOpenableAfterActivityRecreation() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val app = activity.application as ReliteHomeApplication
                val installed = app.appRepository.loadAll().take(2).map { it.componentKey }
                app.workspaceController.createFolder(TEST_FOLDER_LABEL, installed, GridPosition(0, 0, 0))
            }
        }

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.recreate()

            onView(withText(TEST_FOLDER_LABEL)).perform(click())
            onView(withId(R.id.folder_title)).check(matches(isDisplayed()))
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

    private companion object {
        const val TEST_FOLDER_LABEL = "Restore Test Folder"
    }
}
