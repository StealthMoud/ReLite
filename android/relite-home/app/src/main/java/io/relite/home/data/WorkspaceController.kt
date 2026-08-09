package io.relite.home.data

import java.util.UUID

/**
 * The single place that mutates a [Workspace]. Before this existed,
 * fragments manipulated persisted JSON structure directly wherever an edit
 * was needed; every mutation here instead validates against the grid,
 * updates in-memory state, and persists atomically through
 * [WorkspaceRepository] — pure Kotlin, so it's unit-testable without a
 * device (section 14 of the v0.2.0 plan).
 *
 * Every method that can fail (out-of-bounds position, occupied cell,
 * operating on a nonexistent item) returns `false`/`null` rather than
 * throwing — callers (UI code) are expected to check the result rather
 * than rely on exceptions for normal "that didn't work" outcomes.
 */
class WorkspaceController(
    private val repository: WorkspaceRepository,
    private val gridColumns: Int,
    private val gridRows: Int,
    private val dockCapacity: Int,
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
        if (!isFree(target)) return null
        val id = newId()
        mutate { it.copy(items = it.items + WorkspaceItem.AppIcon(id, target, componentKey)) }
        return id
    }

    fun removeItem(itemId: String) {
        mutate { it.copy(items = it.items.filterNot { item -> item.id == itemId }) }
    }

    fun moveItem(itemId: String, to: GridPosition): Boolean {
        if (workspace.items.none { it.id == itemId }) return false
        if (!isFree(to, excludeId = itemId)) return false
        mutate { ws -> ws.copy(items = ws.items.map { if (it.id == itemId) withPosition(it, to) else it }) }
        return true
    }

    fun moveToPage(itemId: String, page: Int): Boolean {
        val item = workspace.items.find { it.id == itemId } ?: return false
        val target = firstFreeCellOnPage(page, excludeId = itemId) ?: return false
        return moveItem(item.id, target)
    }

    // --- pages ---

    fun addPage(): Int {
        mutate { it.copy(pageCount = it.pageCount + 1) }
        return workspace.pageCount - 1
    }

    /** Removes `page` if it has no items, shifting later pages down by one. Page 0 can't be removed. */
    fun removeEmptyPage(page: Int): Boolean {
        if (page <= 0 || page >= workspace.pageCount) return false
        if (workspace.items.any { it.position.page == page }) return false
        mutate { ws ->
            val shifted = ws.items.map { item ->
                if (item.position.page > page) {
                    withPosition(item, item.position.copy(page = item.position.page - 1))
                } else {
                    item
                }
            }
            ws.copy(pageCount = ws.pageCount - 1, items = shifted)
        }
        return true
    }

    // --- dock ---

    fun addToDock(componentKey: String): Boolean {
        if (workspace.dockComponentKeys.size >= dockCapacity) return false
        if (componentKey in workspace.dockComponentKeys) return false
        mutate { it.copy(dockComponentKeys = it.dockComponentKeys + componentKey) }
        return true
    }

    fun removeFromDock(componentKey: String) {
        mutate { it.copy(dockComponentKeys = it.dockComponentKeys.filterNot { key -> key == componentKey }) }
    }

    /** Replaces dock order/contents; rejects a set that adds or drops keys (use add/removeFromDock for that). */
    fun reorderDock(componentKeys: List<String>): Boolean {
        if (componentKeys.toSet() != workspace.dockComponentKeys.toSet()) return false
        if (componentKeys.size != workspace.dockComponentKeys.size) return false
        mutate { it.copy(dockComponentKeys = componentKeys) }
        return true
    }

    // --- folders ---

    fun createFolder(label: String, memberComponentKeys: List<String>, position: GridPosition? = null): String? {
        val target = position ?: findFreeCell() ?: return null
        if (!isFree(target)) return null
        val id = newId()
        mutate { it.copy(items = it.items + WorkspaceItem.FolderIcon(id, target, label, memberComponentKeys)) }
        return id
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
        removeItem(folderId)
        return true
    }

    // --- widgets ---

    fun addWidget(appWidgetId: Int, spanColumns: Int, spanRows: Int, position: GridPosition? = null): String? {
        val target = position ?: findFreeCell() ?: return null
        if (!isFree(target)) return null
        val id = newId()
        mutate { it.copy(items = it.items + WorkspaceItem.WidgetIcon(id, target, appWidgetId, spanColumns, spanRows)) }
        return id
    }

    fun resizeWidget(itemId: String, spanColumns: Int, spanRows: Int): Boolean {
        val widget = workspace.items.filterIsInstance<WorkspaceItem.WidgetIcon>().find { it.id == itemId }
            ?: return false
        mutate { ws ->
            ws.copy(
                items = ws.items.map {
                    if (it.id == itemId) widget.copy(spanColumns = spanColumns, spanRows = spanRows) else it
                },
            )
        }
        return true
    }

    fun removeWidget(itemId: String) = removeItem(itemId)

    // --- package lifecycle (section 20) ---

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
    fun removeShortcutsForPackage(packageName: String) {
        val prefix = "$packageName/"
        mutate { ws ->
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
        mutate { ws -> ws.copy(items = ws.items.map { if (it.id == folderId) updated else it }) }
        return true
    }

    private fun findFolder(folderId: String): WorkspaceItem.FolderIcon? =
        workspace.items.filterIsInstance<WorkspaceItem.FolderIcon>().find { it.id == folderId }

    private fun mutate(transform: (Workspace) -> Workspace) {
        workspace = transform(workspace)
        repository.save(workspace)
    }

    private fun isFree(position: GridPosition, excludeId: String? = null): Boolean {
        if (position.page < 0 || position.page >= workspace.pageCount) return false
        if (position.column < 0 || position.column >= gridColumns) return false
        if (position.row < 0 || position.row >= gridRows) return false
        return workspace.items.none { it.id != excludeId && it.position == position }
    }

    private fun findFreeCell(): GridPosition? {
        for (page in 0 until workspace.pageCount) {
            firstFreeCellOnPage(page)?.let { return it }
        }
        return null
    }

    private fun firstFreeCellOnPage(page: Int, excludeId: String? = null): GridPosition? {
        if (page < 0 || page >= workspace.pageCount) return null
        for (row in 0 until gridRows) {
            for (column in 0 until gridColumns) {
                val candidate = GridPosition(page, column, row)
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
