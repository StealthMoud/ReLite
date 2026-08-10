package io.relite.home.ui.folder

import android.app.AlertDialog
import android.content.Context
import android.widget.EditText
import android.widget.Toast
import io.relite.home.R
import io.relite.home.data.WorkspaceController
import io.relite.home.data.WorkspaceItem

/**
 * Shared "Add to Folder" menu flow (master plan section 31): pick an
 * existing folder or create a new one. If [existingHomeItemId] is set (the
 * app is already a standalone home shortcut being folded in, rather than a
 * fresh add from the drawer), that shortcut is removed once the app is
 * successfully placed in the folder — never left duplicated on the grid.
 */
object FolderPicker {

    fun show(
        context: Context,
        controller: WorkspaceController,
        componentKey: String,
        existingHomeItemId: String? = null,
        onDone: () -> Unit,
    ) {
        val folders = controller.current().items.filterIsInstance<WorkspaceItem.FolderIcon>()
        val labels = folders.map { it.label } + context.getString(R.string.new_folder_option)

        AlertDialog.Builder(context)
            .setTitle(R.string.action_add_to_folder)
            .setItems(labels.toTypedArray()) { _, which ->
                if (which < folders.size) {
                    addToFolder(controller, folders[which].id, componentKey, existingHomeItemId, onDone)
                } else {
                    promptNewFolder(context, controller, componentKey, existingHomeItemId, onDone)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun promptNewFolder(
        context: Context,
        controller: WorkspaceController,
        componentKey: String,
        existingHomeItemId: String?,
        onDone: () -> Unit,
    ) {
        val input = EditText(context).apply { setText(R.string.new_folder_label) }
        AlertDialog.Builder(context)
            .setTitle(R.string.new_folder_option)
            .setView(input)
            .setPositiveButton(R.string.ok) { _, _ ->
                val label = input.text.toString().trim().ifEmpty { context.getString(R.string.new_folder_label) }
                // Section 30 (v0.5.0): a single atomic conversion when an
                // existing home app is being folded in — see
                // WorkspaceController.convertHomeAppToFolder's kdoc for why
                // this must not be two separate saves.
                val ok = if (existingHomeItemId != null) {
                    controller.convertHomeAppToFolder(existingHomeItemId, label) != null
                } else {
                    controller.createFolder(label, listOf(componentKey)) != null
                }
                if (!ok) {
                    Toast.makeText(context, R.string.workspace_full, Toast.LENGTH_SHORT).show()
                } else {
                    onDone()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun addToFolder(
        controller: WorkspaceController,
        folderId: String,
        componentKey: String,
        existingHomeItemId: String?,
        onDone: () -> Unit,
    ) {
        // Section 29 (v0.5.0): one atomic move when folding an existing home
        // app into an existing folder, instead of addAppToFolder followed by
        // a separate removeItem.
        if (existingHomeItemId != null) {
            controller.moveHomeAppIntoFolder(existingHomeItemId, folderId)
        } else {
            controller.addAppToFolder(folderId, componentKey)
        }
        onDone()
    }
}
