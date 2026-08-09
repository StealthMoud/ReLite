package io.relite.home.ui.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.relite.home.R
import io.relite.home.ReliteHomeApplication
import kotlin.math.ceil

/**
 * ReLite Home's own widget provider picker (master plan sections 40-51) —
 * built from [AppWidgetManager.installedProviders] directly rather than the
 * deprecated system [AppWidgetManager.ACTION_APPWIDGET_PICK] dialog, which
 * is unreliable/absent on many modern builds. Runs the full
 * allocate → bind (with permission fallback) → configure → span →
 * placement → persist pipeline, deleting the allocated widget id on any
 * cancellation or failure so nothing leaks (section 58).
 */
class WidgetPickerActivity : AppCompatActivity() {

    private lateinit var app: ReliteHomeApplication
    private lateinit var host: ReliteAppWidgetHost
    private var pendingAppWidgetId: Int = AppWidgetManager.INVALID_APPWIDGET_ID

    private val bindLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val provider = pendingProvider
        if (result.resultCode == RESULT_OK && provider != null) {
            proceedToConfigureOrFinish(pendingAppWidgetId, provider)
        } else {
            cancelPending()
        }
    }

    private val configureLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val provider = pendingProvider
        if (result.resultCode == RESULT_OK && provider != null) {
            finishSuccess(pendingAppWidgetId, provider)
        } else {
            cancelPending()
        }
    }

    private var pendingProvider: AppWidgetProviderInfo? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = application as ReliteHomeApplication
        host = app.appWidgetHost // Section 40: reuse the single process-scoped host, never a new one.
        setContentView(R.layout.activity_widget_picker)

        val providers = AppWidgetManager.getInstance(this).installedProviders
            .sortedBy { it.loadLabel(packageManager).lowercase() }

        findViewById<RecyclerView>(R.id.provider_recycler).apply {
            layoutManager = LinearLayoutManager(this@WidgetPickerActivity)
            adapter = WidgetProviderAdapter(packageManager) { provider -> onProviderSelected(provider) }
        }

        if (providers.isEmpty()) {
            Toast.makeText(this, R.string.no_widgets_available, Toast.LENGTH_SHORT).show()
        }
    }

    private fun onProviderSelected(provider: AppWidgetProviderInfo) {
        val appWidgetId = host.allocateId()
        pendingAppWidgetId = appWidgetId
        pendingProvider = provider

        val widgetManager = AppWidgetManager.getInstance(this)
        val alreadyBound = widgetManager.bindAppWidgetIdIfAllowed(appWidgetId, provider.provider)
        if (alreadyBound) {
            proceedToConfigureOrFinish(appWidgetId, provider)
        } else {
            val bindIntent = Intent(AppWidgetManager.ACTION_APPWIDGET_BIND).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, provider.provider)
            }
            bindLauncher.launch(bindIntent)
        }
    }

    private fun proceedToConfigureOrFinish(appWidgetId: Int, provider: AppWidgetProviderInfo) {
        val configure = provider.configure
        if (configure == null) {
            finishSuccess(appWidgetId, provider)
            return
        }
        val configureIntent = Intent(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE).apply {
            component = configure
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        }
        configureLauncher.launch(configureIntent)
    }

    /** Section 48: derive an initial span from the provider's declared minimum size, clamped to the grid. */
    private fun finishSuccess(appWidgetId: Int, provider: AppWidgetProviderInfo) {
        val gridSpec = app.workspaceController.gridSpec
        val density = resources.displayMetrics.density
        val cellSizePx = resources.displayMetrics.widthPixels / gridSpec.columns
        val spanColumns = ceil(provider.minWidth * density / cellSizePx).toInt().coerceIn(1, gridSpec.columns)
        val spanRows = ceil(provider.minHeight * density / cellSizePx).toInt().coerceIn(1, gridSpec.rows)

        val itemId = app.workspaceController.addWidget(
            appWidgetId = appWidgetId,
            spanColumns = spanColumns,
            spanRows = spanRows,
            providerComponent = provider.provider.flattenToString(),
        )
        if (itemId == null) {
            host.removeWidget(appWidgetId)
            Toast.makeText(this, R.string.workspace_full, Toast.LENGTH_SHORT).show()
            setResult(RESULT_CANCELED)
        } else {
            setResult(RESULT_OK)
        }
        finish()
    }

    private fun cancelPending() {
        if (pendingAppWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            host.removeWidget(pendingAppWidgetId)
        }
        pendingAppWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
        pendingProvider = null
        // Cancelling one provider's bind/configure step returns to the picker
        // list, not straight back to the workspace — the user may want to
        // try a different widget rather than abandon the flow entirely.
    }

    companion object {
        fun newIntent(activity: Activity): Intent = Intent(activity, WidgetPickerActivity::class.java)
    }
}
