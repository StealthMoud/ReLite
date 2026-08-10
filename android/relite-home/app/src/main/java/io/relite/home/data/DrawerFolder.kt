package io.relite.home.data

/**
 * Section 6 (v0.5.0 completion pass): an Apps-screen-native folder — grouping
 * within the Custom-order drawer grid, entirely separate from a Home
 * [WorkspaceItem.FolderIcon]. Has no position/span (the drawer isn't a
 * positioned grid, just an ordered sequence) and never touches
 * [WorkspaceController] or `workspace.json`.
 */
data class DrawerFolder(
    val id: String,
    val label: String,
    val memberComponentKeys: List<String>,
)
