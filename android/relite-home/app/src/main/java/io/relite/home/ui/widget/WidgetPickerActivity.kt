package io.relite.home.ui.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.view.View
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

    // Section 7: pending flow state must survive Activity recreation (system
    // bind/configure Activities can and do recreate the caller on low-memory
    // devices). AppWidgetProviderInfo isn't Parcelable-stable across all API
    // levels for our purposes, so the provider component is persisted and
    // re-resolved from AppWidgetManager on restore rather than the object itself.
    private var pendingProviderComponent: ComponentName? = null
    private val pendingProvider: AppWidgetProviderInfo?
        get() {
            val component = pendingProviderComponent ?: return null
            return AppWidgetManager.getInstance(this).installedProviders
                .firstOrNull { it.provider == component }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = application as ReliteHomeApplication
        host = app.appWidgetHost // Section 40: reuse the single process-scoped host, never a new one.
        setContentView(R.layout.activity_widget_picker)

        if (savedInstanceState != null) {
            pendingAppWidgetId = savedInstanceState.getInt(STATE_PENDING_WIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            val componentString = savedInstanceState.getString(STATE_PENDING_PROVIDER)
            pendingProviderComponent = componentString?.let { ComponentName.unflattenFromString(it) }
        }

        val providers = AppWidgetManager.getInstance(this).installedProviders
            .sortedBy { it.loadLabel(packageManager).lowercase() }

        val recycler = findViewById<RecyclerView>(R.id.provider_recycler)
        val emptyState = findViewById<View>(R.id.empty_state)
        val adapter = WidgetProviderAdapter(packageManager) { provider -> onProviderSelected(provider) }
        recycler.layoutManager = LinearLayoutManager(this@WidgetPickerActivity)
        recycler.adapter = adapter
        adapter.submitList(providers)

        findViewById<View>(R.id.empty_state_close).setOnClickListener { finish() }

        if (providers.isEmpty()) {
            recycler.visibility = View.GONE
            emptyState.visibility = View.VISIBLE
        } else {
            recycler.visibility = View.VISIBLE
            emptyState.visibility = View.GONE
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_PENDING_WIDGET_ID, pendingAppWidgetId)
        outState.putString(STATE_PENDING_PROVIDER, pendingProviderComponent?.flattenToString())
    }

    private fun onProviderSelected(provider: AppWidgetProviderInfo) {
        val appWidgetId = host.allocateId()
        pendingAppWidgetId = appWidgetId
        pendingProviderComponent = provider.provider

        val widgetManager = AppWidgetManager.getInstance(this)
        val alreadyBound = widgetManager.bindAppWidgetIdIfAllowed(appWidgetId, provider.provider)
        if (alreadyBound) {
            proceedToConfigureOrFinish(appWidgetId, provider)
        } else {
            val bindIntent = Intent(AppWidgetManager.ACTION_APPWIDGET_BIND).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, provider.provider)
            }
            // Section 9 (v0.5.0 completion pass): a provider that vanishes
            // (uninstalled) between being listed and being tapped, or a
            // device/OEM build with no bind-permission UI at all to launch,
            // must fall back to a clean cancel rather than crash the whole
            // Activity out from under the user with an uncaught
            // ActivityNotFoundException/SecurityException — completing the
            // cancellation-matrix branch the master plan calls "provider
            // missing".
            runCatching { bindLauncher.launch(bindIntent) }.onFailure { cancelPending() }
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
        // Same "provider vanished/no handler" defense as onProviderSelected —
        // a configure Activity that can no longer be resolved must cancel
        // cleanly, not crash.
        runCatching { configureLauncher.launch(configureIntent) }.onFailure { cancelPending() }
    }

    /**
     * Section 41-42/48 (v0.5.0): derive an initial span from the provider's
     * declared minimum size against the grid's *real* measured (non-square)
     * cell rectangle, recorded by the last Home page to lay out
     * ([ReliteHomeApplication.lastGridMetrics]) — falling back to the old
     * width-derived square-cell approximation only in the practically
     * unreachable case that Home has never laid out a grid in this process.
     */
    private fun finishSuccess(appWidgetId: Int, provider: AppWidgetProviderInfo) {
        val gridSpec = app.workspaceController.gridSpec
        val density = resources.displayMetrics.density
        val metrics = app.lastGridMetrics
        val cellWidthPx = metrics?.cellWidthPx ?: (resources.displayMetrics.widthPixels / gridSpec.columns)
        val cellHeightPx = metrics?.cellHeightPx ?: cellWidthPx
        val spanColumns = ceil(provider.minWidth * density / cellWidthPx).toInt().coerceIn(1, gridSpec.columns)
        val spanRows = ceil(provider.minHeight * density / cellHeightPx).toInt().coerceIn(1, gridSpec.rows)

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
        pendingProviderComponent = null
        // Cancelling one provider's bind/configure step returns to the picker
        // list, not straight back to the workspace — the user may want to
        // try a different widget rather than abandon the flow entirely.
    }

    companion object {
        private const val STATE_PENDING_WIDGET_ID = "pending_widget_id"
        private const val STATE_PENDING_PROVIDER = "pending_provider"

        fun newIntent(activity: Activity): Intent = Intent(activity, WidgetPickerActivity::class.java)
    }
}
