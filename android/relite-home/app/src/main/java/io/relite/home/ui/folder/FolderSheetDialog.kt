package io.relite.home.ui.folder

import android.app.Dialog
import android.content.pm.LauncherApps
import android.os.Bundle
import androidx.fragment.app.DialogFragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.relite.home.R
import io.relite.home.ReliteHomeApplication
import io.relite.home.data.AppEntry
import io.relite.home.ui.drawer.AppDrawerAdapter
import io.relite.home.util.IconCache

/**
 * Large, rounded folder presentation (master plan section 19) — a plain
 * DialogFragment rather than a custom bottom-sheet dependency, since a
 * folder is a short-lived, centered overlay rather than a persistent
 * bottom-anchored surface.
 */
class FolderSheetDialog : DialogFragment() {

    var onAppLaunch: ((AppEntry) -> Unit)? = null

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val app = requireActivity().application as ReliteHomeApplication
        val launcherApps = requireContext().getSystemService(LauncherApps::class.java)
        val iconSizePx = resources.getDimensionPixelSize(R.dimen.icon_size)
        val iconCache = IconCache(launcherApps, iconSizePx)

        val componentKeys = requireArguments().getStringArrayList(ARG_COMPONENT_KEYS).orEmpty()
        val allApps = app.appRepository.loadAll().associateBy { it.componentKey }
        val folderApps = componentKeys.mapNotNull { allApps[it] }

        val view = layoutInflater.inflate(R.layout.dialog_folder, null)
        view.findViewById<android.widget.TextView>(R.id.folder_title).text =
            requireArguments().getString(ARG_LABEL).orEmpty()

        val adapter = AppDrawerAdapter(
            iconCache = iconCache,
            onAppClick = { onAppLaunch?.invoke(it); dismiss() },
            onAppLongClick = { _, _ -> false },
        )
        view.findViewById<RecyclerView>(R.id.folder_recycler).apply {
            layoutManager = GridLayoutManager(requireContext(), COLUMN_COUNT)
            this.adapter = adapter
        }
        adapter.submitList(folderApps)

        return Dialog(requireContext(), R.style.Theme_ReliteHome_FolderDialog).apply {
            setContentView(view)
        }
    }

    companion object {
        private const val ARG_LABEL = "label"
        private const val ARG_COMPONENT_KEYS = "component_keys"
        private const val COLUMN_COUNT = 4

        fun newInstance(label: String, componentKeys: List<String>): FolderSheetDialog =
            FolderSheetDialog().apply {
                arguments = Bundle().apply {
                    putString(ARG_LABEL, label)
                    putStringArrayList(ARG_COMPONENT_KEYS, ArrayList(componentKeys))
                }
            }
    }
}
