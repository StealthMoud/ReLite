package io.relite.home.ui.drawer

import io.relite.home.ui.menu.showOneUi
import android.app.AlertDialog
import android.content.Context
import android.widget.EditText
import io.relite.home.R
import io.relite.home.data.DrawerFolder
import io.relite.home.util.AppsPreference
import java.util.UUID

/**
 * Section 6 (v0.5.0 completion pass): the Apps-screen equivalent of
 * [io.relite.home.ui.folder.FolderPicker] — pick an existing drawer folder
 * or create a new one, given a component key. A menu-driven, non-drag
 * alternative rather than drag-app-onto-app merge (see this pass's
 * CHANGELOG entry): the Apps grid's [androidx.recyclerview.widget.ItemTouchHelper]
 * only supports reorder, not overlap-based merge detection, and this
 * launcher's established pattern is to ship every drag-affordance with a
 * real, first-class non-drag alternative rather than leaving one out.
 */
object DrawerFolderPicker {

    fun show(context: Context, componentKey: String, onDone: () -> Unit) {
        val folders = AppsPreference.getFolders(context)
        val labels = folders.map { it.label } + context.getString(R.string.new_folder_option)

        AlertDialog.Builder(context)
            .setTitle(R.string.action_add_to_apps_folder)
            .setItems(labels.toTypedArray()) { _, which ->
                if (which < folders.size) {
                    addToFolder(context, folders[which].id, componentKey)
                    onDone()
                } else {
                    promptNewFolder(context, componentKey, onDone)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .showOneUi()
    }

    private fun promptNewFolder(context: Context, componentKey: String, onDone: () -> Unit) {
        val input = EditText(context).apply { setText(R.string.new_folder_label) }
        AlertDialog.Builder(context)
            .setTitle(R.string.new_folder_option)
            .setView(input)
            .setPositiveButton(R.string.ok) { _, _ ->
                val label = input.text.toString().trim().ifEmpty { context.getString(R.string.new_folder_label) }
                createFolder(context, label, componentKey)
                onDone()
            }
            .setNegativeButton(R.string.cancel, null)
            .showOneUi()
    }

    private fun createFolder(context: Context, label: String, componentKey: String) {
        val id = UUID.randomUUID().toString()
        AppsPreference.setFolders(context, AppsPreference.getFolders(context) + DrawerFolder(id, label, listOf(componentKey)))
        replaceOrderSlot(context, componentKey, AppsPreference.FOLDER_SLOT_PREFIX + id)
    }

    private fun addToFolder(context: Context, folderId: String, componentKey: String) {
        val folders = AppsPreference.getFolders(context).map { folder ->
            if (folder.id == folderId && componentKey !in folder.memberComponentKeys) {
                folder.copy(memberComponentKeys = folder.memberComponentKeys + componentKey)
            } else folder
        }
        AppsPreference.setFolders(context, folders)
        val order = AppsPreference.getCustomOrder(context).filterNot { it == componentKey }
        AppsPreference.setCustomOrder(context, order)
    }

    /** Swaps the app's own order slot for the folder's, so it disappears as a top-level tile the instant it's grouped. */
    private fun replaceOrderSlot(context: Context, componentKey: String, newSlot: String) {
        val order = AppsPreference.getCustomOrder(context).toMutableList()
        val idx = order.indexOf(componentKey)
        if (idx >= 0) order[idx] = newSlot else order.add(newSlot)
        AppsPreference.setCustomOrder(context, order)
    }
}
