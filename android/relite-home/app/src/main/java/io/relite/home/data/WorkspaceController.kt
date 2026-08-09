package io.relite.home.data

import java.util.UUID

/**
 * The single place that mutates a [Workspace]. Before this existed,
 * fragments manipulated persisted JSON structure directly wherever an edit
 * was needed; every mutation here instead validates against the grid,
 * persists atomically through [WorkspaceRepository], and only then commits
 * the new state to memory — pure Kotlin, so it's unit-testable without a
 * device (section 14 of the v0.2.0 plan).
 *
 * Every method that can fail (out-of-bounds position, occupied/overlapping
 * cells, operating on a nonexistent item, or a persistence failure) returns
 * `false`/`null` rather than throwing — callers (UI code) are expected to
 * check the result rather than rely on exceptions for normal "that didn't
 * work" outcomes.
 */
class WorkspaceController(
    private val repository: WorkspaceRepository,
    private val gridSpec: LauncherGridSpec,
) {

    private var workspace: Workspace = repository.load()

    fun current(): Workspace = workspace

    /** Re-reads persisted state, discarding any in-memory changes not yet saved. */
    fun reload() {
        workspace = repository.load()
    }

    // --- home screen items ---

    fun addApp(componentKey: String, position: GridPosition? = null): String? {
        val target = position ?: findFreeCell() ?: return null
        if (!isFree(GridRect.of(target, 1, 1))) return null
        val id = newId()
        return if (mutate { it.copy(items = it.items + WorkspaceItem.AppIcon(id, target, componentKey)) }) id else null
    }

    fun removeItem(itemId: String): Boolean =
        mutate { it.copy(items = it.items.filterNot { item -> item.id == itemId }) }

    fun moveItem(itemId: String, to: GridPosition): Boolean {
        val item = workspace.items.find { it.id == itemId } ?: return false
        val span = spanOf(item)
        if (!isFree(GridRect.of(to, span.first, span.second), excludeId = itemId)) return false
        return mutate { ws -> ws.copy(items = ws.items.map { if (it.id == itemId) withPosition(it, to) else it }) }
    }

    fun moveToPage(itemId: String, page: Int): Boolean {
        val item = workspace.items.find { it.id == itemId } ?: return false
        val span = spanOf(item)
        val target = firstFreeRectOnPage(page, span.first, span.second, excludeId = itemId) ?: return false
        return moveItem(item.id, GridPosition(page, target.column, target.row))
    }

    // --- pages ---

    fun addPage(): Int {
        val newIndex = workspace.pageCount
        return if (mutate { it.copy(pageCount = it.pageCount + 1) }) newIndex else -1
    }

    /** Removes `page` if it has no items, shifting later pages down by one. Page 0 can't be removed. */
    fun removeEmptyPage(page: Int): Boolean {
        if (page <= 0 || page >= workspace.pageCount) return false
        if (workspace.items.any { it.position.page == page }) return false
        return mutate { ws ->
            val shifted = ws.items.map { item ->
                if (item.position.page > page) {
                    withPosition(item, item.position.copy(page = item.position.page - 1))
                } else {
                    item
                }
            }
            ws.copy(pageCount = ws.pageCount - 1, items = shifted)
        }
    }

    // --- dock ---

    fun addToDock(componentKey: String): Boolean {
        if (workspace.dockComponentKeys.size >= gridSpec.dockCapacity) return false
        if (componentKey in workspace.dockComponentKeys) return false
        return mutate { it.copy(dockComponentKeys = it.dockComponentKeys + componentKey) }
    }

    fun removeFromDock(componentKey: String): Boolean =
        mutate { it.copy(dockComponentKeys = it.dockComponentKeys.filterNot { key -> key == componentKey }) }

    /** Replaces dock order/contents; rejects a set that adds or drops keys (use add/removeFromDock for that). */
    fun reorderDock(componentKeys: List<String>): Boolean {
        if (componentKeys.toSet() != workspace.dockComponentKeys.toSet()) return false
        if (componentKeys.size != workspace.dockComponentKeys.size) return false
        return mutate { it.copy(dockComponentKeys = componentKeys) }
    }

    // --- folders ---

    fun createFolder(label: String, memberComponentKeys: List<String>, position: GridPosition? = null): String? {
        val target = position ?: findFreeCell() ?: return null
        if (!isFree(GridRect.of(target, 1, 1))) return null
        val id = newId()
        val ok = mutate { it.copy(items = it.items + WorkspaceItem.FolderIcon(id, target, label, memberComponentKeys)) }
        return if (ok) id else null
    }

    fun renameFolder(folderId: String, newLabel: String): Boolean =
        updateFolder(folderId) { it.copy(label = newLabel) }

    fun addAppToFolder(folderId: String, componentKey: String): Boolean =
        updateFolder(folderId) { folder ->
            if (componentKey in folder.itemComponentKeys) return false
            folder.copy(itemComponentKeys = folder.itemComponentKeys + componentKey)
        }

    fun removeAppFromFolder(folderId: String, componentKey: String): Boolean =
        updateFolder(folderId) { it.copy(itemComponentKeys = it.itemComponentKeys.filterNot { k -> k == componentKey }) }

    fun deleteEmptyFolder(folderId: String): Boolean {
        val folder = findFolder(folderId) ?: return false
        if (folder.itemComponentKeys.isNotEmpty()) return false
        return removeItem(folderId)
    }

    // --- widgets ---

    fun addWidget(
        appWidgetId: Int,
        spanColumns: Int,
        spanRows: Int,
        providerComponent: String,
        position: GridPosition? = null,
    ): String? {
        val target = position
            ?: findFreeRect(spanColumns, spanRows)?.let { GridPosition(it.page, it.column, it.row) }
            ?: return null
        if (!isFree(GridRect.of(target, spanColumns, spanRows))) return null
        val id = newId()
        val ok = mutate {
            it.copy(
                items = it.items + WorkspaceItem.WidgetIcon(id, target, appWidgetId, spanColumns, spanRows, providerComponent),
            )
        }
        return if (ok) id else null
    }

    /** Validates the new rectangle (bounds + no overlap with any other item) before committing — section 20. */
    fun resizeWidget(itemId: String, spanColumns: Int, spanRows: Int): Boolean {
        val widget = workspace.items.filterIsInstance<WorkspaceItem.WidgetIcon>().find { it.id == itemId }
            ?: return false
        val candidateRect = GridRect.of(widget.position, spanColumns, spanRows)
        if (!isFree(candidateRect, excludeId = itemId)) return false
        return mutate { ws ->
            ws.copy(
                items = ws.items.map {
                    if (it.id == itemId) widget.copy(spanColumns = spanColumns, spanRows = spanRows) else it
                },
            )
        }
    }

    fun removeWidget(itemId: String): Boolean = removeItem(itemId)

    // --- package lifecycle (section 20, v0.2.0) ---

    /**
     * Removes every shortcut, dock entry, and folder membership referring
     * to `packageName` — called when [io.relite.home.data.AppRepository]
     * reports the package is gone. Widgets are left untouched (a widget's
     * lifecycle is host-managed, not tied 1:1 to a single package the way
     * a shortcut's componentKey is) and folders are kept even if they
     * become empty, since the user created them deliberately — an empty
     * folder isn't evidence of anything wrong, and re-creating an
     * intentional folder just because its last app was uninstalled would
     * be worse than leaving it for the user to delete themselves.
     */
    fun removeShortcutsForPackage(packageName: String): Boolean {
        val prefix = "$packageName/"
        return mutate { ws ->
            val items = ws.items.mapNotNull { item ->
                when (item) {
                    is WorkspaceItem.AppIcon ->
                        if (item.componentKey.startsWith(prefix)) null else item
                    is WorkspaceItem.FolderIcon ->
                        item.copy(itemComponentKeys = item.itemComponentKeys.filterNot { it.startsWith(prefix) })
                    is WorkspaceItem.WidgetIcon -> item
                }
            }
            val dock = ws.dockComponentKeys.filterNot { it.startsWith(prefix) }
            ws.copy(items = items, dockComponentKeys = dock)
        }
    }

    // --- internals ---

    private inline fun updateFolder(folderId: String, transform: (WorkspaceItem.FolderIcon) -> WorkspaceItem.FolderIcon): Boolean {
        val folder = findFolder(folderId) ?: return false
        val updated = transform(folder)
        return mutate { ws -> ws.copy(items = ws.items.map { if (it.id == folderId) updated else it }) }
    }

    private fun findFolder(folderId: String): WorkspaceItem.FolderIcon? =
        workspace.items.filterIsInstance<WorkspaceItem.FolderIcon>().find { it.id == folderId }

    /**
     * Section 24 (v0.4.0): builds the candidate state, persists it FIRST,
     * and only commits it to [workspace] once [WorkspaceRepository.save]
     * confirms success. A previous version did `workspace = transform(...)`
     * before saving — if the save then failed (or threw), in-memory state
     * had already diverged from disk with no way to detect it.
     */
    private fun mutate(transform: (Workspace) -> Workspace): Boolean {
        val candidate = transform(workspace)
        val ok = repository.save(candidate)
        if (ok) workspace = candidate
        return ok
    }

    private fun spanOf(item: WorkspaceItem): Pair<Int, Int> = when (item) {
        is WorkspaceItem.WidgetIcon -> item.spanColumns to item.spanRows
        is WorkspaceItem.AppIcon, is WorkspaceItem.FolderIcon -> 1 to 1
    }

    private fun isFree(rect: GridRect, excludeId: String? = null): Boolean {
        if (!rect.isWithinBounds(gridSpec, workspace.pageCount)) return false
        return workspace.items.none { it.id != excludeId && GridRect.of(it).intersects(rect) }
    }

    private fun findFreeCell(): GridPosition? {
        val rect = findFreeRect(1, 1) ?: return null
        return GridPosition(rect.page, rect.column, rect.row)
    }

    private fun findFreeRect(spanColumns: Int, spanRows: Int): GridRect? {
        for (page in 0 until workspace.pageCount) {
            firstFreeRectOnPage(page, spanColumns, spanRows)?.let { return it }
        }
        return null
    }

    /** First rectangle of size [spanColumns]x[spanRows] on [page] with no overlap — scans row-major, top-left first. */
    private fun firstFreeRectOnPage(page: Int, spanColumns: Int, spanRows: Int, excludeId: String? = null): GridRect? {
        if (page < 0 || page >= workspace.pageCount) return null
        for (row in 0 until gridSpec.rows) {
            for (column in 0 until gridSpec.columns) {
                val candidate = GridRect(page, column, row, spanColumns, spanRows)
                if (isFree(candidate, excludeId)) return candidate
            }
        }
        return null
    }

    private fun withPosition(item: WorkspaceItem, position: GridPosition): WorkspaceItem = when (item) {
        is WorkspaceItem.AppIcon -> item.copy(position = position)
        is WorkspaceItem.FolderIcon -> item.copy(position = position)
        is WorkspaceItem.WidgetIcon -> item.copy(position = position)
    }

    private fun newId(): String = UUID.randomUUID().toString()
}
