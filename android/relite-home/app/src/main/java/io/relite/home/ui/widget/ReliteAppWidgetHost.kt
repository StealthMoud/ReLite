package io.relite.home.ui.widget

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context

/**
 * ReLite Home's AppWidgetHost. A single fixed host ID is used across the
 * app's lifetime — widgets are added/resized/removed via [addWidget] /
 * [removeWidget], and their state (appWidgetId + span) is persisted in
 * WorkspaceItem.WidgetIcon by the caller, not here.
 */
class ReliteAppWidgetHost(context: Context) : AppWidgetHost(context, HOST_ID) {

    private val appContext = context.applicationContext
    private val widgetManager = AppWidgetManager.getInstance(appContext)

    fun allocateId(): Int = allocateAppWidgetId()

    fun bindWidgetView(appWidgetId: Int): AppWidgetHostView {
        val info: AppWidgetProviderInfo? = widgetManager.getAppWidgetInfo(appWidgetId)
        return createView(appContext, appWidgetId, info)
    }

    fun removeWidget(appWidgetId: Int) {
        deleteAppWidgetId(appWidgetId)
    }

    companion object {
        // Arbitrary but stable per-app host id, distinct from AOSP Launcher3's
        // reserved range, so ReLite Home can coexist during side-by-side testing.
        private const val HOST_ID = 1027
    }
}
