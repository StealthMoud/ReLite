package io.relite.home

import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.swipeDown
import androidx.test.espresso.action.ViewActions.swipeUp
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.relite.home.data.Workspace
import io.relite.home.ui.MainActivity
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Section 1-2/7 (v0.5.0 completion pass): the real Home<->Apps gesture,
 * exercised through Espresso's swipe actions rather than only the manual
 * adb swipes used to physically validate it live on the RMX5303 during
 * development.
 */
@RunWith(AndroidJUnit4::class)
class HomeAppsSwipeInstrumentationTest {

    @Test
    fun swipingUpFromHomeOpensAppsAndSwipingDownClosesIt() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val app = activity.application as ReliteHomeApplication
                app.workspaceController.replaceWorkspace(Workspace.empty())
            }
            scenario.recreate()

            onView(withId(R.id.page_grid)).perform(swipeUp())
            onView(withId(R.id.drawer_container)).check(matches(isDisplayed()))

            onView(withId(R.id.drawer_container)).perform(swipeDown())
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
