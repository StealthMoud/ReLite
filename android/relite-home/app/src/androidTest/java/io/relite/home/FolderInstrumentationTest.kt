package io.relite.home

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.relite.home.data.GridPosition
import io.relite.home.data.WorkspaceItem
import io.relite.home.ui.MainActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Section 43-44/58: a folder actually persisted in the workspace must
 * render on the real Home grid without crashing — this exercises
 * HomePageFragment.iconFor -> FolderPreview.render on a live device, not
 * just the pure WorkspaceController mutation logic already covered by JVM
 * tests. Uses the real ReliteHomeApplication.workspaceController singleton
 * directly to seed deterministic fixture state.
 */
@RunWith(AndroidJUnit4::class)
class FolderInstrumentationTest {

    @Test
    fun aPersistedFolderRendersOnHomeWithoutCrashing() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val app = activity.application as ReliteHomeApplication
                val controller = app.workspaceController
                val installed = app.appRepository.loadAll().take(2).map { it.componentKey }

                val folderId = controller.createFolder("Test Folder", installed, GridPosition(0, 0, 0))
                assertTrue(folderId != null)
            }
        }

        // Recreate MainActivity so the freshly-persisted folder is actually
        // bound and drawn by HomePageFragment on a clean launch.
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val app = activity.application as ReliteHomeApplication
                val folder = app.workspaceController.current().items.filterIsInstance<WorkspaceItem.FolderIcon>().firstOrNull()
                assertTrue(folder != null)
            }
            assertEquals(androidx.lifecycle.Lifecycle.State.RESUMED, scenario.state)
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
}
