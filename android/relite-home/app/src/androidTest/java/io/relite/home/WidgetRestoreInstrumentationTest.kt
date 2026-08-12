package io.relite.home

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.relite.home.data.GridPosition
import io.relite.home.data.PortableWidget
import io.relite.home.data.Workspace
import io.relite.home.data.WorkspaceItem
import io.relite.home.data.WorkspaceRepository
import io.relite.home.ui.MainActivity
import io.relite.home.ui.widget.WidgetRestorer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Section 65-66 (v0.5.0 completion pass): live proof on the RMX5303 that a
 * layout export now *carries* widgets and an import genuinely restores them
 * — the gap every prior release recorded as "export drops widgets entirely".
 *
 * Runs against the real [android.appwidget.AppWidgetManager] and the app's
 * real [io.relite.home.ui.widget.ReliteAppWidgetHost], using the debug
 * build's own `ReliteTestWidgetProvider` fixture as the provider.
 *
 * ## What this can and cannot cover on this hardware, and why
 *
 * Two assumptions were tested here and **both turned out to be false on the
 * device**, which is why the suite is shaped the way it is:
 *
 * 1. A host binding a provider from its *own* package is **not** exempt from
 *    the consent requirement. `bindAppWidgetIdIfAllowed` returns false
 *    same-package. This is the real production behavior and the reason
 *    [WidgetRestorer]'s silent pass is the exception rather than the rule:
 *    `BIND_APPWIDGET` is `signature|privileged`, so an ordinary third-party
 *    launcher never holds it and essentially every imported widget reaches
 *    the user through the per-widget system consent dialog.
 * 2. `adb shell appwidget grantbind` — the documented way to hand a package
 *    that grant for testing — **does not work on this device**: it exits
 *    137 (killed) and `dumpsys appwidget` shows `Grants:` still empty. So
 *    the silently-granted branch cannot be set up here at all.
 *
 * The consequence is drawn honestly rather than papered over. The *system's*
 * half of a restore (turning a consented request into a live binding) is the
 * platform's own code and is left undriven here. **ReLite's** half — parsing
 * the descriptor, allocating, placing it at its exact recorded position and
 * span, reporting every failure mode, and never leaking host state — is
 * covered completely below, including through [WidgetRestorer.restoreOne]'s
 * `alreadyBoundId` path, which is precisely the code that runs after the
 * user accepts the consent dialog in the real settings flow.
 */
@RunWith(AndroidJUnit4::class)
class WidgetRestoreInstrumentationTest {

    private val testProvider = "io.relite.home/io.relite.home.debug.ReliteTestWidgetProvider"

    /**
     * The full export -> reset -> import cycle on-device: a placed widget's
     * descriptor survives it intact, where before this pass it was discarded.
     */
    @Test
    fun exportedWidgetSurvivesAFullExportResetImportCycleAsAPortableDescriptor() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val app = activity.application as ReliteHomeApplication
                val controller = app.workspaceController

                controller.replaceWorkspace(Workspace.empty())
                val originalId = app.appWidgetHost.allocateId()
                controller.addWidget(
                    appWidgetId = originalId,
                    spanColumns = 2,
                    spanRows = 2,
                    providerComponent = testProvider,
                    position = GridPosition(0, 1, 1),
                )

                val exported = app.workspaceRepository.exportPortable(controller.current())

                controller.replaceWorkspace(Workspace.empty())
                app.appWidgetHost.removeWidget(originalId)
                assertTrue(controller.current().items.isEmpty())

                val installedApps = app.appRepository.loadAll().map { it.componentKey }.toSet()
                val result = app.workspaceRepository.importPortable(exported, installedApps)
                    as WorkspaceRepository.ImportResult.Success

