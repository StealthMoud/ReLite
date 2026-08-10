package io.relite.home.ui.folder

import android.app.AlertDialog
import android.app.Dialog
import android.os.Bundle
import android.widget.EditText
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.relite.home.R
import io.relite.home.ReliteHomeApplication
import io.relite.home.data.AppEntry
import io.relite.home.ui.drawer.AppDrawerAdapter

/**
 * Full folder editor (master plan sections 33-39): open, rename, add
 * members, remove members, reorder is left as-is (list order == member
 * order, no separate drag needed for the common small-folder case), and
 * delete — whether or not the folder still has members. Re-reads the
 * folder from [io.relite.home.data.WorkspaceController] by id on every
 * redraw rather than holding a static snapshot, so edits made through this
 * dialog show up immediately without closing and reopening it.
 */
class FolderSheetDialog : DialogFragment() {

    var onAppLaunch: ((AppEntry) -> Unit)? = null
    var onWorkspaceChanged: (() -> Unit)? = null

    private lateinit var app: ReliteHomeApplication
    private lateinit var folderId: String
    private lateinit var adapter: AppDrawerAdapter
    private lateinit var titleView: TextView

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        app = requireActivity().application as ReliteHomeApplication
        folderId = requireArguments().getString(ARG_FOLDER_ID)!!

        val view = layoutInflater.inflate(R.layout.dialog_folder, null)
        titleView = view.findViewById(R.id.folder_title)

        adapter = AppDrawerAdapter(
            iconCache = app.iconCache,
            onAppClick = { onAppLaunch?.invoke(it); dismiss() },
            onAppLongClick = { entry, anchor -> showMemberMenu(entry, anchor); true },
        )
        view.findViewById<RecyclerView>(R.id.folder_recycler).apply {
            layoutManager = GridLayoutManager(requireContext(), app.workspaceController.gridSpec.columns)
            adapter = this@FolderSheetDialog.adapter
        }

        titleView.setOnClickListener { showRenameDialog() }
        view.findViewById<android.widget.Button>(R.id.folder_delete).setOnClickListener {
            app.workspaceController.removeItem(folderId)
            onWorkspaceChanged?.invoke()
            dismiss()
        }
        view.findViewById<android.widget.Button>(R.id.folder_add_apps).setOnClickListener {
            showAddAppsDialog()
        }

        refresh()

