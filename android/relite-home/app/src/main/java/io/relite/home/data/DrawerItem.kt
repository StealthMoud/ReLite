package io.relite.home.data

/**
 * One slot in the Apps screen's Custom-order grid — either a plain app or a
 * [DrawerFolder]. Only the Custom-order grid groups into folders;
 * Alphabetical order and search results are always flattened to individual
 * [AppItem]s (see `AppSearch.customOrderItems`'s kdoc).
 */
sealed class DrawerItem {
    data class AppItem(val entry: AppEntry) : DrawerItem()
    data class FolderItem(val folder: DrawerFolder) : DrawerItem()
}
