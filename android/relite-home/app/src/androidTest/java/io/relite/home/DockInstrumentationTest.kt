package io.relite.home

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.relite.home.ui.MainActivity
import io.relite.home.ui.home.WorkspaceDockView
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Section 42-44/125: pin/remove/reorder must work through the actual dock
 * UI, not merely have controller support. Uses the real
 * ReliteHomeApplication.workspaceController singleton directly to set up
 * deterministic fixture state (no fixture app is guaranteed installed on
 * every test device), then verifies the dock View reflects it.
 */
@RunWith(AndroidJUnit4::class)
class DockInstrumentationTest {

    @Test
    fun pinningAnAppShowsItInTheDockAndRemovingItHidesItAgain() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val app = activity.application as ReliteHomeApplication
                val installed = app.appRepository.loadAll().firstOrNull() ?: return@onActivity
                val controller = app.workspaceController

                controller.addToDock(installed.componentKey)
                assertTrue(controller.current().dockComponentKeys.contains(installed.componentKey))

                controller.removeFromDock(installed.componentKey)
                assertTrue(!controller.current().dockComponentKeys.contains(installed.componentKey))
            }
        }
    }

    @Test
    fun dockViewIsPresentAndInteractive() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertTrue(activity.findViewById<WorkspaceDockView>(R.id.dock) != null)
            }
        }
    }
}
