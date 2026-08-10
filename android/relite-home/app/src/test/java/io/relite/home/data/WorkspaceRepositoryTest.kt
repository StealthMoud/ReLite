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
                WorkspaceItem.WidgetIcon(
                    "w1", GridPosition(1, 0, 0), appWidgetId = 42, spanColumns = 2, spanRows = 1,
                    providerComponent = "io.relite.widgets/Clock",
                ),
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
        override fun write(content: String): Boolean {
            this.content = content
            return true
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
    fun `schema 1 data migrates in place with an empty providerComponent for widgets`() {
        // Section 22: a pre-v0.4.0 layout file (no providerComponent field
        // on widget entries) must still load, not be rejected outright.
        val v1Json = """
            {"schema": 1, "pageCount": 1, "dock": [],
             "items": [{"id": "w1", "position": {"page": 0, "column": 0, "row": 0},
                        "type": "widget", "appWidgetId": 7, "spanColumns": 1, "spanRows": 1}]}
        """.trimIndent()
        val repo = WorkspaceRepository(InMemoryStorage(v1Json))
        val widget = repo.load().items.single() as WorkspaceItem.WidgetIcon
        assertEquals(7, widget.appWidgetId)
        assertEquals("", widget.providerComponent)
    }

    @Test
    fun `overlapping items fail validation and preserve the corrupt file instead of silently emptying it`() {
        // Section 23/25: two items occupying the same cell must never be
        // trusted just because the JSON parsed and the schema is known.
        val overlapping = """
            {"schema": 2, "pageCount": 1, "dock": [],
             "items": [
               {"id": "a1", "position": {"page": 0, "column": 0, "row": 0}, "type": "app", "componentKey": "io.relite.a/Main"},
               {"id": "a2", "position": {"page": 0, "column": 0, "row": 0}, "type": "app", "componentKey": "io.relite.b/Main"}
             ]}
        """.trimIndent()
        val backingUpStorage = BackupTrackingStorage(overlapping)
        val repo = WorkspaceRepository(backingUpStorage)

        val workspace = repo.load()

        assertTrue(workspace.items.isEmpty())
        assertEquals(listOf(overlapping), backingUpStorage.backedUp)
        assertTrue(repo.lastLoadIssue!!.contains("overlap"))
    }

    @Test
    fun `an out-of-bounds item fails validation`() {
        val outOfBounds = """
            {"schema": 2, "pageCount": 1, "dock": [],
             "items": [{"id": "a1", "position": {"page": 0, "column": 99, "row": 0}, "type": "app", "componentKey": "io.relite.a/Main"}]}
        """.trimIndent()
        val repo = WorkspaceRepository(InMemoryStorage(outOfBounds))
        assertTrue(repo.load().items.isEmpty())
        assertTrue(repo.lastLoadIssue!!.contains("out of bounds"))
    }

    @Test
    fun `dock exceeding capacity fails validation`() {
        val tooManyDockEntries = """
            {"schema": 2, "pageCount": 1,
             "dock": ["a/A", "b/B", "c/C", "d/D", "e/E", "f/F"], "items": []}
        """.trimIndent()
        val repo = WorkspaceRepository(InMemoryStorage(tooManyDockEntries), dockCapacity = 5)
        assertTrue(repo.load().dockComponentKeys.isEmpty())
        assertTrue(repo.lastLoadIssue!!.contains("capacity"))
    }

    @Test
    fun `lastLoadIssue is null after a clean load`() {
        val repo = WorkspaceRepository(InMemoryStorage())
        repo.load()
        assertEquals(null, repo.lastLoadIssue)
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

    // --- portable layout export/import (sections 60-66, 111-112) ---

    @Test
    fun `exportPortable then importPortable round-trips apps, folders, and dock but drops widgets`() {
        val repo = WorkspaceRepository(InMemoryStorage())
        val workspace = Workspace(
            pageCount = 1,
            items = listOf(
                WorkspaceItem.AppIcon("a1", GridPosition(0, 0, 0), "io.relite.camera/Main"),
                WorkspaceItem.FolderIcon("f1", GridPosition(0, 1, 0), "Utilities", listOf("io.relite.calc/Main")),
                WorkspaceItem.WidgetIcon(
                    "w1", GridPosition(0, 2, 0), appWidgetId = 42, spanColumns = 1, spanRows = 1,
                    providerComponent = "io.relite.widgets/Clock",
                ),
            ),
            dockComponentKeys = listOf("io.relite.phone/Main"),
        )
        val installed = setOf("io.relite.camera/Main", "io.relite.calc/Main", "io.relite.phone/Main")

        val exported = repo.exportPortable(workspace)
        val result = repo.importPortable(exported, installed) as WorkspaceRepository.ImportResult.Success

        assertTrue(result.missingApps.isEmpty())
        assertEquals(2, result.candidate.items.size) // widget dropped
        assertTrue(result.candidate.items.none { it is WorkspaceItem.WidgetIcon })
        assertEquals(listOf("io.relite.phone/Main"), result.candidate.dockComponentKeys)
    }

    @Test
    fun `importPortable reports apps not currently installed instead of keeping a dead shortcut`() {
        val repo = WorkspaceRepository(InMemoryStorage())
        val workspace = Workspace(
            pageCount = 1,
            items = listOf(
                WorkspaceItem.AppIcon("a1", GridPosition(0, 0, 0), "io.relite.gone/Main"),
                WorkspaceItem.FolderIcon(
                    "f1", GridPosition(0, 1, 0), "Mixed",
                    listOf("io.relite.gone2/Main", "io.relite.here/Main"),
                ),
            ),
            dockComponentKeys = listOf("io.relite.gone3/Main"),
        )
        val exported = repo.exportPortable(workspace)

        val result = repo.importPortable(exported, setOf("io.relite.here/Main")) as WorkspaceRepository.ImportResult.Success

        assertEquals(
            setOf("io.relite.gone/Main", "io.relite.gone2/Main", "io.relite.gone3/Main"),
            result.missingApps.toSet(),
        )
        assertTrue(result.candidate.items.none { it is WorkspaceItem.AppIcon }) // the only AppIcon was missing
        val folder = result.candidate.items.single { it is WorkspaceItem.FolderIcon } as WorkspaceItem.FolderIcon
        assertEquals(listOf("io.relite.here/Main"), folder.itemComponentKeys)
        assertTrue(result.candidate.dockComponentKeys.isEmpty())
    }

    @Test
    fun `importPortable rejects malformed json instead of throwing`() {
        val repo = WorkspaceRepository(InMemoryStorage())
        val result = repo.importPortable("{not valid json", emptySet())
        assertTrue(result is WorkspaceRepository.ImportResult.Failure)
    }

    @Test
    fun `importPortable rejects a structurally invalid layout (overlapping items)`() {
        val repo = WorkspaceRepository(InMemoryStorage())
        val overlapping = """
            {"schema": 2, "pageCount": 1, "dock": [], "items": [
                {"id": "a", "type": "app", "position": {"page":0,"column":0,"row":0}, "componentKey": "io.relite.a/Main"},
                {"id": "b", "type": "app", "position": {"page":0,"column":0,"row":0}, "componentKey": "io.relite.b/Main"}
            ]}
        """.trimIndent()

        val result = repo.importPortable(overlapping, setOf("io.relite.a/Main", "io.relite.b/Main"))

        assertTrue(result is WorkspaceRepository.ImportResult.Failure)
    }

    @Test
    fun `a failed import does not mutate the storage backing the repository`() {
        val storage = InMemoryStorage()
        val repo = WorkspaceRepository(storage)
        repo.save(Workspace(1, listOf(WorkspaceItem.AppIcon("a1", GridPosition(0, 0, 0), "io.relite.a/Main")), emptyList()))

        repo.importPortable("{not valid json", emptySet())

        assertEquals(1, repo.load().items.size) // untouched — importPortable never writes to storage itself
    }

    @Test
    fun `save refuses to persist a structurally invalid candidate`() {
        // Section 26 (v0.5.0): validate at the repository boundary itself,
        // not only inside WorkspaceController.mutate — a candidate with two
        // overlapping items must never reach disk regardless of caller.
        val storage = InMemoryStorage()
        val repo = WorkspaceRepository(storage)
        val overlapping = Workspace(
            pageCount = 1,
            items = listOf(
                WorkspaceItem.AppIcon("a1", GridPosition(0, 0, 0), "io.relite.a/Main"),
                WorkspaceItem.AppIcon("a2", GridPosition(0, 0, 0), "io.relite.b/Main"),
            ),
            dockComponentKeys = emptyList(),
        )

        val ok = repo.save(overlapping)

        assertTrue(!ok)
        assertEquals(0, repo.load().items.size) // storage was never written to
    }

    // --- schema v3: homeGrid (sections 55-56, v0.5.0) ---

    @Test
    fun `homeGrid round-trips through save and load`() {
        val storage = InMemoryStorage()
        val repo = WorkspaceRepository(storage)
        repo.save(Workspace.empty().copy(homeGrid = HomeGridPreset.FIVE_BY_SIX))

        assertEquals(HomeGridPreset.FIVE_BY_SIX, repo.load().homeGrid)
    }

    @Test
    fun `a schema-2 file without homeGrid defaults to FOUR_BY_SIX on load`() {
        val v2Json = """{"schema": 2, "pageCount": 1, "dock": [], "items": []}"""
        val repo = WorkspaceRepository(InMemoryStorage(v2Json))

        assertEquals(HomeGridPreset.FOUR_BY_SIX, repo.load().homeGrid)
    }

    // --- defaultPage (sections 80/119, v0.5.0) ---

    @Test
    fun `defaultPage round-trips through save and load`() {
        val storage = InMemoryStorage()
        val repo = WorkspaceRepository(storage)
        repo.save(Workspace.empty(pageCount = 3).copy(defaultPage = 2))

        assertEquals(2, repo.load().defaultPage)
    }

    @Test
    fun `a stored defaultPage beyond the current pageCount is clamped on load`() {
        val json = """{"schema": 3, "pageCount": 2, "dock": [], "items": [], "defaultPage": 99}"""
        val repo = WorkspaceRepository(InMemoryStorage(json))

        assertEquals(1, repo.load().defaultPage)
    }
}
