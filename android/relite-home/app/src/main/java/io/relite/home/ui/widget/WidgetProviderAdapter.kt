package io.relite.home.ui.widget

import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import io.relite.home.R

/**
 * Section 10 (v0.5.0 completion pass): a Samsung-like grouped widget picker
 * — a header per source app (icon + label) followed by that app's widgets
 * as real preview cards, replacing the previous flat icon+label list. Each
 * card renders the provider's own declared preview image where one exists
 * (see [loadPreviewDrawable]'s kdoc for the icon+dimension fallback when it
 * doesn't) rather than just its launcher icon.
 */
class WidgetProviderAdapter(
    private val packageManager: PackageManager,
    private val onProviderClick: (AppWidgetProviderInfo) -> Unit,
) : ListAdapter<WidgetPickerRow, RecyclerView.ViewHolder>(DIFF) {

    override fun getItemViewType(position: Int): Int = when (getItem(position)) {
        is WidgetPickerRow.AppHeader -> VIEW_TYPE_HEADER
        is WidgetPickerRow.ProviderCard -> VIEW_TYPE_CARD
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder = when (viewType) {
        VIEW_TYPE_HEADER -> HeaderViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_widget_provider_header, parent, false))
        else -> CardViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_widget_provider_card, parent, false))
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = getItem(position)) {
            is WidgetPickerRow.AppHeader -> {
                holder as HeaderViewHolder
                holder.icon.setImageDrawable(row.icon)
                holder.label.text = row.label
            }
            is WidgetPickerRow.ProviderCard -> {
                holder as CardViewHolder
                val provider = row.info
                val context = holder.itemView.context
                holder.label.text = provider.loadLabel(packageManager)
                holder.dimensions.text = context.getString(R.string.widget_dimensions_dp, provider.minWidth, provider.minHeight)
                holder.preview.setImageDrawable(loadPreviewDrawable(context, provider))
                holder.itemView.setOnClickListener { onProviderClick(provider) }
            }
        }
    }

    /**
     * A real rendered provider preview where one exists — [AppWidgetProviderInfo.loadPreviewImage]
     * (API 31+) or the older `android:previewImage` resource otherwise —
     * falling back to the provider's plain launcher icon (already the only
     * thing the previous flat-list picker ever showed) when neither is
     * declared, which is common for providers that never set a dedicated
     * preview asset.
     */
    private fun loadPreviewDrawable(context: Context, provider: AppWidgetProviderInfo): Drawable? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            provider.loadPreviewImage(context, 0)?.let { return it }
        } else if (provider.previewImage != 0) {
            runCatching {
                packageManager.getDrawable(provider.provider.packageName, provider.previewImage, null)
            }.getOrNull()?.let { return it }
        }
        return provider.loadIcon(context, 0)
    }

    class HeaderViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val icon: ImageView = itemView.findViewById(R.id.header_icon)
        val label: TextView = itemView.findViewById(R.id.header_label)
    }

    class CardViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val preview: ImageView = itemView.findViewById(R.id.provider_preview)
        val label: TextView = itemView.findViewById(R.id.provider_label)
        val dimensions: TextView = itemView.findViewById(R.id.provider_dimensions)
    }

    companion object {
        private const val VIEW_TYPE_HEADER = 0
        private const val VIEW_TYPE_CARD = 1

        private fun keyOf(row: WidgetPickerRow): String = when (row) {
            is WidgetPickerRow.AppHeader -> "header:${row.packageName}"
            is WidgetPickerRow.ProviderCard -> "card:${row.info.provider.flattenToString()}"
        }

        private val DIFF = object : DiffUtil.ItemCallback<WidgetPickerRow>() {
            override fun areItemsTheSame(oldItem: WidgetPickerRow, newItem: WidgetPickerRow) = keyOf(oldItem) == keyOf(newItem)
            override fun areContentsTheSame(oldItem: WidgetPickerRow, newItem: WidgetPickerRow) = oldItem == newItem
        }
    }
}
