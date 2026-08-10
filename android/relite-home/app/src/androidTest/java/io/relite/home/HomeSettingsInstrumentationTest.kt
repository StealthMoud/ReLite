package io.relite.home

import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.relite.home.data.HomeGridPreset
import io.relite.home.ui.settings.HomeSettingsActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Section 120-125 (v0.5.0): the redesigned sectioned Settings screen —
 * tapping the 5x6 grid card must actually change the live workspace's
 * grid, not just repaint a selection state, exercised through the real
 * Activity/View tree rather than calling WorkspaceController directly.
 */
@RunWith(AndroidJUnit4::class)
class HomeSettingsInstrumentationTest {

    @Test
    fun tappingAGridCardChangesTheRealWorkspaceGrid() {
        ActivityScenario.launch(HomeSettingsActivity::class.java).use { scenario ->
            onView(withId(R.id.settings_grid_5x6)).perform(scrollTo(), click())

            scenario.onActivity { activity ->
                val app = activity.application as ReliteHomeApplication
                assertEquals(HomeGridPreset.FIVE_BY_SIX, app.workspaceController.current().homeGrid)
            }
        }
    }

    @Test
    fun toggleSwitchesReflectAndPersistPreferences() {
        ActivityScenario.launch(HomeSettingsActivity::class.java).use {
            onView(withId(R.id.settings_show_app_labels)).perform(scrollTo(), click())
            onView(withId(R.id.settings_show_apps_button)).perform(scrollTo(), click())
        }
        // Reopening must reflect the persisted (now-flipped) state, not reset to defaults.
        ActivityScenario.launch(HomeSettingsActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertEquals(false, io.relite.home.util.HomePreference.getShowAppLabels(activity))
                assertEquals(false, io.relite.home.util.HomePreference.getShowAppsButton(activity))
                // Restore defaults so this test doesn't leak state into other tests.
                io.relite.home.util.HomePreference.setShowAppLabels(activity, true)
                io.relite.home.util.HomePreference.setShowAppsButton(activity, true)
            }
        }
    }

    @After
    fun tearDown() {
        ActivityScenario.launch(HomeSettingsActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val app = activity.application as ReliteHomeApplication
                app.workspaceController.replaceWorkspace(io.relite.home.data.Workspace.empty())
                io.relite.home.util.HomePreference.setShowAppLabels(activity, true)
                io.relite.home.util.HomePreference.setShowAppsButton(activity, true)
            }
        }
    }
}
