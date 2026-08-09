package io.relite.home.ui.widget

import android.appwidget.AppWidgetProviderInfo
import android.content.pm.PackageManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import io.relite.home.R

class WidgetProviderAdapter(
    private val packageManager: PackageManager,
    private val onProviderClick: (AppWidgetProviderInfo) -> Unit,
) : ListAdapter<AppWidgetProviderInfo, WidgetProviderAdapter.ViewHolder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_widget_provider, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val provider = getItem(position)
        holder.label.text = provider.loadLabel(packageManager)
        holder.icon.setImageDrawable(provider.loadIcon(holder.itemView.context, 0))
        holder.itemView.setOnClickListener { onProviderClick(provider) }
    }

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val icon: ImageView = itemView.findViewById(R.id.provider_icon)
        val label: TextView = itemView.findViewById(R.id.provider_label)
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<AppWidgetProviderInfo>() {
            override fun areItemsTheSame(oldItem: AppWidgetProviderInfo, newItem: AppWidgetProviderInfo) =
                oldItem.provider == newItem.provider
            override fun areContentsTheSame(oldItem: AppWidgetProviderInfo, newItem: AppWidgetProviderInfo) =
                oldItem.provider == newItem.provider
        }
    }
}
