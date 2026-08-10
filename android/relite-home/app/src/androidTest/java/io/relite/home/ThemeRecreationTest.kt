package io.relite.home

import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.relite.home.ui.MainActivity
import io.relite.home.util.ThemeMode
import io.relite.home.util.ThemePreference
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Section 16/51: switching the theme preference recreates every AppCompat
 * Activity (AppCompatDelegate.setDefaultNightMode) — the workspace and
 * drawer must still be interactive afterward, not just present.
 */
@RunWith(AndroidJUnit4::class)
class ThemeRecreationTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @After
    fun resetTheme() {
        ThemePreference.set(context, ThemeMode.SYSTEM)
    }

    @Test
    fun switchingThemeRecreatesTheActivityAndStaysInteractive() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { ThemePreference.set(context, ThemeMode.DARK) }
            scenario.recreate()
            assertEquals(Lifecycle.State.RESUMED, scenario.state)

            scenario.onActivity { ThemePreference.set(context, ThemeMode.LIGHT) }
            scenario.recreate()
            assertEquals(Lifecycle.State.RESUMED, scenario.state)

            onView(withContentDescription(R.string.apps_button_description)).perform(click())
            onView(withId(R.id.drawer_container)).check(matches(isDisplayed()))
        }
    }
}
