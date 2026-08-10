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
        /**
         * Pre-schema-v3 fixed grid, kept only as the historical migration
         * input and the default dock capacity — the workspace's own
         * geometry is now [HomeGridPreset]-driven (section 55-57, v0.5.0);
         * see [forGrid].
         */
        val RMX5303 = LauncherGridSpec(columns = 4, rows = 5, dockCapacity = 5)

        fun forGrid(preset: HomeGridPreset, dockCapacity: Int = RMX5303.dockCapacity): LauncherGridSpec =
            LauncherGridSpec(columns = preset.columns, rows = preset.rows, dockCapacity = dockCapacity)
    }
}
