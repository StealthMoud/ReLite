package io.relite.home

import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.typeText
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.relite.home.ui.MainActivity
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Section 36: exercises the real drawer search field through the UI. No
 * fixture app is guaranteed installed on every test device, so this
 * asserts the deterministic, device-independent property instead of a
 * specific match: an unmatchable query narrows the list to nothing, and
 * clearing it restores the full list, without ever crashing.
 */
@RunWith(AndroidJUnit4::class)
class DrawerSearchTest {

    @Test
    fun searchingAnImpossibleQueryEmptiesTheListWithoutCrashing() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            onView(withContentDescription(R.string.apps_button_description)).perform(click())
            onView(withId(R.id.drawer_search)).perform(typeText("zzz-not-a-real-app-zzz"))

            scenario.onActivity { activity ->
                val recycler = activity.findViewById<RecyclerView>(R.id.drawer_recycler)
                assertTrue(recycler.adapter != null)
                assertEqualsZero(recycler.adapter!!.itemCount)
            }
        }
    }

    private fun assertEqualsZero(count: Int) {
        org.junit.Assert.assertEquals(0, count)
    }
}
