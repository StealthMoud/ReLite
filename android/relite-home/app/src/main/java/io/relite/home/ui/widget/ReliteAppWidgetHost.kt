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

    /**
     * Section 10 (v0.4.1): [AppWidgetManager.getAppWidgetInfo] can return
     * null for a binding whose provider was uninstalled, or whose id was
     * never actually bound (host state surviving a provider that vanished
     * between sessions). Blindly calling [createView] with a null info used
     * to hand back a view with nothing meaningful to render; the caller now
     * gets an explicit null and is expected to show a removable placeholder
     * instead of a live host view.
     */
    fun bindWidgetView(appWidgetId: Int): AppWidgetHostView? {
        val info: AppWidgetProviderInfo? = widgetManager.getAppWidgetInfo(appWidgetId)
        if (info == null) return null
        return createView(appContext, appWidgetId, info)
    }

    fun removeWidget(appWidgetId: Int) {
        deleteAppWidgetId(appWidgetId)
    }

    /**
     * Section 13 (v0.4.1): after a persisted resize, the provider must be
     * told its real rendered size so remote-view content (e.g. a calendar
     * widget showing more/fewer rows) can adapt — a purely visual grid-cell
     * resize with no provider notification left providers rendering their
     * old layout until the next unrelated rebind.
     */
    @Suppress("DEPRECATION") // minSdk 26; the List<SizeF> overload requires API 31.
    fun notifyResized(hostView: AppWidgetHostView, widthDp: Int, heightDp: Int) {
        hostView.updateAppWidgetSize(android.os.Bundle(), widthDp, heightDp, widthDp, heightDp)
    }

    companion object {
        // Arbitrary but stable per-app host id, distinct from AOSP Launcher3's
        // reserved range, so ReLite Home can coexist during side-by-side testing.
        private const val HOST_ID = 1027
    }
}
