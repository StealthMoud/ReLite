package io.relite.home.ui.settings

import io.relite.home.ui.menu.showOneUi
import android.app.AlertDialog
import android.app.role.RoleManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.RadioGroup
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import io.relite.home.R
import io.relite.home.ReliteHomeApplication
import io.relite.home.data.HomeGridPreset
import io.relite.home.data.Workspace
import io.relite.home.data.WorkspaceRepository
import io.relite.home.util.AppsPreference
import io.relite.home.util.AppsSortMode
import io.relite.home.util.HomePreference
import io.relite.home.util.IconSize
import io.relite.home.util.IconSizePreference
import io.relite.home.util.ThemeMode
import io.relite.home.util.ThemePreference

/**
 * Sections 17/60-71: the one settings surface this launcher has — layout
 * export/import, reset, and a default-launcher helper. No account/session
 * state, no network, no telemetry (section 20/147) — every action here is
 * either a local file operation via the Storage Access Framework or a
 * standard system intent.
 */
class HomeSettingsActivity : AppCompatActivity() {

    private lateinit var app: ReliteHomeApplication

    private val exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) exportTo(uri)
    }
    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importFrom(uri)
    }
    private val roleRequestLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        // No follow-up needed either way — the system settings/role prompt
        // itself is the source of truth for whether the role changed.
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = application as ReliteHomeApplication
        setContentView(R.layout.activity_home_settings)

        // Before any ActivityResult callback can fire (they are delivered
        // after onStart), so a consent answer arriving post-recreation finds
        // its in-flight request still there.
        savedInstanceState?.let { restoreWidgetRestoreState(it) }

        findViewById<android.widget.Button>(R.id.settings_export).setOnClickListener {
            exportLauncher.launch(getString(R.string.export_layout_filename))
        }
        findViewById<android.widget.Button>(R.id.settings_import).setOnClickListener {
            importLauncher.launch(arrayOf("application/json"))
        }
        findViewById<android.widget.Button>(R.id.settings_reset).setOnClickListener { confirmReset() }
        findViewById<android.widget.Button>(R.id.settings_default_launcher).setOnClickListener { openDefaultLauncherHelper() }
        findViewById<android.widget.Button>(R.id.settings_about).setOnClickListener { showAbout() }
        findViewById<android.widget.Button>(R.id.settings_apps_sort).setOnClickListener { showAppsSortChooser() }

        setUpThemePicker()
        setUpHomeGridPicker()
        setUpIconSizePicker()
        setUpToggles()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        saveWidgetRestoreState(outState)
    }

    /** Section 10-13 (v0.5.0 completion pass): same tappable-card pattern as the Home grid picker. */
    private fun setUpIconSizePicker() {
        val cards = mapOf(
            IconSize.SMALL to findViewById<TextView>(R.id.settings_icon_size_small),
            IconSize.DEFAULT to findViewById<TextView>(R.id.settings_icon_size_default),
            IconSize.LARGE to findViewById<TextView>(R.id.settings_icon_size_large),
        )

        fun refresh() {
            val current = IconSizePreference.get(this)
            cards.forEach { (size, card) ->
                card.setBackgroundResource(if (size == current) R.drawable.bg_dock_selected else R.drawable.bg_dock)
            }
        }
        refresh()

        cards.forEach { (size, card) ->
            card.setOnClickListener {
                IconSizePreference.set(this, size)
                // Section 12 (v0.5.0 completion pass): every rendered pixel
                // size is now a distinct cache key (see IconCache's kdoc) —
                // a full clear isn't required for correctness, but it keeps
                // the now-unused previous size's bitmaps from sitting in the
                // byte budget until something else evicts them.
                app.iconCache.clear()
                refresh()
                Toast.makeText(this, R.string.settings_icon_size_changed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** Section 55-57/123 (v0.5.0): tappable grid-size cards, reflecting and driving the real persisted Home grid. */
    private fun setUpHomeGridPicker() {
        val card4x6 = findViewById<TextView>(R.id.settings_grid_4x6)
        val card5x6 = findViewById<TextView>(R.id.settings_grid_5x6)

        fun refresh() {
            val current = app.workspaceController.current().homeGrid
            card4x6.setBackgroundResource(if (current == HomeGridPreset.FOUR_BY_SIX) R.drawable.bg_dock_selected else R.drawable.bg_dock)
            card5x6.setBackgroundResource(if (current == HomeGridPreset.FIVE_BY_SIX) R.drawable.bg_dock_selected else R.drawable.bg_dock)
        }
        refresh()

        card4x6.setOnClickListener {
            if (app.workspaceController.changeHomeGrid(HomeGridPreset.FOUR_BY_SIX)) {
                refresh()
                Toast.makeText(this, R.string.settings_home_grid_changed, Toast.LENGTH_SHORT).show()
            }
        }
        card5x6.setOnClickListener {
            if (app.workspaceController.changeHomeGrid(HomeGridPreset.FIVE_BY_SIX)) {
                refresh()
                Toast.makeText(this, R.string.settings_home_grid_changed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** Section 74/78 (v0.5.0): Home Settings toggles — see HomePreference's kdoc for defaults. */
    private fun setUpToggles() {
        val appsButtonSwitch = findViewById<Switch>(R.id.settings_show_apps_button)
        appsButtonSwitch.isChecked = HomePreference.getShowAppsButton(this)
        appsButtonSwitch.setOnCheckedChangeListener { _, checked -> HomePreference.setShowAppsButton(this, checked) }

        val appLabelsSwitch = findViewById<Switch>(R.id.settings_show_app_labels)
        appLabelsSwitch.isChecked = HomePreference.getShowAppLabels(this)
        appLabelsSwitch.setOnCheckedChangeListener { _, checked -> HomePreference.setShowAppLabels(this, checked) }

        val widgetLabelsSwitch = findViewById<Switch>(R.id.settings_show_widget_labels)
        widgetLabelsSwitch.isChecked = HomePreference.getShowWidgetLabels(this)
        // Same pattern as the icon-size picker above: MainActivity.onResume()
        // always calls refreshWorkspace(), which rebuilds every cell view
        // (including widgets) from scratch — no extra notify needed here.
        widgetLabelsSwitch.setOnCheckedChangeListener { _, checked -> HomePreference.setShowWidgetLabels(this, checked) }
    }

    /** Section 90 (v0.5.0): same Sort chooser as the Apps screen's own "More" menu, reachable from Settings too. */
    private fun showAppsSortChooser() {
        val modes = listOf(
            AppsSortMode.CUSTOM to getString(R.string.sort_custom_order),
            AppsSortMode.ALPHABETICAL to getString(R.string.sort_alphabetical_order),
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.action_sort)
            .setItems(modes.map { it.second }.toTypedArray()) { _, which ->
                AppsPreference.setSortMode(this, modes[which].first)
            }
            .showOneUi()
    }

    /** Section 11-16: explicit theme choice, applied immediately (AppCompat recreates this Activity for us). */
    private fun setUpThemePicker() {
        val group = findViewById<RadioGroup>(R.id.settings_theme_group)
        val idForMode = mapOf(
            ThemeMode.SYSTEM to R.id.theme_system,
            ThemeMode.LIGHT to R.id.theme_light,
            ThemeMode.DARK to R.id.theme_dark,
        )
        val modeForId = idForMode.entries.associate { (mode, id) -> id to mode }

        group.check(idForMode.getValue(ThemePreference.get(this)))
        group.setOnCheckedChangeListener { _, checkedId ->
            val mode = modeForId[checkedId] ?: return@setOnCheckedChangeListener
            if (mode != ThemePreference.get(this)) {
                ThemePreference.set(this, mode)
            }
        }
    }

    /**
     * Section 38 (v0.4.1): `openOutputStream(uri)?.use { ... }` reported
     * success even when the stream was null — `?.use` on a null receiver
     * simply evaluates to null without ever throwing, so the surrounding
     * try/catch never saw a failure. A null/failed stream, or an
     * OutputStream.close() failure surfaced by `use`, must both be treated
     * as export failure, not silently swallowed.
     */
    private fun exportTo(uri: Uri) {
        val json = app.workspaceRepository.exportPortable(app.workspaceController.current())
        val ok = try {
            val stream = contentResolver.openOutputStream(uri)
            if (stream == null) {
                false
            } else {
                stream.use { it.write(json.toByteArray()) }
                true
            }
        } catch (e: Exception) {
            false
        }
        Toast.makeText(this, if (ok) R.string.export_success else R.string.export_failed, Toast.LENGTH_SHORT).show()
    }

    private fun importFrom(uri: Uri) {
        val raw = try {
            contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        } catch (e: Exception) {
            null
        }
        if (raw == null) {
            Toast.makeText(this, getString(R.string.import_failed, "could not read file"), Toast.LENGTH_LONG).show()
            return
        }

        val installed = app.appRepository.loadAll().map { it.componentKey }.toSet()
        when (val result = app.workspaceRepository.importPortable(raw, installed)) {
            is WorkspaceRepository.ImportResult.Failure -> {
                Toast.makeText(this, getString(R.string.import_failed, result.reason), Toast.LENGTH_LONG).show()
            }
            is WorkspaceRepository.ImportResult.Success -> confirmImport(result)
        }
    }

    /** Section 64: never replaces the current layout without an explicit confirmation showing what will change. */
    private fun confirmImport(result: WorkspaceRepository.ImportResult.Success) {
        val appCount = result.candidate.items.count { it is io.relite.home.data.WorkspaceItem.AppIcon }
        val folderCount = result.candidate.items.count { it is io.relite.home.data.WorkspaceItem.FolderIcon }
        AlertDialog.Builder(this)
            .setTitle(R.string.import_confirm_title)
            .setMessage(
                getString(
                    R.string.import_confirm_message,
                    appCount,
                    folderCount,
                    result.missingApps.size,
                    result.pendingWidgets.size,
                ),
            )
            .setPositiveButton(R.string.ok) { _, _ ->
                // Section 39 (v0.4.1): only report success when the
                // persisted commit actually succeeded.
                val (ok, removedWidgetIds) = app.workspaceController.replaceWorkspaceSafely(result.candidate)
                if (ok) {
                    // Section 44 (v0.5.0): the *previous* layout's widgets are
                    // orphaned host state once it is replaced, whether or not
                    // the incoming layout brings any of its own.
                    removedWidgetIds.forEach { app.appWidgetHost.removeWidget(it) }
                    // Section 65-66 (v0.5.0 completion pass): restore the
                    // incoming layout's widgets only after its workspace is
                    // committed — each rebind mutates that now-current
                    // workspace, so it cannot run against the candidate.
                    restoreImportedWidgets(result.pendingWidgets)
                } else {
                    Toast.makeText(this, getString(R.string.import_failed, getString(R.string.persistence_failed)), Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .showOneUi()
    }

    // ---- Section 65-66 (v0.5.0 completion pass): imported-widget restore ----

    /**
     * Descriptors that failed only for lack of the system bind consent, and
     * are being re-offered one at a time via [widgetConsentLauncher]. A
     * queue rather than a batch because `ACTION_APPWIDGET_BIND` is inherently
     * per-widget — the system asks about exactly one provider at a time.
     */
    private val widgetConsentQueue = ArrayDeque<io.relite.home.data.PortableWidget>()
    private var widgetConsentInFlight: Pair<Int, io.relite.home.data.PortableWidget>? = null
    private var widgetRestoreTally = io.relite.home.ui.widget.WidgetRestorer.Result()

    /**
     * Section 7/65-66: the whole in-progress restore must survive Activity
     * recreation. The system bind-consent dialog can recreate its caller on
     * a low-memory device — exactly the hazard `WidgetPickerActivity`
     * already guards its own pending flow against — and without this the
     * queue, the in-flight request and the running tally would all be lost:
     * [widgetConsentLauncher] would return early on a null in-flight, the
     * already-allocated widget id would leak, and the remaining widgets
     * would silently never be offered at all.
     */
    private fun saveWidgetRestoreState(outState: Bundle) {
        if (widgetConsentQueue.isEmpty() && widgetConsentInFlight == null) return
        outState.putStringArrayList(
            STATE_WIDGET_CONSENT_QUEUE,
            ArrayList(widgetConsentQueue.map { it.flatten() }),
        )
        widgetConsentInFlight?.let { (appWidgetId, widget) ->
            outState.putInt(STATE_WIDGET_CONSENT_ID, appWidgetId)
            outState.putString(STATE_WIDGET_CONSENT_WIDGET, widget.flatten())
        }
        widgetRestoreTally.saveTo(outState, STATE_WIDGET_TALLY_PREFIX)
    }

    private fun restoreWidgetRestoreState(state: Bundle) {
        val queued = state.getStringArrayList(STATE_WIDGET_CONSENT_QUEUE) ?: return
        widgetConsentQueue.clear()
        widgetConsentQueue.addAll(queued.mapNotNull { io.relite.home.data.PortableWidget.unflatten(it) })
        val inFlightWidget = state.getString(STATE_WIDGET_CONSENT_WIDGET)
            ?.let { io.relite.home.data.PortableWidget.unflatten(it) }
        widgetConsentInFlight = inFlightWidget?.let {
            state.getInt(STATE_WIDGET_CONSENT_ID, android.appwidget.AppWidgetManager.INVALID_APPWIDGET_ID) to it
        }
        widgetRestoreTally =
            io.relite.home.ui.widget.WidgetRestorer.Result.restoreFrom(state, STATE_WIDGET_TALLY_PREFIX)
    }

    private val widgetConsentLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { activityResult ->
        val (appWidgetId, widget) = widgetConsentInFlight ?: return@registerForActivityResult
        widgetConsentInFlight = null
        val outcome = if (activityResult.resultCode == RESULT_OK) {
            io.relite.home.ui.widget.WidgetRestorer.restoreOne(
                context = this,
                host = app.appWidgetHost,
                workspaceController = app.workspaceController,
                widget = widget,
                alreadyBoundId = appWidgetId,
            )
        } else {
            // Consent declined: release the id allocated for the request so
            // a refused restore leaks no host state.
            app.appWidgetHost.removeWidget(appWidgetId)
            io.relite.home.ui.widget.WidgetRestorer.Result(
                skipped = listOf(
                    io.relite.home.ui.widget.WidgetRestorer.Skipped(
                        widget,
                        io.relite.home.ui.widget.WidgetRestorer.SkipReason.BIND_NOT_PERMITTED,
                    ),
                ),
            )
        }
        widgetRestoreTally = widgetRestoreTally.merge(outcome)
        pumpWidgetConsentQueue()
    }

    private fun restoreImportedWidgets(widgets: List<io.relite.home.data.PortableWidget>) {
        if (widgets.isEmpty()) {
            Toast.makeText(this, R.string.import_success, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        // Pass one: everything that can come back with no user interaction.
        val silent = io.relite.home.ui.widget.WidgetRestorer.restoreAll(
            context = this,
            host = app.appWidgetHost,
            workspaceController = app.workspaceController,
            widgets = widgets,
        )
        widgetRestoreTally = silent.copy(
            skipped = silent.skipped.filterNot {
                it.reason == io.relite.home.ui.widget.WidgetRestorer.SkipReason.BIND_NOT_PERMITTED
            },
        )
        // Pass two: ask, per widget, for the ones that only lacked consent.
        widgetConsentQueue.clear()
        widgetConsentQueue.addAll(silent.awaitingConsent)
        pumpWidgetConsentQueue()
    }

    private fun pumpWidgetConsentQueue() {
        val next = widgetConsentQueue.removeFirstOrNull()
        if (next == null) {
            reportWidgetRestore(widgetRestoreTally)
            return
        }
        val request = io.relite.home.ui.widget.WidgetRestorer.consentRequest(app.appWidgetHost, next)
        if (request == null) {
            widgetRestoreTally = widgetRestoreTally.merge(
                io.relite.home.ui.widget.WidgetRestorer.Result(
                    skipped = listOf(
                        io.relite.home.ui.widget.WidgetRestorer.Skipped(
                            next,
                            io.relite.home.ui.widget.WidgetRestorer.SkipReason.PROVIDER_NOT_INSTALLED,
                        ),
                    ),
                ),
            )
            pumpWidgetConsentQueue()
            return
        }
        val (appWidgetId, intent) = request
        widgetConsentInFlight = appWidgetId to next
        // A device with no bind-consent UI to launch at all must degrade to
        // "not restored", never crash the import — the same defense
        // WidgetPickerActivity.onProviderSelected already applies.
        runCatching { widgetConsentLauncher.launch(intent) }.onFailure {
            widgetConsentInFlight = null
            app.appWidgetHost.removeWidget(appWidgetId)
            widgetRestoreTally = widgetRestoreTally.merge(
                io.relite.home.ui.widget.WidgetRestorer.Result(
                    skipped = listOf(
                        io.relite.home.ui.widget.WidgetRestorer.Skipped(
                            next,
                            io.relite.home.ui.widget.WidgetRestorer.SkipReason.BIND_NOT_PERMITTED,
                        ),
                    ),
                ),
            )
            pumpWidgetConsentQueue()
        }
    }

    /**
     * Reports what actually came back, itemised — never a bare "imported"
     * that would let a half-restored layout read as a complete one.
     */
    private fun reportWidgetRestore(result: io.relite.home.ui.widget.WidgetRestorer.Result) {
        if (result.skipped.isEmpty() && result.restoredUnconfigured.isEmpty()) {
            Toast.makeText(this, R.string.import_success, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        val lines = mutableListOf<String>()
        if (result.totalRestored > 0) lines += getString(R.string.widget_restore_restored, result.totalRestored)
        if (result.restoredUnconfigured.isNotEmpty()) {
            lines += getString(R.string.widget_restore_unconfigured, result.restoredUnconfigured.size)
        }
        val byReason = result.skipped.groupBy { it.reason }
        byReason[io.relite.home.ui.widget.WidgetRestorer.SkipReason.PROVIDER_NOT_INSTALLED]?.let {
            lines += getString(R.string.widget_restore_missing_provider, it.size)
        }
        byReason[io.relite.home.ui.widget.WidgetRestorer.SkipReason.BIND_NOT_PERMITTED]?.let {
            lines += getString(R.string.widget_restore_not_permitted, it.size)
        }
        byReason[io.relite.home.ui.widget.WidgetRestorer.SkipReason.NO_ROOM]?.let {
            lines += getString(R.string.widget_restore_no_room, it.size)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.widget_restore_title)
            .setMessage(lines.joinToString("\n"))
            .setPositiveButton(R.string.ok) { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .showOneUi()
    }

    private fun confirmReset() {
        AlertDialog.Builder(this)
            .setTitle(R.string.reset_confirm_title)
            .setMessage(R.string.reset_confirm_message)
            .setPositiveButton(R.string.ok) { _, _ ->
                // Section 40 (v0.4.1): same false-success fix as import.
                val (ok, removedWidgetIds) = app.workspaceController.replaceWorkspaceSafely(Workspace.empty())
                if (ok) {
                    // Section 44 (v0.5.0): every widget the layout had is
                    // now orphaned host state — clean it up, not just the
                    // workspace reference to it.
                    removedWidgetIds.forEach { app.appWidgetHost.removeWidget(it) }
                    Toast.makeText(this, R.string.reset_success, Toast.LENGTH_SHORT).show()
                    finish()
                } else {
                    Toast.makeText(this, R.string.reset_failed, Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .showOneUi()
    }

    private fun openDefaultLauncherHelper() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = getSystemService(RoleManager::class.java)
            if (roleManager != null && roleManager.isRoleAvailable(RoleManager.ROLE_HOME) &&
                !roleManager.isRoleHeld(RoleManager.ROLE_HOME)
            ) {
                roleRequestLauncher.launch(roleManager.createRequestRoleIntent(RoleManager.ROLE_HOME))
                return
            }
        }
        startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
    }

    private fun showAbout() {
        val versionName = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (e: Exception) {
            "?"
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_about)
            .setMessage(getString(R.string.about_message, versionName))
            .setPositiveButton(R.string.ok, null)
            .showOneUi()
    }

    private companion object {
        // Section 7/65-66: saved-instance-state keys for an imported-widget
        // restore interrupted by Activity recreation.
        const val STATE_WIDGET_CONSENT_QUEUE = "widget_consent_queue"
        const val STATE_WIDGET_CONSENT_ID = "widget_consent_id"
        const val STATE_WIDGET_CONSENT_WIDGET = "widget_consent_widget"
        const val STATE_WIDGET_TALLY_PREFIX = "widget_tally_"
    }
}
