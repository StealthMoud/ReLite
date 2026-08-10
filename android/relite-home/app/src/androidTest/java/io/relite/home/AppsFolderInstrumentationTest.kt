package io.relite.home

import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.longClick
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.relite.home.ui.MainActivity
import io.relite.home.util.AppsPreference
import io.relite.home.util.AppsSortMode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Section 6 (v0.5.0 completion pass): an Apps-screen-native folder, created
 * through the real "Add to Apps folder" menu action reachable from
 * Alphabetical order (see AppDrawerFragment.showLongPressMenu's kdoc for
 * why Custom order's own long-press can't host this menu), and verified to
 * actually occupy a slot in the persisted Custom order afterward — not just
 * that WorkspaceController/AppsPreference math works in isolation.
 */
@RunWith(AndroidJUnit4::class)
class AppsFolderInstrumentationTest {

    @Test
    fun creatingAnAppsFolderFromTheAlphabeticalMenuGroupsItInCustomOrder() {
        lateinit var targetLabel: String
        lateinit var targetComponentKey: String

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val app = activity.application as ReliteHomeApplication
                AppsPreference.setFolders(activity, emptyList())
                AppsPreference.setCustomOrder(activity, emptyList())
                val first = app.appRepository.loadAll().minByOrNull { it.label.lowercase() }!!
                targetLabel = first.label
                targetComponentKey = first.componentKey
            }

            onView(withContentDescription(R.string.apps_button_description)).perform(click())

            // Section 92: switch to Alphabetical order through the real
            // Sort chooser rather than writing the preference directly —
            // AppDrawerFragment is added once and kept alive (see its
            // LauncherHost kdoc), so it only reads AppsPreference at that
            // one-time creation; a preference write after the fact would
            // never reach its already-live `sortMode` field or re-run
            // applySortModeToLayout(). Custom order's own long-press starts
            // a reorder drag instead of the action menu this test needs.
            onView(withId(R.id.drawer_more)).perform(click())
            onView(withText(R.string.action_sort)).perform(click())
            onView(withText(R.string.sort_alphabetical_order)).perform(click())

            onView(withText(targetLabel)).perform(longClick())
            onView(withText(R.string.action_add_to_apps_folder)).perform(click())
            onView(withText(R.string.new_folder_option)).perform(click())
            onView(withText(R.string.ok)).perform(click())

            scenario.onActivity { activity ->
                val folders = AppsPreference.getFolders(activity)
                assertEquals(1, folders.size)
                assertTrue(targetComponentKey in folders.single().memberComponentKeys)

                val order = AppsPreference.getCustomOrder(activity)
                assertTrue(order.contains(AppsPreference.FOLDER_SLOT_PREFIX + folders.single().id))
                assertTrue(targetComponentKey !in order)
            }
        }
    }

    @After
    fun tearDown() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                AppsPreference.setFolders(activity, emptyList())
                AppsPreference.setCustomOrder(activity, emptyList())
                AppsPreference.setSortMode(activity, AppsSortMode.CUSTOM)
            }
        }
    }
}
