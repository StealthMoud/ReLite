package io.relite.home.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceRepositoryTest {

    @Test
    fun `load with no prior data returns an empty single-page workspace`() {
        val repo = WorkspaceRepository(InMemoryStorage())
        val workspace = repo.load()
        assertEquals(1, workspace.pageCount)
        assertTrue(workspace.items.isEmpty())
        assertTrue(workspace.dockComponentKeys.isEmpty())
    }

    @Test
    fun `save then load round-trips app icons, folders, widgets, and dock`() {
        val storage = InMemoryStorage()
        val repo = WorkspaceRepository(storage)

        val workspace = Workspace(
            pageCount = 2,
            items = listOf(
                WorkspaceItem.AppIcon("a1", GridPosition(0, 0, 0), "io.relite.camera/Main"),
                WorkspaceItem.FolderIcon(
                    "f1", GridPosition(0, 1, 0), "Utilities",
                    listOf("io.relite.calc/Main", "io.relite.notes/Main"),
                ),
                WorkspaceItem.WidgetIcon("w1", GridPosition(1, 0, 0), appWidgetId = 42, spanColumns = 2, spanRows = 1),
            ),
            dockComponentKeys = listOf("io.relite.phone/Main", "io.relite.messages/Main"),
        )

        repo.save(workspace)
        val loaded = repo.load()

        assertEquals(workspace, loaded)
    }

    @Test
    fun `corrupt stored data fails safe to an empty workspace`() {
        val storage = InMemoryStorage("{not valid json")
        val repo = WorkspaceRepository(storage)
        val workspace = repo.load()
        assertTrue(workspace.items.isEmpty())
    }

    @Test
    fun `corrupt data is preserved via backupCorrupt before falling back to empty`() {
        // Section 50: a corrupt file must not just silently become an
        // empty workspace with no trace of what was there before.
        val backingUpStorage = BackupTrackingStorage("{not valid json")
        WorkspaceRepository(backingUpStorage).load()
        assertEquals(listOf("{not valid json"), backingUpStorage.backedUp)
    }

    @Test
    fun `well-formed data does not trigger a corruption backup`() {
        val storage = InMemoryStorage()
        val repo = WorkspaceRepository(storage)
        repo.save(Workspace.empty())
        val backingUpStorage = BackupTrackingStorage(storage.read())
        loadWith(backingUpStorage)
        assertTrue(backingUpStorage.backedUp.isEmpty())
    }

    private fun loadWith(storage: Storage) {
        WorkspaceRepository(storage).load()
    }

    private class BackupTrackingStorage(private var content: String?) : Storage {
        val backedUp = mutableListOf<String>()
        override fun read(): String? = content
        override fun write(content: String) {
            this.content = content
        }
        override fun backupCorrupt(content: String) {
            backedUp.add(content)
        }
    }

    @Test
    fun `unsupported schema version fails safe to an empty workspace`() {
        val storage = InMemoryStorage("""{"schema": 999, "pageCount": 1, "items": [], "dock": []}""")
        val repo = WorkspaceRepository(storage)
        val workspace = repo.load()
        assertTrue(workspace.items.isEmpty())
    }

    @Test
    fun `folder persists its member component keys in order`() {
        val storage = InMemoryStorage()
        val repo = WorkspaceRepository(storage)
        val folder = WorkspaceItem.FolderIcon(
            "f1", GridPosition(0, 0, 0), "Games",
            listOf("io.relite.chess/Main", "io.relite.sudoku/Main"),
        )
        repo.save(Workspace(1, listOf(folder), emptyList()))

        val loaded = repo.load().items.single() as WorkspaceItem.FolderIcon
        assertEquals(listOf("io.relite.chess/Main", "io.relite.sudoku/Main"), loaded.itemComponentKeys)
    }
}
