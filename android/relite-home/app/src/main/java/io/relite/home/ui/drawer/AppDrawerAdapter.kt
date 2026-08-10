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
import io.relite.home.util.IconCache

class AppDrawerAdapter(
    private val iconCache: IconCache,
    private val onAppClick: (AppEntry) -> Unit,
    // Section 92 (v0.5.0): long-press is overloaded between "open the item
    // action menu" and "start a Custom-order drag" — the caller decides
    // which applies for the current sort mode rather than this adapter
    // guessing, since it has no sort-mode concept of its own.
    private val onAppLongClick: (AppEntry, View) -> Boolean,
) : ListAdapter<AppEntry, AppDrawerAdapter.ViewHolder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_app_drawer, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val app = getItem(position)
        holder.label.text = app.label
        holder.icon.setImageDrawable(iconCache.get(app.packageName, app.activityName))
        holder.itemView.setOnClickListener { onAppClick(app) }
        holder.itemView.setOnLongClickListener { onAppLongClick(app, it) }
    }

    /**
     * Section 92 (v0.5.0): applies a drag-reorder move immediately (not via
     * the normal async [submitList] diff path) so [androidx.recyclerview.widget.ItemTouchHelper]
     * sees the RecyclerView's child positions actually match the adapter's
     * backing list on every intermediate step of a drag, not just the final
     * settled state.
     */
    fun moveItem(from: Int, to: Int) {
        if (from == to || from !in 0 until itemCount || to !in 0 until itemCount) return
        val current = currentList.toMutableList()
        val item = current.removeAt(from)
        current.add(to, item)
        submitList(current) // DiffUtil detects the move itself via stable componentKey ids
    }

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val icon: ImageView = itemView.findViewById(R.id.app_icon)
        val label: TextView = itemView.findViewById(R.id.app_label)
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<AppEntry>() {
            override fun areItemsTheSame(oldItem: AppEntry, newItem: AppEntry) =
                oldItem.componentKey == newItem.componentKey
            override fun areContentsTheSame(oldItem: AppEntry, newItem: AppEntry) = oldItem == newItem
        }
    }
}
