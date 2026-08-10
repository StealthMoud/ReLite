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
    private val dockCapacity: Int = LauncherGridSpec.RMX5303.dockCapacity,
) {

    private var workspace: Workspace = repository.load()

    /**
     * Section 55-57 (v0.5.0): derived from the current workspace's own
     * persisted [HomeGridPreset] rather than a constructor-fixed value —
     * every caller reads this property (never stores the value), so a grid
     * change ([changeHomeGrid]) takes effect for every consumer without any
     * of them needing to be notified separately.
     */
    val gridSpec: LauncherGridSpec get() = LauncherGridSpec.forGrid(workspace.homeGrid, dockCapacity)

    fun current(): Workspace = workspace

    /** Re-reads persisted state, discarding any in-memory changes not yet saved. */
    fun reload() {
        workspace = repository.load()
    }

    /** Section 65: atomically replaces the entire workspace — layout import, reset. */
    fun replaceWorkspace(candidate: Workspace): Boolean = mutate { candidate }

    /**
     * Section 44 (v0.5.0): same as [replaceWorkspace], but also reports
     * which `appWidgetId`s existed before and are gone after — a reset or
     * import can drop [WorkspaceItem.WidgetIcon]s (reset always does;
     * import never carries widgets at all, see [WorkspaceRepository.exportPortable])
     * without the caller otherwise having any way to know which host
     * bindings are now orphaned. Only ever returns removed ids on a
     * successful commit — a failed save changes nothing, so nothing to clean up.
     */
    fun replaceWorkspaceSafely(candidate: Workspace): Pair<Boolean, Set<Int>> {
        val oldWidgetIds = workspace.items.filterIsInstance<WorkspaceItem.WidgetIcon>().map { it.appWidgetId }.toSet()
        val newWidgetIds = candidate.items.filterIsInstance<WorkspaceItem.WidgetIcon>().map { it.appWidgetId }.toSet()
        val removedIds = oldWidgetIds - newWidgetIds
        val ok = mutate { candidate }
        return ok to (if (ok) removedIds else emptySet())
    }

    // --- home screen items ---

    /**
     * Section 33 (v0.4.1): auto-placement that needs a fresh page now folds
     * the page-count bump and the new item into a single [mutate] call. The
     * previous version called [addPage] (its own persisted save) and then a
     * second, separate save for the item — if that second save failed, an
     * empty extra page was left committed to disk with nothing on it.
     */
    fun addApp(componentKey: String, position: GridPosition? = null): String? {
        if (position != null) {
            if (!isFree(GridRect.of(position, 1, 1))) return null
            val id = newId()
            return if (mutate { it.copy(items = it.items + WorkspaceItem.AppIcon(id, position, componentKey)) }) id else null
        }
        findFreeCell()?.let { target ->
            val id = newId()
            return if (mutate { it.copy(items = it.items + WorkspaceItem.AppIcon(id, target, componentKey)) }) id else null
        }
        if (workspace.pageCount >= MAX_AUTO_PAGES) return null
        val newPageIndex = workspace.pageCount
        val target = GridPosition(newPageIndex, 0, 0)
        val id = newId()
        return if (mutate {
                it.copy(pageCount = it.pageCount + 1, items = it.items + WorkspaceItem.AppIcon(id, target, componentKey))
            }
        ) {
            id
        } else {
            null
        }
    }

    fun removeItem(itemId: String): Boolean =
        mutate { it.copy(items = it.items.filterNot { item -> item.id == itemId }) }

    fun moveItem(itemId: String, to: GridPosition): Boolean {
        val item = workspace.items.find { it.id == itemId } ?: return false
        val span = spanOf(item)
        if (!isFree(GridRect.of(to, span.first, span.second), excludeId = itemId)) return false
        return mutate { ws -> ws.copy(items = ws.items.map { if (it.id == itemId) withPosition(it, to) else it }) }
    }

    /** Whether [itemId] could legally move to [to] right now — used for live drag feedback before the drop is committed. */
    fun canMoveTo(itemId: String, to: GridPosition): Boolean {
        val item = workspace.items.find { it.id == itemId } ?: return false
        val span = spanOf(item)
        return isFree(GridRect.of(to, span.first, span.second), excludeId = itemId)
    }

    /**
     * Section 35 (v0.4.1): moves [itemId] onto a brand-new page in one
     * transaction — the previous UI-level pattern called [addPage] and
     * [moveToPage] as two separate persisted mutations, so a [moveToPage]
     * failure after a successful [addPage] left a committed empty page
     * behind with the item still on its original page.
     */
    fun moveToNewPage(itemId: String): Boolean {
        val item = workspace.items.find { it.id == itemId } ?: return false
        val span = spanOf(item)
        if (span.first > gridSpec.columns || span.second > gridSpec.rows) return false
        if (workspace.pageCount >= MAX_AUTO_PAGES) return false
        val newPageIndex = workspace.pageCount
        val target = GridPosition(newPageIndex, 0, 0)
        return mutate { ws ->
            ws.copy(
                pageCount = ws.pageCount + 1,
                items = ws.items.map { if (it.id == itemId) withPosition(it, target) else it },
            )
        }
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
            val newDefaultPage = when {
                ws.defaultPage > page -> ws.defaultPage - 1
                ws.defaultPage == page -> 0
                else -> ws.defaultPage
            }
            ws.copy(pageCount = ws.pageCount - 1, items = shifted, defaultPage = newDefaultPage)
        }
    }

    /** Section 80/119 (v0.5.0): which page Home opens on first launch — set from the edit-mode page strip. */
    fun setDefaultPage(page: Int): Boolean {
        if (page < 0 || page >= workspace.pageCount) return false
        return mutate { it.copy(defaultPage = page) }
    }

    /**
     * Section 27-28 (v0.5.0 completion pass): reorders pages themselves —
     * distinct from [changeHomeGrid], which reflows items within a fixed
     * page count. [newOrder] must be a permutation of `0 until pageCount`;
     * `newOrder[i]` names which *old* page index now sits at new index `i`.
     * Every item keeps its (column, row, span) — only its page index moves —
     * and [Workspace.defaultPage] is remapped along with everything else so
     * "the default page" still points at the same physical page it did
     * before the reorder.
     */
    fun reorderPages(newOrder: List<Int>): Boolean {
        val pageCount = workspace.pageCount
        if (newOrder.size != pageCount) return false
        if (newOrder.toSet() != (0 until pageCount).toSet()) return false
        val oldToNew = IntArray(pageCount)
        newOrder.forEachIndexed { newIndex, oldIndex -> oldToNew[oldIndex] = newIndex }
        return mutate { ws ->
            val remapped = ws.items.map { item -> withPosition(item, item.position.copy(page = oldToNew[item.position.page])) }
            ws.copy(items = remapped, defaultPage = oldToNew[ws.defaultPage])
        }
    }

    /**
     * Section 55-59 (v0.5.0): switches the Home grid preset, reflowing any
     * item that no longer fits (or now collides) rather than ever dropping
     * it. An item keeps its exact cell if it's still valid there under the
     * new grid; otherwise it takes the first free rect on its own page, or —
     * if nothing fits there — a newly appended page. Processed in
     * page/row/column order for deterministic placement. This is a
     * simplified reflow (no separate preview step, no "moved items" report)
     * chosen over a full GridReflowPlanner architecture because the two
     * supported presets (4x6, 5x6) rarely require reflow at all: growing
     * (4x6->5x6) never invalidates an existing position, and shrinking
     * (5x6->4x6) only ever affects the last column.
     */
    fun changeHomeGrid(newPreset: HomeGridPreset): Boolean {
        if (newPreset == workspace.homeGrid) return true
        return mutate { ws ->
            val newSpec = LauncherGridSpec.forGrid(newPreset, gridSpec.dockCapacity)
            var pageCount = ws.pageCount
            val placed = mutableListOf<WorkspaceItem>()

            fun isFreeUnder(rect: GridRect): Boolean =
                rect.isWithinBounds(newSpec, pageCount) && placed.none { GridRect.of(it).intersects(rect) }

            fun firstFreeRectOnPage(page: Int, spanColumns: Int, spanRows: Int): GridRect? {
                for (row in 0 until newSpec.rows) {
                    for (column in 0 until newSpec.columns) {
                        val candidate = GridRect(page, column, row, spanColumns, spanRows)
                        if (isFreeUnder(candidate)) return candidate
                    }
                }
                return null
            }

            val ordered = ws.items.sortedWith(
                compareBy({ it.position.page }, { it.position.row }, { it.position.column }),
            )
            for (item in ordered) {
                val span = spanOf(item)
                val currentRect = GridRect.of(item.position, span.first, span.second)
                if (isFreeUnder(currentRect)) {
                    placed += item
                    continue
                }
                val onOwnPage = firstFreeRectOnPage(item.position.page, span.first, span.second)
                if (onOwnPage != null) {
                    placed += withPosition(item, GridPosition(onOwnPage.page, onOwnPage.column, onOwnPage.row))
                    continue
                }
                val newPage = pageCount
                pageCount += 1
                placed += withPosition(item, GridPosition(newPage, 0, 0))
            }

            ws.copy(homeGrid = newPreset, pageCount = pageCount, items = placed)
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

    /** Section 42 (v0.4.1): reorders a folder's members — same set, no duplicates, or it's rejected. */
    fun reorderFolderMembers(folderId: String, componentKeys: List<String>): Boolean {
        val folder = findFolder(folderId) ?: return false
        if (componentKeys.toSet() != folder.itemComponentKeys.toSet()) return false
        if (componentKeys.size != folder.itemComponentKeys.size) return false
        return updateFolder(folderId) { it.copy(itemComponentKeys = componentKeys) }
    }

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

    /**
     * Section 29 (v0.5.0): folds an existing home app being folded into an
     * existing folder into one transaction. The previous UI-level pattern
     * called [addAppToFolder] then, on success, a separate [removeItem] —
     * if that second save failed, the app ended up duplicated: a member of
     * the folder *and* still its own standalone home shortcut.
     */
    fun moveHomeAppIntoFolder(itemId: String, folderId: String): Boolean {
        val item = workspace.items.find { it.id == itemId } as? WorkspaceItem.AppIcon ?: return false
        val folder = findFolder(folderId) ?: return false
        if (item.componentKey in folder.itemComponentKeys) return false
        return mutate { ws ->
            ws.copy(
                items = ws.items.mapNotNull { current ->
                    when {
                        current.id == itemId -> null
                        current.id == folderId && current is WorkspaceItem.FolderIcon ->
                            current.copy(itemComponentKeys = current.itemComponentKeys + item.componentKey)
                        else -> current
                    }
                },
            )
        }
    }

    /**
     * Section 30 (v0.5.0): converts an existing standalone home app into a
     * brand-new folder occupying the same cell, with the app as its sole
     * member — one transaction instead of [createFolder] followed by a
     * separate [removeItem].
     */
    fun convertHomeAppToFolder(itemId: String, label: String): String? {
        val item = workspace.items.find { it.id == itemId } as? WorkspaceItem.AppIcon ?: return null
        val id = newId()
        val ok = mutate { ws ->
            ws.copy(
                items = ws.items.mapNotNull { current ->
                    if (current.id == itemId) {
                        WorkspaceItem.FolderIcon(id, item.position, label, listOf(item.componentKey))
                    } else {
                        current
                    }
                },
            )
        }
        return if (ok) id else null
    }

    /**
     * Section 19-22 (v0.5.0 completion pass): drag-app-onto-app folder
     * creation — atomically removes both standalone [WorkspaceItem.AppIcon]s
     * and replaces the target's cell with a brand-new folder containing
     * both, in one [mutate] so a save failure can never strand a duplicated
     * or a vanished app. The new folder keeps the *target's* grid position
     * (where the user actually dropped), not the source's.
     */
    fun createFolderFromApps(sourceItemId: String, targetItemId: String): String? {
        if (sourceItemId == targetItemId) return null
        val source = workspace.items.find { it.id == sourceItemId } as? WorkspaceItem.AppIcon ?: return null
        val target = workspace.items.find { it.id == targetItemId } as? WorkspaceItem.AppIcon ?: return null
        val id = newId()
        val ok = mutate { ws ->
            ws.copy(
                items = ws.items.mapNotNull { current ->
                    when (current.id) {
                        sourceItemId -> null
                        targetItemId -> WorkspaceItem.FolderIcon(
                            id,
                            target.position,
                            DEFAULT_FOLDER_LABEL,
                            listOf(target.componentKey, source.componentKey),
                        )
                        else -> current
                    }
                },
            )
        }
        return if (ok) id else null
    }

    // --- widgets ---

    /** Section 34 (v0.4.1): same one-mutate guarantee as [addApp] — see its doc comment. */
    fun addWidget(
        appWidgetId: Int,
        spanColumns: Int,
        spanRows: Int,
        providerComponent: String,
        position: GridPosition? = null,
    ): String? {
        fun commit(target: GridPosition, newPageCount: Int? = null): String? {
            val id = newId()
            val widget = WorkspaceItem.WidgetIcon(id, target, appWidgetId, spanColumns, spanRows, providerComponent)
            val ok = mutate { ws ->
                val base = if (newPageCount != null) ws.copy(pageCount = newPageCount) else ws
                base.copy(items = base.items + widget)
            }
            return if (ok) id else null
        }

        if (position != null) {
            if (!isFree(GridRect.of(position, spanColumns, spanRows))) return null
            return commit(position)
        }
        findFreeRect(spanColumns, spanRows)?.let { rect ->
            return commit(GridPosition(rect.page, rect.column, rect.row))
        }
        if (spanColumns <= 0 || spanRows <= 0 || spanColumns > gridSpec.columns || spanRows > gridSpec.rows) return null
        if (workspace.pageCount >= MAX_AUTO_PAGES) return null
        val newPageIndex = workspace.pageCount
        return commit(GridPosition(newPageIndex, 0, 0), newPageCount = workspace.pageCount + 1)
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

    /**
     * Section 20/21 (v0.4.1): exact `package/activity` reconciliation.
     * [removeShortcutsForPackage] only checks whether *any* activity of a
     * package remains launchable, so a package that simply renamed its
     * launcher activity (`.OldActivity` -> `.NewActivity`, package still
     * installed) left the stale `.OldActivity` shortcut in place forever —
     * present, but dead on tap. This removes shortcuts/dock/folder entries
     * whose exact componentKey is not in [stillLaunchable], leaving
     * everything else (including the package's still-valid new activity)
     * untouched. Widgets are host-managed and handled separately by
     * [removeWidgetsForMissingProviders].
     */
    fun removeStaleComponents(stillLaunchable: Set<String>): Boolean {
        return mutate { ws ->
            val items = ws.items.mapNotNull { item ->
                when (item) {
                    is WorkspaceItem.AppIcon ->
                        if (item.componentKey in stillLaunchable) item else null
                    is WorkspaceItem.FolderIcon ->
                        item.copy(itemComponentKeys = item.itemComponentKeys.filter { it in stillLaunchable })
                    is WorkspaceItem.WidgetIcon -> item
                }
            }
            val dock = ws.dockComponentKeys.filter { it in stillLaunchable }
            ws.copy(items = items, dockComponentKeys = dock)
        }
    }

    /**
     * Section 8/9 (v0.5.0): if a widget's exact provider component
     * (`package/providerClass`) is no longer installed/available, remove
     * the corresponding [WorkspaceItem.WidgetIcon] so the workspace never
     * renders a dead widget host view. [availableProviderComponents] must
     * come from `AppWidgetManager.installedProviders`, not from launcher
     * activities — a valid widget provider need not have one — and the
     * comparison is exact-component, not package-only, so one provider
     * disappearing from a multi-provider package doesn't reap a sibling
     * provider's still-valid widgets. Returns the set of `appWidgetId`s
     * removed so the caller can also delete the host binding
     * ([io.relite.home.ui.widget.ReliteAppWidgetHost.removeWidget]).
     */
    fun removeWidgetsForMissingProviders(availableProviderComponents: Set<String>): Set<Int> {
        val staleWidgetIds = workspace.items
            .filterIsInstance<WorkspaceItem.WidgetIcon>()
            .filter { it.providerComponent !in availableProviderComponents }
            .map { it.appWidgetId }
            .toSet()
        if (staleWidgetIds.isEmpty()) return emptySet()
        val committed = mutate { ws ->
            ws.copy(items = ws.items.filterNot { it is WorkspaceItem.WidgetIcon && it.appWidgetId in staleWidgetIds })
        }
        return if (committed) staleWidgetIds else emptySet()
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

    companion object {
        private const val MAX_AUTO_PAGES = 20

        // Matches R.string.new_folder_label — this class is deliberately
        // Context-free (pure Kotlin, unit-testable without Android), so it
        // can't read the string resource itself; the UI layer already
        // renames a freshly drag-created folder immediately in most flows.
        private const val DEFAULT_FOLDER_LABEL = "New folder"
    }
}
