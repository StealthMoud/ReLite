package io.relite.home.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class WorkspaceControllerTest {

    private lateinit var repository: WorkspaceRepository
    private lateinit var controller: WorkspaceController

    @Before
    fun setUp() {
        repository = WorkspaceRepository(InMemoryStorage(), TEST_SPEC)
        controller = WorkspaceController(repository, TEST_SPEC)
    }

    // --- addApp / removeItem / moveItem ---

    @Test
    fun `addApp places the item in the first free cell and persists it`() {
        val id = controller.addApp("io.relite.camera/Main")
        assertNotNull(id)
        val item = controller.current().items.single()
        assertEquals(GridPosition(0, 0, 0), item.position)

        // persisted, not just in-memory
        val reloaded = WorkspaceController(repository, TEST_SPEC)
        assertEquals(1, reloaded.current().items.size)
    }

    @Test
    fun `addApp at an occupied position fails without mutating state`() {
        controller.addApp("io.relite.a/Main", GridPosition(0, 0, 0))
        val result = controller.addApp("io.relite.b/Main", GridPosition(0, 0, 0))
        assertNull(result)
        assertEquals(1, controller.current().items.size)
    }

    @Test
    fun `addApp at an out-of-bounds position fails`() {
        assertNull(controller.addApp("io.relite.a/Main", GridPosition(0, 99, 0)))
        assertNull(controller.addApp("io.relite.a/Main", GridPosition(0, 0, -1)))
        assertNull(controller.addApp("io.relite.a/Main", GridPosition(5, 0, 0)))
    }

    @Test
    fun `addApp auto-adds a page once every existing page is full`() {
        repeat(16) { i -> assertNotNull(controller.addApp("io.relite.app$i/Main")) }
        assertEquals(1, controller.current().pageCount)

        val overflowId = controller.addApp("io.relite.overflow/Main")

        assertNotNull(overflowId)
        assertEquals(2, controller.current().pageCount)
        assertEquals(1, controller.current().items.first { it.id == overflowId }.position.page)
    }

    @Test
    fun `removeItem drops the item and is a no-op for an unknown id`() {
        val id = controller.addApp("io.relite.a/Main")!!
        controller.removeItem(id)
        assertTrue(controller.current().items.isEmpty())

        controller.removeItem("does-not-exist") // must not throw
    }

    @Test
    fun `moveItem relocates to a free cell`() {
        val id = controller.addApp("io.relite.a/Main", GridPosition(0, 0, 0))!!
        val moved = controller.moveItem(id, GridPosition(0, 1, 1))
        assertTrue(moved)
        assertEquals(GridPosition(0, 1, 1), controller.current().items.single().position)
    }

    @Test
    fun `moveItem to an occupied cell fails and leaves both items in place`() {
        val a = controller.addApp("io.relite.a/Main", GridPosition(0, 0, 0))!!
        controller.addApp("io.relite.b/Main", GridPosition(0, 1, 0))

        val moved = controller.moveItem(a, GridPosition(0, 1, 0))

        assertFalse(moved)
        assertEquals(GridPosition(0, 0, 0), controller.current().items.first { it.id == a }.position)
    }

    @Test
    fun `moveItem for an unknown id fails`() {
        assertFalse(controller.moveItem("does-not-exist", GridPosition(0, 0, 0)))
    }

    // --- cross-page moveItem (sections 6-7, v0.4.0 hover drag) ---

    @Test
    fun `moveItem relocates an item from page 0 to page 1`() {
        controller.addPage()
        val id = controller.addApp("io.relite.a/Main", GridPosition(0, 0, 0))!!

        val moved = controller.moveItem(id, GridPosition(1, 2, 2))

        assertTrue(moved)
        assertEquals(GridPosition(1, 2, 2), controller.current().items.single().position)
    }

    @Test
    fun `moveItem relocates an item from page 1 back to page 0`() {
        controller.addPage()
        val id = controller.addApp("io.relite.a/Main", GridPosition(1, 0, 0))!!

        val moved = controller.moveItem(id, GridPosition(0, 3, 3))

        assertTrue(moved)
        assertEquals(GridPosition(0, 3, 3), controller.current().items.single().position)
    }

    @Test
    fun `moveItem to another page fails when the destination rectangle is occupied there, leaving the item on its original page`() {
        controller.addPage()
        controller.addApp("io.relite.blocker/Main", GridPosition(1, 0, 0))
        val id = controller.addApp("io.relite.a/Main", GridPosition(0, 0, 0))!!

        val moved = controller.moveItem(id, GridPosition(1, 0, 0))

        assertFalse(moved)
        assertEquals(GridPosition(0, 0, 0), controller.current().items.first { it.id == id }.position)
    }

    @Test
    fun `moveItem refuses a cross-page destination when the widget rectangle would not fit there`() {
        controller.addPage()
        val widgetId = "w1"
        val workspace = controller.current().copy(
            items = listOf(
                WorkspaceItem.WidgetIcon(
                    widgetId, GridPosition(0, 0, 0), appWidgetId = 1, spanColumns = 2, spanRows = 2,
                    providerComponent = "io.relite.widgets/Clock",
                ),
            ),
        )
        controller.replaceWorkspace(workspace)
        controller.addApp("io.relite.blocker/Main", GridPosition(1, 1, 1))

        // The widget's 2x2 rectangle at (0,0) on page 1 would overlap the blocker at (1,1).
        assertFalse(controller.moveItem(widgetId, GridPosition(1, 0, 0)))
        assertFalse(controller.canMoveTo(widgetId, GridPosition(1, 0, 0)))
    }

    @Test
    fun `canMoveTo cross-page reflects a free destination without mutating state`() {
        controller.addPage()
        val id = controller.addApp("io.relite.a/Main", GridPosition(0, 0, 0))!!

        assertTrue(controller.canMoveTo(id, GridPosition(1, 1, 1)))
        assertEquals(GridPosition(0, 0, 0), controller.current().items.single().position) // unchanged
    }

    // --- canMoveTo ---

    @Test
    fun `canMoveTo is true for a free cell and does not mutate state`() {
        val id = controller.addApp("io.relite.a/Main", GridPosition(0, 0, 0))!!
        assertTrue(controller.canMoveTo(id, GridPosition(0, 1, 1)))
        assertEquals(GridPosition(0, 0, 0), controller.current().items.single().position)
    }

    @Test
    fun `canMoveTo is false for a cell occupied by another item`() {
        val a = controller.addApp("io.relite.a/Main", GridPosition(0, 0, 0))!!
        controller.addApp("io.relite.b/Main", GridPosition(0, 1, 0))
        assertFalse(controller.canMoveTo(a, GridPosition(0, 1, 0)))
    }

    @Test
    fun `canMoveTo is true for an item's own current cell`() {
        val id = controller.addApp("io.relite.a/Main", GridPosition(0, 0, 0))!!
        assertTrue(controller.canMoveTo(id, GridPosition(0, 0, 0)))
    }

    @Test
    fun `canMoveTo is false for an out-of-bounds destination`() {
        val id = controller.addApp("io.relite.a/Main", GridPosition(0, 0, 0))!!
        assertFalse(controller.canMoveTo(id, GridPosition(0, TEST_SPEC.columns, 0)))
    }

    @Test
    fun `canMoveTo is false for an unknown id`() {
        assertFalse(controller.canMoveTo("does-not-exist", GridPosition(0, 0, 0)))
    }

    @Test
    fun `canMoveTo respects a widget's full rectangle, not just its anchor`() {
        val widgetId = controller.addWidget(
            appWidgetId = 1,
            spanColumns = 2,
            spanRows = 2,
            providerComponent = "io.relite.widget/Provider",
            position = GridPosition(0, 0, 0),
        )!!
        controller.addApp("io.relite.a/Main", GridPosition(0, 3, 0))
        // Anchor at (2,0) would be free, but the 2x2 rectangle from there overlaps nothing here —
        // move it so it *would* overlap the app at (3,0) to prove the full rect is checked.
        assertFalse(controller.canMoveTo(widgetId, GridPosition(0, 2, 0)))
        assertTrue(controller.canMoveTo(widgetId, GridPosition(0, 0, 1)))
    }

    // --- pages ---

    @Test
    fun `addPage increases pageCount and returns the new page index`() {
        assertEquals(1, controller.addPage())
        assertEquals(2, controller.current().pageCount)
    }

    @Test
    fun `removeEmptyPage shifts later items down and decrements pageCount`() {
        controller.addPage() // page 1
        controller.addPage() // page 2
        val onPage2 = controller.addApp("io.relite.a/Main", GridPosition(2, 0, 0))!!

        val removed = controller.removeEmptyPage(1)

        assertTrue(removed)
        assertEquals(2, controller.current().pageCount)
        assertEquals(1, controller.current().items.first { it.id == onPage2 }.position.page)
    }

    @Test
    fun `removeEmptyPage refuses a page that still has items`() {
        controller.addPage()
        controller.addApp("io.relite.a/Main", GridPosition(1, 0, 0))
        assertFalse(controller.removeEmptyPage(1))
    }

    @Test
    fun `removeEmptyPage refuses to remove page zero`() {
        assertFalse(controller.removeEmptyPage(0))
    }

    // --- dock ---

    @Test
    fun `addToDock appends up to capacity then refuses`() {
        repeat(5) { i -> assertTrue(controller.addToDock("io.relite.app$i/Main")) }
        assertFalse(controller.addToDock("io.relite.overflow/Main"))
        assertEquals(5, controller.current().dockComponentKeys.size)
    }

    @Test
    fun `addToDock refuses a duplicate component key`() {
        controller.addToDock("io.relite.a/Main")
        assertFalse(controller.addToDock("io.relite.a/Main"))
        assertEquals(1, controller.current().dockComponentKeys.size)
    }

    @Test
    fun `removeFromDock drops the key`() {
        controller.addToDock("io.relite.a/Main")
        controller.removeFromDock("io.relite.a/Main")
        assertTrue(controller.current().dockComponentKeys.isEmpty())
    }

    @Test
    fun `reorderDock accepts a permutation of the same keys`() {
        controller.addToDock("io.relite.a/Main")
        controller.addToDock("io.relite.b/Main")
        val ok = controller.reorderDock(listOf("io.relite.b/Main", "io.relite.a/Main"))
        assertTrue(ok)
        assertEquals(listOf("io.relite.b/Main", "io.relite.a/Main"), controller.current().dockComponentKeys)
    }

    @Test
    fun `reorderDock rejects a set that adds or drops keys`() {
        controller.addToDock("io.relite.a/Main")
        assertFalse(controller.reorderDock(listOf("io.relite.a/Main", "io.relite.b/Main")))
        assertFalse(controller.reorderDock(emptyList()))
        assertEquals(listOf("io.relite.a/Main"), controller.current().dockComponentKeys)
    }

    // --- folders ---

    @Test
    fun `createFolder then addAppToFolder and removeAppFromFolder`() {
        val folderId = controller.createFolder("Games", listOf("io.relite.chess/Main"))!!
        assertTrue(controller.addAppToFolder(folderId, "io.relite.sudoku/Main"))

        val folder = controller.current().items.single() as WorkspaceItem.FolderIcon
        assertEquals(listOf("io.relite.chess/Main", "io.relite.sudoku/Main"), folder.itemComponentKeys)

        assertTrue(controller.removeAppFromFolder(folderId, "io.relite.chess/Main"))
        val updated = controller.current().items.single() as WorkspaceItem.FolderIcon
        assertEquals(listOf("io.relite.sudoku/Main"), updated.itemComponentKeys)
    }

    @Test
    fun `addAppToFolder refuses a duplicate member`() {
        val folderId = controller.createFolder("Games", listOf("io.relite.chess/Main"))!!
        assertFalse(controller.addAppToFolder(folderId, "io.relite.chess/Main"))
    }

    @Test
    fun `addAppToFolder on an unknown folder fails`() {
        assertFalse(controller.addAppToFolder("no-such-folder", "io.relite.a/Main"))
    }

    @Test
    fun `reorderFolderMembers persists the new order`() {
        val folderId = controller.createFolder("Games", listOf("io.relite.a/Main", "io.relite.b/Main", "io.relite.c/Main"))!!
        val ok = controller.reorderFolderMembers(folderId, listOf("io.relite.c/Main", "io.relite.a/Main", "io.relite.b/Main"))
        assertTrue(ok)
        val folder = controller.current().items.single { it.id == folderId } as WorkspaceItem.FolderIcon
        assertEquals(listOf("io.relite.c/Main", "io.relite.a/Main", "io.relite.b/Main"), folder.itemComponentKeys)
    }

    @Test
    fun `reorderFolderMembers rejects a different member set`() {
        val folderId = controller.createFolder("Games", listOf("io.relite.a/Main", "io.relite.b/Main"))!!
        assertFalse(controller.reorderFolderMembers(folderId, listOf("io.relite.a/Main", "io.relite.z/Main")))
        assertFalse(controller.reorderFolderMembers(folderId, listOf("io.relite.a/Main")))
    }

    @Test
    fun `renameFolder updates the label`() {
        val folderId = controller.createFolder("Games", emptyList())!!
        assertTrue(controller.renameFolder(folderId, "Utilities"))
        assertEquals("Utilities", (controller.current().items.single() as WorkspaceItem.FolderIcon).label)
    }

    @Test
    fun `deleteEmptyFolder succeeds only when the folder has no members`() {
        val emptyFolder = controller.createFolder("Empty", emptyList())!!
        val fullFolder = controller.createFolder("Full", listOf("io.relite.a/Main"))!!

        assertFalse(controller.deleteEmptyFolder(fullFolder))
        assertTrue(controller.deleteEmptyFolder(emptyFolder))
        assertEquals(1, controller.current().items.size)
    }

    // --- widgets ---

    @Test
    fun `addWidget then resizeWidget then removeWidget`() {
        val id = controller.addWidget(
            appWidgetId = 42, spanColumns = 1, spanRows = 1, providerComponent = "io.relite.widgets/Clock",
        )!!
        assertTrue(controller.resizeWidget(id, spanColumns = 2, spanRows = 2))

        val widget = controller.current().items.single() as WorkspaceItem.WidgetIcon
        assertEquals(2, widget.spanColumns)
        assertEquals(2, widget.spanRows)

        controller.removeWidget(id)
        assertTrue(controller.current().items.isEmpty())
    }

    @Test
    fun `resizeWidget on an unknown id fails`() {
        assertFalse(controller.resizeWidget("no-such-widget", 2, 2))
    }

    // --- package removal cleanup (section 20) ---

    @Test
    fun `removeShortcutsForPackage drops matching home icons, dock entries, and folder members`() {
        controller.addApp("io.relite.dead/Main", GridPosition(0, 0, 0))
        controller.addApp("io.relite.alive/Main", GridPosition(0, 1, 0))
        controller.addToDock("io.relite.dead/Main")
        val folderId = controller.createFolder(
            "Mixed",
            listOf("io.relite.dead/Main", "io.relite.alive/Main"),
            GridPosition(0, 2, 0),
        )!!

        controller.removeShortcutsForPackage("io.relite.dead")

        val items = controller.current().items
        assertTrue(items.filterIsInstance<WorkspaceItem.AppIcon>().none { it.componentKey.startsWith("io.relite.dead/") })
        assertTrue(items.filterIsInstance<WorkspaceItem.AppIcon>().any { it.componentKey == "io.relite.alive/Main" })
        assertFalse("io.relite.dead/Main" in controller.current().dockComponentKeys)

        val folder = items.single { it.id == folderId } as WorkspaceItem.FolderIcon
        assertEquals(listOf("io.relite.alive/Main"), folder.itemComponentKeys)
    }

    @Test
    fun `removeShortcutsForPackage does not remove an unrelated package with a similar prefix`() {
        // "io.relite.app" must not incorrectly match "io.relite.app2" —
        // the match is on the exact "packageName/" prefix, not a raw substring.
        controller.addApp("io.relite.app2/Main", GridPosition(0, 0, 0))
        controller.removeShortcutsForPackage("io.relite.app")
        assertEquals(1, controller.current().items.size)
    }

    @Test
    fun `removeShortcutsForPackage keeps widgets untouched`() {
        controller.addWidget(appWidgetId = 1, spanColumns = 1, spanRows = 1, providerComponent = "io.relite.widgets/Clock")
        controller.removeShortcutsForPackage("io.relite.anything")
        assertEquals(1, controller.current().items.size)
    }

    // --- exact component reconciliation (section 20/21, v0.4.1) ---

    @Test
    fun `removeStaleComponents drops a renamed activity but keeps the package's new activity`() {
        controller.addApp("io.relite.app/OldActivity", GridPosition(0, 0, 0))
        controller.addToDock("io.relite.app/OldActivity")

        // Package is still installed, just under a new activity name — the
        // old shortcut is now dead, but the package itself is fine.
        controller.removeStaleComponents(setOf("io.relite.app/NewActivity"))

        assertTrue(controller.current().items.isEmpty())
        assertTrue(controller.current().dockComponentKeys.isEmpty())
    }

    @Test
    fun `removeStaleComponents keeps a component still in the launchable set`() {
        controller.addApp("io.relite.alive/Main", GridPosition(0, 0, 0))
        controller.removeStaleComponents(setOf("io.relite.alive/Main"))
        assertEquals(1, controller.current().items.size)
    }

    @Test
    fun `removeStaleComponents keeps widgets untouched`() {
        controller.addWidget(appWidgetId = 1, spanColumns = 1, spanRows = 1, providerComponent = "io.relite.widgets/Clock")
        controller.removeStaleComponents(emptySet())
        assertEquals(1, controller.current().items.size)
    }

    @Test
    fun `removeWidgetsForMissingProviders removes widgets whose provider package is gone`() {
        controller.addWidget(appWidgetId = 7, spanColumns = 1, spanRows = 1, providerComponent = "io.relite.widgets/Clock")
        val removed = controller.removeWidgetsForMissingProviders(emptySet())
        assertEquals(setOf(7), removed)
        assertTrue(controller.current().items.isEmpty())
    }

    @Test
    fun `removeWidgetsForMissingProviders leaves widgets whose provider is still available`() {
        controller.addWidget(appWidgetId = 7, spanColumns = 1, spanRows = 1, providerComponent = "io.relite.widgets/Clock")
        val removed = controller.removeWidgetsForMissingProviders(setOf("io.relite.widgets/Clock"))
        assertTrue(removed.isEmpty())
        assertEquals(1, controller.current().items.size)
    }

    @Test
    fun `removeWidgetsForMissingProviders is exact-component, not package-only`() {
        // Section 8/9 (v0.5.0): the available set names a sibling provider
        // in the same package ("Weather", not "Clock") — a package-only
        // comparison would have wrongly kept the Clock widget since its
        // package is still present; exact-component comparison correctly
        // treats Clock as unavailable and removes it.
        controller.addWidget(appWidgetId = 7, spanColumns = 1, spanRows = 1, providerComponent = "io.relite.widgets/Clock")
        val removed = controller.removeWidgetsForMissingProviders(setOf("io.relite.widgets/Weather"))
        assertEquals(setOf(7), removed)
        assertTrue(controller.current().items.isEmpty())
    }

    // --- reload ---

    @Test
    fun `reload discards unsaved in-memory state and re-reads from storage`() {
        controller.addApp("io.relite.a/Main")
        val savedState = repository.load()
        assertEquals(1, savedState.items.size) // addApp already persisted via mutate()

        controller.reload()
        assertEquals(1, controller.current().items.size)
    }

    // --- rectangle-based widget geometry (section 20) ---

    @Test
    fun `addWidget occupies a rectangle and blocks a 1x1 icon anywhere inside it`() {
        val widgetId = controller.addWidget(
            appWidgetId = 1, spanColumns = 2, spanRows = 2, providerComponent = "io.relite.widgets/Clock",
            position = GridPosition(0, 0, 0),
        )
        assertNotNull(widgetId)

        // every one of the 4 cells the 2x2 widget covers must be blocked
        assertNull(controller.addApp("io.relite.a/Main", GridPosition(0, 0, 0)))
        assertNull(controller.addApp("io.relite.a/Main", GridPosition(0, 1, 0)))
        assertNull(controller.addApp("io.relite.a/Main", GridPosition(0, 0, 1)))
        assertNull(controller.addApp("io.relite.a/Main", GridPosition(0, 1, 1)))
        // just outside the rectangle is fine
        assertNotNull(controller.addApp("io.relite.a/Main", GridPosition(0, 2, 0)))
    }

    @Test
    fun `addWidget refuses a rectangle that overlaps an existing widget`() {
        controller.addWidget(
            appWidgetId = 1, spanColumns = 2, spanRows = 2, providerComponent = "io.relite.widgets/Clock",
            position = GridPosition(0, 0, 0),
        )
        val overlapping = controller.addWidget(
            appWidgetId = 2, spanColumns = 2, spanRows = 2, providerComponent = "io.relite.widgets/Clock",
            position = GridPosition(0, 1, 1),
        )
        assertNull(overlapping)
    }

    @Test
    fun `addWidget refuses a rectangle that runs past the right or bottom edge`() {
        assertNull(
            controller.addWidget(
                appWidgetId = 1, spanColumns = 3, spanRows = 1, providerComponent = "io.relite.widgets/Clock",
                position = GridPosition(0, 2, 0), // TEST_SPEC has 4 columns: column 2 + span 3 runs off the edge
            ),
        )
        assertNull(
            controller.addWidget(
                appWidgetId = 1, spanColumns = 1, spanRows = 3, providerComponent = "io.relite.widgets/Clock",
                position = GridPosition(0, 0, 2), // TEST_SPEC has 4 rows: row 2 + span 3 runs off the edge
            ),
        )
    }

    @Test
    fun `addWidget refuses a zero or negative span`() {
        assertNull(controller.addWidget(appWidgetId = 1, spanColumns = 0, spanRows = 1, providerComponent = "a/B"))
        assertNull(controller.addWidget(appWidgetId = 1, spanColumns = 1, spanRows = -1, providerComponent = "a/B"))
    }

    @Test
    fun `resizeWidget refuses growing into an occupied cell`() {
        val widgetId = controller.addWidget(
            appWidgetId = 1, spanColumns = 1, spanRows = 1, providerComponent = "io.relite.widgets/Clock",
            position = GridPosition(0, 0, 0),
        )!!
        controller.addApp("io.relite.blocker/Main", GridPosition(0, 1, 0))

        assertFalse(controller.resizeWidget(widgetId, spanColumns = 2, spanRows = 1))
        val widget = controller.current().items.single { it.id == widgetId } as WorkspaceItem.WidgetIcon
        assertEquals(1, widget.spanColumns)
    }

    @Test
    fun `moveItem refuses a destination whose rectangle overlaps another item`() {
        val widgetId = controller.addWidget(
            appWidgetId = 1, spanColumns = 2, spanRows = 2, providerComponent = "io.relite.widgets/Clock",
            position = GridPosition(0, 0, 0),
        )!!
        controller.addApp("io.relite.other/Main", GridPosition(0, 3, 0))

        // moving the 2x2 widget to (2,0) would span columns 2-3, overlapping io.relite.other at (3,0)
        assertFalse(controller.moveItem(widgetId, GridPosition(0, 2, 0)))
        val widget = controller.current().items.single { it.id == widgetId }
        assertEquals(GridPosition(0, 0, 0), widget.position)
    }

    // --- persistence-failure safety (section 24) ---

    @Test
    fun `a failed save leaves in-memory state unchanged`() {
        val storage = InMemoryStorage()
        val repo = WorkspaceRepository(storage, TEST_SPEC)
        val ctrl = WorkspaceController(repo, TEST_SPEC)
        storage.failWrites = true

        val id = ctrl.addApp("io.relite.a/Main")

        assertNull(id)
        assertTrue(ctrl.current().items.isEmpty())
        assertNull(storage.read()) // nothing was ever durably written either
    }

    @Test
    fun `a failed save while auto-creating a page leaves pageCount unchanged`() {
        val storage = InMemoryStorage()
        val repo = WorkspaceRepository(storage, TEST_SPEC)
        val ctrl = WorkspaceController(repo, TEST_SPEC)
        // Fill every cell of the only page so the next addApp must auto-create a page.
        for (row in 0 until TEST_SPEC.rows) {
            for (column in 0 until TEST_SPEC.columns) {
                assertNotNull(ctrl.addApp("io.relite.filler.$row.$column/Main", GridPosition(0, column, row)))
            }
        }
        assertEquals(1, ctrl.current().pageCount)

        storage.failWrites = true
        val id = ctrl.addApp("io.relite.overflow/Main")

        assertNull(id)
        // Section 33: previously this left pageCount == 2 with no item on it.
        assertEquals(1, ctrl.current().pageCount)
    }

    @Test
    fun `a failed save while auto-creating a page for a widget leaves pageCount unchanged`() {
        val storage = InMemoryStorage()
        val repo = WorkspaceRepository(storage, TEST_SPEC)
        val ctrl = WorkspaceController(repo, TEST_SPEC)
        for (row in 0 until TEST_SPEC.rows) {
            for (column in 0 until TEST_SPEC.columns) {
                assertNotNull(ctrl.addApp("io.relite.filler.$row.$column/Main", GridPosition(0, column, row)))
            }
        }

        storage.failWrites = true
        val id = ctrl.addWidget(appWidgetId = 1, spanColumns = 1, spanRows = 1, providerComponent = "io.relite.widgets/Clock")

        assertNull(id)
        assertEquals(1, ctrl.current().pageCount)
    }

    // --- moveToNewPage (section 35) ---

    @Test
    fun `moveToNewPage adds a page and moves the item there in one transaction`() {
        val id = controller.addApp("io.relite.a/Main", GridPosition(0, 0, 0))!!
        assertTrue(controller.moveToNewPage(id))

        assertEquals(2, controller.current().pageCount)
        val item = controller.current().items.single { it.id == id }
        assertEquals(GridPosition(1, 0, 0), item.position)
    }

    @Test
    fun `a failed save during moveToNewPage leaves pageCount and position unchanged`() {
        val storage = InMemoryStorage()
        val repo = WorkspaceRepository(storage, TEST_SPEC)
        val ctrl = WorkspaceController(repo, TEST_SPEC)
        val id = ctrl.addApp("io.relite.a/Main", GridPosition(0, 0, 0))!!

        storage.failWrites = true
        assertFalse(ctrl.moveToNewPage(id))

        assertEquals(1, ctrl.current().pageCount)
        assertEquals(GridPosition(0, 0, 0), ctrl.current().items.single().position)
    }

    private companion object {
        val TEST_SPEC = LauncherGridSpec(columns = 4, rows = 4, dockCapacity = 5)
    }
}
