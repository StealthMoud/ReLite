package io.relite.home.ui.drawer

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import io.relite.home.R
import io.relite.home.data.AppEntry
import io.relite.home.data.DrawerFolder
import io.relite.home.data.DrawerItem
import io.relite.home.ui.home.FolderPreview
import io.relite.home.util.IconCache

/**
 * Section 6 (v0.5.0 completion pass): the Apps screen's top-level Custom-order
 * grid — unlike [AppDrawerAdapter] (used for the drawer's plain search/
 * alphabetical list and every folder-member picker/editor, which only ever
 * show individual apps), this one renders a mix of [DrawerItem.AppItem] and
 * [DrawerItem.FolderItem] tiles so an Apps-screen-native folder (see
 * [io.relite.home.data.DrawerFolder]'s kdoc) can occupy a grid slot.
 */
class DrawerGridAdapter(
    private val iconCache: IconCache,
    private val iconSizePx: Int,
    private val onAppClick: (AppEntry) -> Unit,
    private val onAppLongClick: (AppEntry, View) -> Boolean,
    private val onFolderClick: (DrawerFolder) -> Unit,
    private val onFolderLongClick: (DrawerFolder, View) -> Boolean,
) : ListAdapter<DrawerItem, RecyclerView.ViewHolder>(DIFF) {

    /**
     * Explicit tile width for the horizontally-paged Custom-order grid, or 0
     * to keep `item_app_drawer`'s own `match_parent`.
     *
     * A real bug found live on the RMX5303: Custom order rendered as a
     * single full-width column of apps instead of a 4-wide page. In a
     * *vertical* `GridLayoutManager` the item's `match_parent` width means
     * "fill one span" — correct. In the *horizontal* one Custom order uses,
     * width is the scrolling axis, so it is unconstrained and `match_parent`
     * resolves to the RecyclerView's whole width; only the height gets
     * divided into spans. Every tile was therefore a full screen wide and
     * exactly one column was ever visible. The span (row) height is already
     * correct in both orientations, so only the width needs pinning here.
     */
    var itemWidthPx: Int = 0
        set(value) {
            if (field == value) return
            field = value
            notifyDataSetChanged()
        }

    override fun getItemViewType(position: Int): Int = when (getItem(position)) {
        is DrawerItem.AppItem -> VIEW_TYPE_APP
        is DrawerItem.FolderItem -> VIEW_TYPE_FOLDER
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_app_drawer, parent, false)
        return ViewHolder(view)
    }

    private fun applyItemWidth(holder: RecyclerView.ViewHolder) {
        val params = holder.itemView.layoutParams ?: return
        val target = if (itemWidthPx > 0) itemWidthPx else ViewGroup.LayoutParams.MATCH_PARENT
        if (params.width != target) {
            params.width = target
            holder.itemView.layoutParams = params
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        applyItemWidth(holder)
        val vh = holder as ViewHolder
        when (val item = getItem(position)) {
            is DrawerItem.AppItem -> {
                val entry = item.entry
                vh.label.text = entry.label
                vh.icon.setImageDrawable(iconCache.get(entry.packageName, entry.activityName, iconSizePx))
                vh.itemView.setOnClickListener { onAppClick(entry) }
                vh.itemView.setOnLongClickListener { onAppLongClick(entry, it) }
            }
            is DrawerItem.FolderItem -> {
                val folder = item.folder
                vh.label.text = folder.label
                vh.icon.setImageDrawable(FolderPreview.render(vh.itemView.context, iconCache, folder.memberComponentKeys, iconSizePx))
                vh.itemView.setOnClickListener { onFolderClick(folder) }
                vh.itemView.setOnLongClickListener { onFolderLongClick(folder, it) }
            }
        }
    }

    /** Same immediate-apply pattern as [AppDrawerAdapter.moveItem] — see its kdoc. */
    fun moveItem(from: Int, to: Int) {
        if (from == to || from !in 0 until itemCount || to !in 0 until itemCount) return
        val current = currentList.toMutableList()
        val item = current.removeAt(from)
        current.add(to, item)
        submitList(current)
    }

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val icon: ImageView = itemView.findViewById(R.id.app_icon)
        val label: TextView = itemView.findViewById(R.id.app_label)
    }

    companion object {
        private const val VIEW_TYPE_APP = 0
        private const val VIEW_TYPE_FOLDER = 1

        private fun keyOf(item: DrawerItem): String = when (item) {
            is DrawerItem.AppItem -> "app:" + item.entry.componentKey
            is DrawerItem.FolderItem -> "folder:" + item.folder.id
        }

        private val DIFF = object : DiffUtil.ItemCallback<DrawerItem>() {
            override fun areItemsTheSame(oldItem: DrawerItem, newItem: DrawerItem) = keyOf(oldItem) == keyOf(newItem)
            override fun areContentsTheSame(oldItem: DrawerItem, newItem: DrawerItem) = oldItem == newItem
        }
    }
}
