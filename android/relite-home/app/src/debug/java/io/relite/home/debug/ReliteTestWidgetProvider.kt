package io.relite.home.debug

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.os.Bundle
import android.widget.RemoteViews
import io.relite.home.R

/**
 * Section 8 (v0.5.0 completion pass): a debug-only, fully deterministic
 * widget provider for exercising ReLite Home's own add/bind/place/resize/
 * remove pipeline end-to-end without depending on a third-party provider
 * that may not be installed (or may render non-deterministic content) on
 * every test device or CI runner. Lives entirely under `src/debug` —
 * excluded from release builds by AGP's default source-set convention, no
 * manual build-variant gating needed anywhere else in the codebase.
 *
 * `updatePeriodMillis="0"` in `widget_info_relite_test.xml` means the only
 * thing that ever redraws this widget's content is a real resize
 * ([onAppWidgetOptionsChanged]) — the min-width/min-height it renders comes
 * straight from the [AppWidgetManager]-supplied option bundle, so a test
 * (or a person live-verifying a resize gesture) can read the widget's own
 * on-screen text to confirm the new size actually reached the provider,
 * not just that ReLite Home's own grid model changed.
 */
class ReliteTestWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { id ->
            appWidgetManager.updateAppWidget(id, buildViews(context, appWidgetManager.getAppWidgetOptions(id)))
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle,
    ) {
        appWidgetManager.updateAppWidget(appWidgetId, buildViews(context, newOptions))
    }

    private fun buildViews(context: Context, options: Bundle): RemoteViews {
        val minWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, -1)
        val minHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, -1)
        return RemoteViews(context.packageName, R.layout.widget_relite_test).apply {
            setTextViewText(R.id.test_widget_size_label, "${minWidth}x${minHeight}dp")
        }
    }
}
