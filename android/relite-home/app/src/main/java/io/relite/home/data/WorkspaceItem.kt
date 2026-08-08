package io.relite.home.data

/** A cell position on a home screen page or in the dock. */
data class GridPosition(val page: Int, val column: Int, val row: Int)

/**
 * Anything that can occupy a workspace cell: an app shortcut, a folder, or
 * a hosted widget. Sealed so persistence/rendering code stays exhaustive.
 */
sealed class WorkspaceItem {
    abstract val id: String
    abstract val position: GridPosition

    data class AppIcon(
        override val id: String,
        override val position: GridPosition,
        val componentKey: String,
    ) : WorkspaceItem()

    data class FolderIcon(
        override val id: String,
        override val position: GridPosition,
        val label: String,
        val itemComponentKeys: List<String>,
    ) : WorkspaceItem()

    data class WidgetIcon(
        override val id: String,
        override val position: GridPosition,
        val appWidgetId: Int,
        val spanColumns: Int,
        val spanRows: Int,
    ) : WorkspaceItem()
}

/** The full persisted layout: workspace pages plus the dock. */
data class Workspace(
    val pageCount: Int,
    val items: List<WorkspaceItem>,
    val dockComponentKeys: List<String>,
) {
    companion object {
        fun empty(pageCount: Int = 1): Workspace = Workspace(pageCount, emptyList(), emptyList())
    }
}