        return Dialog(requireContext(), R.style.Theme_ReliteHome_FolderDialog).apply {
            setContentView(view)
        }
    }

    /** Re-reads the folder and re-binds the title/member list — called after every mutation. */
    private fun refresh() {
        val folder = app.workspaceController.current().items
            .filterIsInstance<io.relite.home.data.WorkspaceItem.FolderIcon>()
            .find { it.id == folderId }
        if (folder == null) {
            dismiss()
            return
        }
        titleView.text = folder.label
        val allApps = app.appRepository.loadAll().associateBy { it.componentKey }
        adapter.submitList(folder.itemComponentKeys.mapNotNull { allApps[it] })
    }

    /**
     * Section 41-42 (v0.4.1): reorder via simple "Move left"/"Move right"
     * actions rather than an in-folder drag gesture — more reliable for the
     * common small-folder case, and consistent with the accessibility
     * fallback already used everywhere else in this launcher (e.g. "Move to
     * page" instead of raw drag).
     */
    private fun showMemberMenu(entry: AppEntry, anchor: android.view.View) {
        val folder = app.workspaceController.current().items
            .filterIsInstance<io.relite.home.data.WorkspaceItem.FolderIcon>()
            .find { it.id == folderId } ?: return
        val index = folder.itemComponentKeys.indexOf(entry.componentKey)

        val actions = buildList {
            if (index > 0) add(io.relite.home.ui.menu.LauncherAction(MENU_ID_MOVE_LEFT, getString(R.string.action_move_left)))
            if (index in 0 until folder.itemComponentKeys.size - 1) {
                add(io.relite.home.ui.menu.LauncherAction(MENU_ID_MOVE_RIGHT, getString(R.string.action_move_right)))
            }
            add(io.relite.home.ui.menu.LauncherAction(MENU_ID_REMOVE, getString(R.string.action_remove_from_folder)))
            add(io.relite.home.ui.menu.LauncherAction(MENU_ID_APP_INFO, getString(R.string.action_app_info)))
        }
        io.relite.home.ui.menu.LauncherContextMenu.show(anchor, actions) { actionId ->
            when (actionId) {
                MENU_ID_MOVE_LEFT -> reorder(folder.itemComponentKeys, index, index - 1)
                MENU_ID_MOVE_RIGHT -> reorder(folder.itemComponentKeys, index, index + 1)
                MENU_ID_REMOVE -> {
                    app.workspaceController.removeAppFromFolder(folderId, entry.componentKey)
                    onWorkspaceChanged?.invoke()
                    refresh()
                }
                MENU_ID_APP_INFO -> {
                    val intent = android.content.Intent(
                        android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        android.net.Uri.fromParts("package", entry.packageName, null),
                    )
                    startActivity(intent)
                }
            }
        }
    }

    private fun reorder(current: List<String>, from: Int, to: Int) {
        val mutable = current.toMutableList()
        val moved = mutable.removeAt(from)
        mutable.add(to, moved)
        app.workspaceController.reorderFolderMembers(folderId, mutable)
        onWorkspaceChanged?.invoke()
        refresh()
    }

    private fun showRenameDialog() {
        val folder = app.workspaceController.current().items
            .filterIsInstance<io.relite.home.data.WorkspaceItem.FolderIcon>()
            .find { it.id == folderId } ?: return
        val input = EditText(requireContext()).apply { setText(folder.label) }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.rename_folder_title)
            .setView(input)
            .setPositiveButton(R.string.ok) { _, _ ->
                val newLabel = input.text.toString().trim().take(MAX_LABEL_LENGTH)
                if (newLabel.isNotEmpty()) {
                    app.workspaceController.renameFolder(folderId, newLabel)
                    onWorkspaceChanged?.invoke()
                    refresh()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showAddAppsDialog() {
        val folder = app.workspaceController.current().items
            .filterIsInstance<io.relite.home.data.WorkspaceItem.FolderIcon>()
            .find { it.id == folderId } ?: return
        val candidates = app.appRepository.loadAll()
            .filterNot { it.componentKey in folder.itemComponentKeys }
            .sortedBy { it.label.lowercase() }
        if (candidates.isEmpty()) return

        lateinit var dialog: AlertDialog
        val pickerAdapter = AppDrawerAdapter(
            iconCache = app.iconCache,
            onAppClick = { entry ->
                app.workspaceController.addAppToFolder(folderId, entry.componentKey)
                onWorkspaceChanged?.invoke()
                refresh()
                dialog.dismiss()
            },
            onAppLongClick = { _, _ -> false },
        ).apply { submitList(candidates) }
        val recycler = RecyclerView(requireContext()).apply {
            layoutManager = GridLayoutManager(requireContext(), app.workspaceController.gridSpec.columns)
            adapter = pickerAdapter
        }
        dialog = AlertDialog.Builder(requireContext())
            .setTitle(R.string.action_add_apps)
            .setView(recycler)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.show()
    }

    companion object {
        private const val ARG_FOLDER_ID = "folder_id"
        private const val MENU_ID_REMOVE = 1
        private const val MENU_ID_APP_INFO = 2
        private const val MENU_ID_MOVE_LEFT = 3
        private const val MENU_ID_MOVE_RIGHT = 4
        private const val MAX_LABEL_LENGTH = 40

        fun newInstance(folderId: String): FolderSheetDialog = FolderSheetDialog().apply {
            arguments = Bundle().apply { putString(ARG_FOLDER_ID, folderId) }
        }
    }
}
