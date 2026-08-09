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
        repository = WorkspaceRepository(InMemoryStorage())
        controller = WorkspaceController(repository, gridColumns = 4, gridRows = 4, dockCapacity = 5)
    }

    // --- addApp / removeItem / moveItem ---

    @Test
    fun `addApp places the item in the first free cell and persists it`() {
        val id = controller.addApp("io.relite.camera/Main")
        assertNotNull(id)
        val item = controller.current().items.single()
        assertEquals(GridPosition(0, 0, 0), item.position)

        // persisted, not just in-memory
        val reloaded = WorkspaceController(repository, 4, 4, 5)
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
    fun `addApp fails once every cell on every page is full`() {
        repeat(16) { i -> assertNotNull(controller.addApp("io.relite.app$i/Main")) }
        assertNull(controller.addApp("io.relite.overflow/Main"))
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
        val id = controller.addWidget(appWidgetId = 42, spanColumns = 1, spanRows = 1)!!
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
        controller.addWidget(appWidgetId = 1, spanColumns = 1, spanRows = 1)
        controller.removeShortcutsForPackage("io.relite.anything")
        assertEquals(1, controller.current().items.size)
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
}
