package io.relite.home

import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.lifecycle.Lifecycle
import io.relite.home.ui.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Section 50: ActivityScenario.recreate() is required — a config change or
 * process-death/restore must not leave dead Fragment callbacks behind
 * (the exact class of bug this launcher fixed once already for the
 * widget long-press menu, see HomePageFragment.wireInteractions).
 */
@RunWith(AndroidJUnit4::class)
class ActivityRecreationTest {

    @Test
    fun activitySurvivesRecreationAndStaysInteractive() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.recreate()
            assertEquals(Lifecycle.State.RESUMED, scenario.state)

            onView(withContentDescription(R.string.apps_button_description)).perform(click())
            onView(withId(R.id.drawer_container)).check(matches(isDisplayed()))
        }
    }
}
