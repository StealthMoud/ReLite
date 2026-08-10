package io.relite.home

import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.relite.home.ui.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Section 33/35 (v0.4.0): MainActivity must actually launch and show the
 * workspace/dock, and the drawer must actually open from the dock's Apps
 * button — not merely have controller methods that could theoretically
 * back a UI (section 170's "domain logic exists is not acceptance").
 */
@RunWith(AndroidJUnit4::class)
class MainActivityTest {

    @Test
    fun mainActivityLaunchesAndShowsTheWorkspace() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertEquals(androidx.lifecycle.Lifecycle.State.RESUMED, scenario.state)
                assertNotNullView(activity, R.id.home_pager)
                assertNotNullView(activity, R.id.dock)
            }
        }
    }

    @Test
    fun appsButtonOpensTheDrawer() {
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withContentDescription(R.string.apps_button_description)).perform(click())
            onView(withId(R.id.drawer_container)).check(matches(isDisplayed()))
        }
    }

    private fun assertNotNullView(activity: android.app.Activity, id: Int) {
        org.junit.Assert.assertNotNull(activity.findViewById<android.view.View>(id))
    }
}
