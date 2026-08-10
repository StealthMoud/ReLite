package io.relite.home.ui.widget

import io.relite.home.data.WorkspaceController
import io.relite.home.data.WorkspaceItem

/**
 * Section 11-12 (v0.4.1): the one canonical widget-removal path. The old
 * call sites deleted the AppWidgetHost id *before* persisting the workspace
 * removal — if persistence then failed, the workspace still referenced a
 * widget id that no longer existed on the host, a state nothing could
 * recover from. Persisting first means a failed removal simply leaves the
 * widget in place with a still-valid id; only a *successful* persisted
 * removal deletes the host binding.
 */
object WidgetLifecycle {
    fun removeWidgetSafely(
        workspaceController: WorkspaceController,
        host: ReliteAppWidgetHost,
        item: WorkspaceItem.WidgetIcon,
    ): Boolean {
        val persisted = workspaceController.removeItem(item.id)
        if (persisted) {
            host.removeWidget(item.appWidgetId)
        }
        return persisted
    }
}
