package io.relite.home.ui.widget

import android.appwidget.AppWidgetProviderInfo
import android.graphics.drawable.Drawable

/** One row in the grouped widget picker — a source-app header or one of that app's provider cards. */
sealed class WidgetPickerRow {
    data class AppHeader(val packageName: String, val label: String, val icon: Drawable?) : WidgetPickerRow()
    data class ProviderCard(val info: AppWidgetProviderInfo) : WidgetPickerRow()
}
