package io.relite.home.data

/**
 * The single authoritative workspace grid geometry — column/row count and
 * dock capacity. Before this existed, `WorkspaceController` took three raw
 * Ints and `HomePageFragment`/`AppDrawerFragment`/`FolderSheetDialog` each
 * hardcoded their own separate `COLUMN_COUNT = 4` constant; nothing
 * guaranteed they agreed, and a renderer could show a different geometry
 * than the controller validated positions against (section 18 of the
 * v0.4.0 plan).
 *
 * [dockCapacity] counts app-shortcut slots only, not the fixed "open
 * drawer" button [WorkspaceDockView] always renders alongside them
 * (section 42): a dock with `dockCapacity = 4` holds 4 pinned apps plus
 * the drawer button, 5 visual slots total.
 */
data class LauncherGridSpec(
    val columns: Int,
    val rows: Int,
    val dockCapacity: Int,
) {
    init {
        require(columns > 0) { "columns must be positive, got $columns" }
        require(rows > 0) { "rows must be positive, got $rows" }
        require(dockCapacity > 0) { "dockCapacity must be positive, got $dockCapacity" }
    }

    companion object {
        /** Current RMX5303 default — see ReliteHomeApplication / section 18. */
        val RMX5303 = LauncherGridSpec(columns = 4, rows = 5, dockCapacity = 5)
    }
}
