package io.relite.home.ui.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import io.relite.home.ReliteHomeApplication

/**
 * Thin trampoline around the platform's widget-pick + bind-permission flow.
 * MainActivity starts this for a result and reads back RESULT_APPWIDGET_ID.
 */
class WidgetPickerActivity : Activity() {

    private lateinit var host: ReliteAppWidgetHost
    private var appWidgetId: Int = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        host = ReliteAppWidgetHost(applicationContext)
        appWidgetId = host.allocateId()

        val pickIntent = Intent(AppWidgetManager.ACTION_APPWIDGET_PICK).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        }
        startActivityForResult(pickIntent, REQUEST_PICK_APPWIDGET)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQUEST_PICK_APPWIDGET && resultCode == RESULT_OK) {
            val result = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            setResult(RESULT_OK, result)
        } else {
            host.removeWidget(appWidgetId)
            setResult(RESULT_CANCELED)
        }
        finish()
    }

    companion object {
        private const val REQUEST_PICK_APPWIDGET = 1
    }
}
