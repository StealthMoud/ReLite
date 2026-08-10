package io.relite.home.data

/** A cell position on a home screen page or in the dock. */
data class GridPosition(val page: Int, val column: Int, val row: Int)

/** Section 26-27 (v0.5.0 completion pass): a folder's footprint on the grid — see [WorkspaceItem.FolderIcon.size]. */
enum class FolderSize {
    COMPACT,
    EXPANDED,
}

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
        // Section 26-27 (v0.5.0 completion pass): a folder normally occupies
        // one cell like an app icon; EXPANDED occupies a real 2x2 grid area
        // showing its member icons directly, launchable without opening the
        // full editor. Every folder that existed before this field was
        // added deserializes as COMPACT — see WorkspaceRepository.
        val size: FolderSize = FolderSize.COMPACT,
    ) : WorkspaceItem()

    data class WidgetIcon(
        override val id: String,
        override val position: GridPosition,
        val appWidgetId: Int,
        val spanColumns: Int,
        val spanRows: Int,
        // Section 21 (v0.4.0): appWidgetId alone is a device-local integer
        // with no meaning outside this specific install — useless for
        // diagnostics, layout export, or recovery after the id becomes
        // invalid (reboot with a stale binding, restore onto a different
        // device). The originating provider's ComponentName
        // ("pkg/pkg.WidgetProvider") survives all of that.
        val providerComponent: String,
    ) : WorkspaceItem()
}

/**
 * Section 55-57 (v0.5.0): the two Home grids current One UI (7) actually
 * offers — see docs/design/one-ui-current-reference.md for the sourced
 * claim. 4×6 is a strict superset of ReLite's previous fixed 4×5 grid (same
 * columns, one more row), so migrating a schema-1/2 workspace onto it never
 * requires moving a single existing item.
 */
enum class HomeGridPreset(val columns: Int, val rows: Int) {
    FOUR_BY_SIX(4, 6),
    FIVE_BY_SIX(5, 6),
}

/** The full persisted layout: workspace pages plus the dock. */
data class Workspace(
    val pageCount: Int,
    val items: List<WorkspaceItem>,
    val dockComponentKeys: List<String>,
    val homeGrid: HomeGridPreset = HomeGridPreset.FOUR_BY_SIX,
    // Section 80/119 (v0.5.0): which page Home opens on first launch —
    // set from the Home edit-mode page strip. Clamped to a valid page
    // index by WorkspaceController.setDefaultPage(); page removal also
    // keeps it valid, see WorkspaceController.removeEmptyPage.
    val defaultPage: Int = 0,
) {
    companion object {
        fun empty(pageCount: Int = 1): Workspace = Workspace(pageCount, emptyList(), emptyList())
    }
}
