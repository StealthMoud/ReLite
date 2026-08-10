package io.relite.home.ui.settings

import android.app.AlertDialog
import android.app.role.RoleManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.RadioGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import io.relite.home.R
import io.relite.home.ReliteHomeApplication
import io.relite.home.data.Workspace
import io.relite.home.data.WorkspaceRepository
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

        findViewById<android.widget.Button>(R.id.settings_export).setOnClickListener {
            exportLauncher.launch(getString(R.string.export_layout_filename))
        }
        findViewById<android.widget.Button>(R.id.settings_import).setOnClickListener {
            importLauncher.launch(arrayOf("application/json"))
        }
        findViewById<android.widget.Button>(R.id.settings_reset).setOnClickListener { confirmReset() }
        findViewById<android.widget.Button>(R.id.settings_default_launcher).setOnClickListener { openDefaultLauncherHelper() }
        findViewById<android.widget.Button>(R.id.settings_about).setOnClickListener { showAbout() }

        setUpThemePicker()
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

    private fun exportTo(uri: Uri) {
        val json = app.workspaceRepository.exportPortable(app.workspaceController.current())
        val ok = try {
            contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
            true
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
            .setMessage(getString(R.string.import_confirm_message, appCount, folderCount, result.missingApps.size))
            .setPositiveButton(R.string.ok) { _, _ ->
                app.workspaceController.replaceWorkspace(result.candidate)
                Toast.makeText(this, R.string.import_success, Toast.LENGTH_SHORT).show()
                finish()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmReset() {
        AlertDialog.Builder(this)
            .setTitle(R.string.reset_confirm_title)
            .setMessage(R.string.reset_confirm_message)
            .setPositiveButton(R.string.ok) { _, _ ->
                app.workspaceController.replaceWorkspace(Workspace.empty())
                Toast.makeText(this, R.string.reset_success, Toast.LENGTH_SHORT).show()
                finish()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
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
            .show()
    }
}
