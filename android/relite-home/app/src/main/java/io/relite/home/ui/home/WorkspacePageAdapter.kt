package io.relite.home.ui.home

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import io.relite.home.R
import io.relite.home.data.WorkspaceItem
import io.relite.home.util.IconCache

/**
 * Renders one home-screen page's icons (apps + folders; widgets are placed
 * as overlay views by HomeFragment since they need their own live
 * AppWidgetHostView, not a RecyclerView cell).
 */
class WorkspacePageAdapter(
    private val items: List<WorkspaceItem>,
    private val iconCache: IconCache,
    private val labelFor: (WorkspaceItem) -> String,
    private val onClick: (WorkspaceItem) -> Unit,
    private val onLongClick: (WorkspaceItem, View) -> Boolean,
) : RecyclerView.Adapter<WorkspacePageAdapter.ViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_workspace_icon, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.label.text = labelFor(item)
        holder.icon.setImageDrawable(iconFor(item))
        holder.itemView.setOnClickListener { onClick(item) }
        holder.itemView.setOnLongClickListener { onLongClick(item, it) }
    }

    private fun iconFor(item: WorkspaceItem) = when (item) {
        is WorkspaceItem.AppIcon -> {
            val (pkg, activity) = item.componentKey.split("/", limit = 2)
            iconCache.get(pkg, activity)
        }
        is WorkspaceItem.FolderIcon -> null // folder icon is drawn from a themed background, not a package icon
        is WorkspaceItem.WidgetIcon -> null
    }

    override fun getItemCount(): Int = items.size

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val icon: ImageView = itemView.findViewById(R.id.icon)
        val label: TextView = itemView.findViewById(R.id.label)
    }
}
