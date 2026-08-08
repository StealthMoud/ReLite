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