                val descriptor = result.pendingWidgets.single()
                assertEquals(testProvider, descriptor.providerComponent)
                assertEquals(GridPosition(0, 1, 1), descriptor.position)
                assertEquals(2, descriptor.spanColumns)
                assertEquals(2, descriptor.spanRows)
                // The dead device-local id is deliberately not among what
                // travelled — that is the whole point of the descriptor.
                assertTrue(result.candidate.items.none { it is WorkspaceItem.WidgetIcon })
            }
        }
    }

    /**
     * The post-consent path: exactly what runs once the user accepts the
     * system bind dialog for one widget. The widget must land at its
     * recorded place and size, on the consented id.
     */
    @Test
    fun aConsentedDescriptorIsPlacedAtItsExactRecordedPositionAndSpan() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val app = activity.application as ReliteHomeApplication
                val controller = app.workspaceController
                controller.replaceWorkspace(Workspace.empty())

                val consentedId = app.appWidgetHost.allocateId()
                val descriptor = PortableWidget(testProvider, GridPosition(0, 2, 3), 2, 1)

                val restore = WidgetRestorer.restoreOne(
                    context = activity,
                    host = app.appWidgetHost,
                    workspaceController = controller,
                    widget = descriptor,
                    alreadyBoundId = consentedId,
                )

                assertEquals(emptyList<WidgetRestorer.Skipped>(), restore.skipped)
                assertEquals(1, restore.totalRestored)

                val restored = controller.current().items.single() as WorkspaceItem.WidgetIcon
                assertEquals(testProvider, restored.providerComponent)
                assertEquals(GridPosition(0, 2, 3), restored.position)
                assertEquals(2, restored.spanColumns)
                assertEquals(1, restored.spanRows)
                assertEquals(consentedId, restored.appWidgetId)
                // The fixture provider declares no configure Activity, so this
                // is a clean restore, not a "needs setting up again" one.
                assertEquals(1, restore.restored.size)
            }
        }
    }

    /**
     * A restore must never overwrite what is already on the grid. The
     * descriptor's cell is occupied, so placement has to fail cleanly.
     */
    @Test
    fun aDescriptorWithNoRoomLeftIsReportedInsteadOfCorruptingTheLayout() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val app = activity.application as ReliteHomeApplication
                val controller = app.workspaceController
                controller.replaceWorkspace(Workspace.empty())

                controller.addApp("io.relite.home/io.relite.home.ui.MainActivity", GridPosition(0, 0, 0))
                val before = controller.current().items.size

                val restore = WidgetRestorer.restoreOne(
                    context = activity,
                    host = app.appWidgetHost,
                    workspaceController = controller,
                    widget = PortableWidget(testProvider, GridPosition(0, 0, 0), 1, 1),
                    alreadyBoundId = app.appWidgetHost.allocateId(),
                )

                assertEquals(WidgetRestorer.SkipReason.NO_ROOM, restore.skipped.single().reason)
                assertEquals("the existing layout must be untouched", before, controller.current().items.size)
            }
        }
    }

    /**
     * The default state of any real install: no bind grant, so the silent
     * pass places nothing and must say so — and must offer the descriptor
     * back for the interactive consent queue rather than discarding it.
     */
    @Test
    fun withoutTheBindGrantTheSilentPassReportsConsentIsNeededInsteadOfFailingQuietly() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val app = activity.application as ReliteHomeApplication
                app.workspaceController.replaceWorkspace(Workspace.empty())

                val descriptor = PortableWidget(testProvider, GridPosition(0, 0, 0), 1, 1)
                val restore = WidgetRestorer.restoreAll(
                    context = activity,
                    host = app.appWidgetHost,
                    workspaceController = app.workspaceController,
                    widgets = listOf(descriptor),
                )

                assertEquals(0, restore.totalRestored)
                assertEquals(WidgetRestorer.SkipReason.BIND_NOT_PERMITTED, restore.skipped.single().reason)
                assertEquals(listOf(descriptor), restore.awaitingConsent)
                assertTrue(app.workspaceController.current().items.isEmpty())
            }
        }
    }

    @Test
    fun aDescriptorWhoseProviderIsNotInstalledIsReportedRatherThanSilentlyDropped() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val app = activity.application as ReliteHomeApplication
                app.workspaceController.replaceWorkspace(Workspace.empty())

                val restore = WidgetRestorer.restoreAll(
                    context = activity,
                    host = app.appWidgetHost,
                    workspaceController = app.workspaceController,
                    widgets = listOf(
                        PortableWidget("io.relite.nope/io.relite.nope.Provider", GridPosition(0, 0, 0), 1, 1),
                    ),
                )

                assertEquals(0, restore.totalRestored)
                assertEquals(
                    WidgetRestorer.SkipReason.PROVIDER_NOT_INSTALLED,
                    restore.skipped.single().reason,
                )
                assertTrue(app.workspaceController.current().items.isEmpty())
            }
        }
    }

    /** A mixed batch must report each descriptor's own outcome, not one blanket verdict. */
    @Test
    fun aMixedBatchReportsEachDescriptorsOwnOutcome() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val app = activity.application as ReliteHomeApplication
                app.workspaceController.replaceWorkspace(Workspace.empty())

                val restore = WidgetRestorer.restoreAll(
                    context = activity,
                    host = app.appWidgetHost,
                    workspaceController = app.workspaceController,
                    widgets = listOf(
                        PortableWidget(testProvider, GridPosition(0, 0, 0), 1, 1),
                        PortableWidget("io.relite.nope/io.relite.nope.Provider", GridPosition(0, 1, 0), 1, 1),
                    ),
                )

                assertEquals(
                    setOf(
                        WidgetRestorer.SkipReason.BIND_NOT_PERMITTED,
                        WidgetRestorer.SkipReason.PROVIDER_NOT_INSTALLED,
                    ),
                    restore.skipped.map { it.reason }.toSet(),
                )
                // Only the installed-but-unconsented one is worth re-asking about.
                assertEquals(listOf(testProvider), restore.awaitingConsent.map { it.providerComponent })
            }
        }
    }

    @Test
    fun aFreshlyAllocatedIdIsReleasedWhenPlacementFails() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val app = activity.application as ReliteHomeApplication
                val controller = app.workspaceController
                controller.replaceWorkspace(Workspace.empty())
                controller.addApp("io.relite.home/io.relite.home.ui.MainActivity", GridPosition(0, 0, 0))

                val consentedId = app.appWidgetHost.allocateId()
                WidgetRestorer.restoreOne(
                    context = activity,
                    host = app.appWidgetHost,
                    workspaceController = controller,
                    widget = PortableWidget(testProvider, GridPosition(0, 0, 0), 1, 1),
                    alreadyBoundId = consentedId,
                )

                // A failed placement must hand the id back rather than let the
                // host accumulate dead ids across a partial restore.
                assertTrue(
                    "the id from a failed placement should no longer be held by the host",
                    app.appWidgetHost.appWidgetIds.none { it == consentedId },
                )
            }
        }
    }

    @After
    fun tearDown() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val app = activity.application as ReliteHomeApplication
                val (_, removed) = app.workspaceController.replaceWorkspaceSafely(Workspace.empty())
                removed.forEach { app.appWidgetHost.removeWidget(it) }
            }
        }
    }
}
