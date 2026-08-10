package io.relite.home.ui.drawer

import android.app.AlertDialog
import android.app.Dialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.EditText
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.relite.home.R
import io.relite.home.ReliteHomeApplication
import io.relite.home.data.AppEntry
import io.relite.home.data.DrawerFolder
import io.relite.home.ui.LauncherHost
import io.relite.home.ui.launcherHost
import io.relite.home.ui.menu.LauncherAction
import io.relite.home.ui.menu.LauncherContextMenu
import io.relite.home.util.AppsPreference
import io.relite.home.util.IconSizePreference

/**
 * Section 6 (v0.5.0 completion pass): the Apps-screen folder editor —
 * mirrors [io.relite.home.ui.folder.FolderSheetDialog]'s shape and layout,
 * but reads/writes [AppsPreference]'s folder storage instead of
 * [io.relite.home.data.WorkspaceController], since a drawer folder has no
 * grid position and never touches `workspace.json`.
 */
class DrawerFolderSheetDialog : DialogFragment() {

    private val host: LauncherHost get() = launcherHost()

    private lateinit var app: ReliteHomeApplication
    private lateinit var folderId: String
    private lateinit var adapter: AppDrawerAdapter
    private lateinit var titleView: TextView
    private var onChanged: (() -> Unit)? = null

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        app = requireActivity().application as ReliteHomeApplication
        folderId = requireArguments().getString(ARG_FOLDER_ID)!!

        val view = layoutInflater.inflate(R.layout.dialog_folder, null)
        titleView = view.findViewById(R.id.folder_title)

        adapter = AppDrawerAdapter(
            iconCache = app.iconCache,
            iconSizePx = IconSizePreference.resolvePx(requireContext(), resources.getDimensionPixelSize(R.dimen.icon_size)),
            onAppClick = { host.launchComponent(it.componentKey); dismiss() },
            onAppLongClick = { entry, anchor -> showMemberMenu(entry, anchor); true },
        )
        view.findViewById<RecyclerView>(R.id.folder_recycler).apply {
            layoutManager = GridLayoutManager(requireContext(), app.workspaceController.gridSpec.columns)
            adapter = this@DrawerFolderSheetDialog.adapter
        }

        titleView.setOnClickListener { showRenameDialog() }
        view.findViewById<android.widget.Button>(R.id.folder_delete).setOnClickListener {
            deleteFolder()
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

    private fun currentFolder(): DrawerFolder? =
        AppsPreference.getFolders(requireContext()).find { it.id == folderId }

    private fun refresh() {
        val folder = currentFolder()
        if (folder == null) {
            dismiss()
            return
        }
        titleView.text = folder.label
        val allApps = app.appRepository.loadAll().associateBy { it.componentKey }
        adapter.submitList(folder.memberComponentKeys.mapNotNull { allApps[it] })
    }

    private fun showMemberMenu(entry: AppEntry, anchor: android.view.View) {
        val actions = listOf(
            LauncherAction(MENU_ID_REMOVE, getString(R.string.action_remove_from_folder)),
            LauncherAction(MENU_ID_APP_INFO, getString(R.string.action_app_info)),
        )
        LauncherContextMenu.show(anchor, actions) { actionId ->
            when (actionId) {
                MENU_ID_REMOVE -> {
                    removeMember(entry.componentKey)
                    onChanged?.invoke()
                    refresh()
                }
                MENU_ID_APP_INFO -> {
                    startActivity(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", entry.packageName, null),
                        ),
                    )
                }
            }
        }
    }

    /** Puts the removed app back into the drawer's own order sequence, appended at the end, rather than losing it. */
    private fun removeMember(componentKey: String) {
        val folders = AppsPreference.getFolders(requireContext()).map { folder ->
            if (folder.id == folderId) folder.copy(memberComponentKeys = folder.memberComponentKeys.filterNot { it == componentKey }) else folder
        }
        AppsPreference.setFolders(requireContext(), folders)
        val order = AppsPreference.getCustomOrder(requireContext())
        if (componentKey !in order) {
            AppsPreference.setCustomOrder(requireContext(), order + componentKey)
        }
    }

    /** Dissolves the folder: every remaining member returns to the drawer as its own tile, appended at the end. */
    private fun deleteFolder() {
        val folder = currentFolder() ?: return
        val folders = AppsPreference.getFolders(requireContext()).filterNot { it.id == folderId }
        AppsPreference.setFolders(requireContext(), folders)
        val order = AppsPreference.getCustomOrder(requireContext())
            .filterNot { it == AppsPreference.FOLDER_SLOT_PREFIX + folderId }
        val toAppend = folder.memberComponentKeys.filterNot { it in order }
        AppsPreference.setCustomOrder(requireContext(), order + toAppend)
        onChanged?.invoke()
    }

    private fun showRenameDialog() {
        val folder = currentFolder() ?: return
        val input = EditText(requireContext()).apply { setText(folder.label) }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.rename_folder_title)
            .setView(input)
            .setPositiveButton(R.string.ok) { _, _ ->
                val newLabel = input.text.toString().trim().take(MAX_LABEL_LENGTH)
                if (newLabel.isNotEmpty()) {
                    val folders = AppsPreference.getFolders(requireContext()).map {
                        if (it.id == folderId) it.copy(label = newLabel) else it
                    }
                    AppsPreference.setFolders(requireContext(), folders)
                    onChanged?.invoke()
                    refresh()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showAddAppsDialog() {
        val folder = currentFolder() ?: return
        val candidates = app.appRepository.loadAll()
            .filterNot { it.componentKey in folder.memberComponentKeys }
            .sortedBy { it.label.lowercase() }
        if (candidates.isEmpty()) return

        lateinit var dialog: AlertDialog
        val pickerAdapter = AppDrawerAdapter(
            iconCache = app.iconCache,
            iconSizePx = IconSizePreference.resolvePx(requireContext(), resources.getDimensionPixelSize(R.dimen.icon_size)),
            onAppClick = { entry ->
                val folders = AppsPreference.getFolders(requireContext()).map {
                    if (it.id == folderId && entry.componentKey !in it.memberComponentKeys) {
                        it.copy(memberComponentKeys = it.memberComponentKeys + entry.componentKey)
                    } else it
                }
                AppsPreference.setFolders(requireContext(), folders)
                AppsPreference.setCustomOrder(
                    requireContext(),
                    AppsPreference.getCustomOrder(requireContext()).filterNot { it == entry.componentKey },
                )
                onChanged?.invoke()
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
        private const val MAX_LABEL_LENGTH = 40

        fun show(fragmentManager: androidx.fragment.app.FragmentManager, folderId: String, onChanged: () -> Unit) {
            val dialog = DrawerFolderSheetDialog().apply {
                arguments = Bundle().apply { putString(ARG_FOLDER_ID, folderId) }
                this.onChanged = onChanged
            }
            dialog.show(fragmentManager, "drawer_folder_$folderId")
        }
    }
}
