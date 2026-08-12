package io.relite.home.ui.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import io.relite.home.data.PortableWidget
import io.relite.home.data.WorkspaceController

/**
 * Section 65-66 (v0.5.0 completion pass): rebinds the [PortableWidget]
 * descriptors a layout import produced, closing the last piece of the
 * "export drops widgets entirely" gap.
 *
 * Rebinding is genuinely best-effort, and this class is built to *report*
 * that rather than paper over it. Four things can each independently stop
 * a given descriptor from coming back, and a real import routinely hits
 * more than one:
 *
 * 1. **Provider not installed.** The exporting device had the widget's app;
 *    this one may not. Nothing to bind to.
 * 2. **Bind not permitted — in practice, the normal case.** Granting a host
 *    the right to bind a widget id is a user decision.
 *    [AppWidgetManager.bindAppWidgetIdIfAllowed] succeeds silently only
 *    where this launcher already holds that grant; otherwise the system's
 *    own `ACTION_APPWIDGET_BIND` consent dialog has to run, per widget.
 *    `BIND_APPWIDGET` itself is `signature|privileged`, so an ordinary
 *    third-party launcher can never hold it (which is why the manifest
 *    doesn't declare it — see AndroidManifest.xml).
 *
 *    Measured on the RMX5303 rather than assumed: the silent bind fails
 *    **even for a provider in this app's own package**. There is no
 *    same-package exemption. So on any normal install the silent pass
 *    places nothing and essentially every imported widget reaches the user
 *    through the consent queue — [restoreAll] is the fast path for the rare
 *    already-granted device, not the main one. This class does the silent
 *    attempt only and hands the rest back to its caller, which owns the
 *    Activity needed to show consent UI.
 * 3. **No room.** The descriptor's recorded rectangle may already be
 *    occupied — the importing layout can differ from the exporting one once
 *    apps missing on this device have been dropped and the grid preset
 *    applied. [WorkspaceController.addWidget] validates before committing,
 *    so a collision fails cleanly instead of corrupting the layout.
 * 4. **Configuration lost.** A provider with a `configure` Activity stores
 *    its settings against the *old* `appWidgetId`, which did not survive
 *    the export. Such a widget rebinds to a fresh id and therefore comes
 *    back at its correct place and size but **unconfigured**. It is
 *    reported separately rather than counted as a clean restore, because
 *    to the user it visibly is not one.
 *
 * Every allocated id is released on any failure path, so a partial restore
 * never leaks host state (the same discipline [WidgetPickerActivity]'s
 * cancellation matrix follows).
 */
object WidgetRestorer {

    /** Why a single descriptor did not come back. See the class kdoc. */
    enum class SkipReason {
        PROVIDER_NOT_INSTALLED,
        BIND_NOT_PERMITTED,
        NO_ROOM,
    }

    data class Skipped(val widget: PortableWidget, val reason: SkipReason)

    data class Result(
        /** Fully restored: correct provider, place, size — and nothing to configure. */
        val restored: List<PortableWidget> = emptyList(),
        /** Restored at the right place/size, but its configuration could not travel. */
        val restoredUnconfigured: List<PortableWidget> = emptyList(),
        val skipped: List<Skipped> = emptyList(),
    ) {
        val totalRestored: Int get() = restored.size + restoredUnconfigured.size

        /** Descriptors that only failed for lack of user consent — the set worth re-offering interactively. */
        val awaitingConsent: List<PortableWidget>
            get() = skipped.filter { it.reason == SkipReason.BIND_NOT_PERMITTED }.map { it.widget }

        /**
         * Accumulates a later single-widget outcome onto a running tally —
         * how the caller's per-widget consent queue folds each answer back
         * into one final report.
         */
        fun merge(other: Result): Result = Result(
            restored = restored + other.restored,
            restoredUnconfigured = restoredUnconfigured + other.restoredUnconfigured,
            skipped = skipped + other.skipped,
        )

        /**
         * Writes this running tally into saved-instance-state, so a restore
         * interrupted by Activity recreation resumes with everything already
         * decided still counted — see [PortableWidget.flatten].
         */
        fun saveTo(outState: android.os.Bundle, keyPrefix: String) {
            outState.putStringArrayList(keyPrefix + KEY_RESTORED, ArrayList(restored.map { it.flatten() }))
            outState.putStringArrayList(keyPrefix + KEY_UNCONFIGURED, ArrayList(restoredUnconfigured.map { it.flatten() }))
            outState.putStringArrayList(
                keyPrefix + KEY_SKIPPED,
                ArrayList(skipped.map { "${it.reason.name}@${it.widget.flatten()}" }),
            )
        }

        companion object {
            private const val KEY_RESTORED = "restored"
            private const val KEY_UNCONFIGURED = "unconfigured"
            private const val KEY_SKIPPED = "skipped"

            /** Inverse of [saveTo]; an absent or unreadable entry simply contributes nothing. */
            fun restoreFrom(state: android.os.Bundle, keyPrefix: String): Result = Result(
                restored = state.getStringArrayList(keyPrefix + KEY_RESTORED)
                    .orEmpty().mapNotNull { PortableWidget.unflatten(it) },
                restoredUnconfigured = state.getStringArrayList(keyPrefix + KEY_UNCONFIGURED)
                    .orEmpty().mapNotNull { PortableWidget.unflatten(it) },
                skipped = state.getStringArrayList(keyPrefix + KEY_SKIPPED).orEmpty().mapNotNull { entry ->
                    val reasonName = entry.substringBefore('@', missingDelimiterValue = "")
                    val reason = SkipReason.entries.firstOrNull { it.name == reasonName } ?: return@mapNotNull null
                    val widget = PortableWidget.unflatten(entry.substringAfter('@')) ?: return@mapNotNull null
                    Skipped(widget, reason)
                },
            )
        }
    }

    /**
     * Attempts every descriptor once, silently. Must be called *after* the
     * imported workspace has been committed, since each successful rebind
     * mutates that now-current workspace.
     */
    fun restoreAll(
        context: Context,
        host: ReliteAppWidgetHost,
        workspaceController: WorkspaceController,
        widgets: List<PortableWidget>,
    ): Result {
        if (widgets.isEmpty()) return Result()

        val widgetManager = AppWidgetManager.getInstance(context)
        val installed = widgetManager.installedProviders.associateBy { it.provider.flattenToString() }

        val restored = mutableListOf<PortableWidget>()
        val restoredUnconfigured = mutableListOf<PortableWidget>()
        val skipped = mutableListOf<Skipped>()

        for (widget in widgets) {
            val provider = installed[widget.providerComponent]
            if (provider == null) {
                skipped += Skipped(widget, SkipReason.PROVIDER_NOT_INSTALLED)
                continue
            }
            when (val outcome = bindAndPlace(widgetManager, host, workspaceController, widget, provider)) {
                is Outcome.Placed ->
                    if (outcome.needsConfiguration) restoredUnconfigured += widget else restored += widget
                is Outcome.Failed -> skipped += Skipped(widget, outcome.reason)
            }
        }
        return Result(restored, restoredUnconfigured, skipped)
    }

    /**
     * Binds one descriptor to a fresh id and places it. Public so the
     * caller's interactive consent flow can re-drive exactly this step for
     * a single widget once the user has granted the bind, without
     * duplicating the allocate/bind/place/cleanup ordering.
     *
     * [alreadyBoundId], when non-null, is an id the caller already got
     * consent for — it is placed directly rather than re-allocated.
     */
    fun restoreOne(
        context: Context,
        host: ReliteAppWidgetHost,
        workspaceController: WorkspaceController,
        widget: PortableWidget,
        alreadyBoundId: Int? = null,
    ): Result {
        val widgetManager = AppWidgetManager.getInstance(context)
        val provider = widgetManager.installedProviders.firstOrNull {
            it.provider.flattenToString() == widget.providerComponent
        } ?: return Result(skipped = listOf(Skipped(widget, SkipReason.PROVIDER_NOT_INSTALLED)))

        val outcome = if (alreadyBoundId != null) {
            place(workspaceController, widget, alreadyBoundId, provider)
                .also { if (it is Outcome.Failed) host.removeWidget(alreadyBoundId) }
        } else {
            bindAndPlace(widgetManager, host, workspaceController, widget, provider)
        }

        return when (outcome) {
            is Outcome.Placed ->
                if (outcome.needsConfiguration) Result(restoredUnconfigured = listOf(widget))
                else Result(restored = listOf(widget))
            is Outcome.Failed -> Result(skipped = listOf(Skipped(widget, outcome.reason)))
        }
    }

    /**
     * The bind-consent Intent for one descriptor, paired with the id it must
     * be answered for. Returns null only when the descriptor's provider
     * string cannot be parsed into a component at all.
     */
    fun consentRequest(host: ReliteAppWidgetHost, widget: PortableWidget): Pair<Int, android.content.Intent>? {
        val component = ComponentName.unflattenFromString(widget.providerComponent) ?: return null
        val appWidgetId = host.allocateId()
        val intent = android.content.Intent(AppWidgetManager.ACTION_APPWIDGET_BIND).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, component)
        }
        return appWidgetId to intent
    }

    private sealed class Outcome {
        data class Placed(val needsConfiguration: Boolean) : Outcome()
        data class Failed(val reason: SkipReason) : Outcome()
    }

    private fun bindAndPlace(
        widgetManager: AppWidgetManager,
        host: ReliteAppWidgetHost,
        workspaceController: WorkspaceController,
        widget: PortableWidget,
        provider: AppWidgetProviderInfo,
    ): Outcome {
        val appWidgetId = host.allocateId()
        // A provider that vanishes between installedProviders being read and
        // this call, or an OEM build that refuses the bind outright, throws
        // rather than returning false — both mean "not bound", never a crash.
        val bound = runCatching { widgetManager.bindAppWidgetIdIfAllowed(appWidgetId, provider.provider) }
            .getOrDefault(false)
        if (!bound) {
            host.removeWidget(appWidgetId)
            return Outcome.Failed(SkipReason.BIND_NOT_PERMITTED)
        }
        return place(workspaceController, widget, appWidgetId, provider)
            .also { if (it is Outcome.Failed) host.removeWidget(appWidgetId) }
    }

    private fun place(
        workspaceController: WorkspaceController,
        widget: PortableWidget,
        appWidgetId: Int,
        provider: AppWidgetProviderInfo,
    ): Outcome {
        val itemId = workspaceController.addWidget(
            appWidgetId = appWidgetId,
            spanColumns = widget.spanColumns,
            spanRows = widget.spanRows,
            providerComponent = widget.providerComponent,
            position = widget.position,
        ) ?: return Outcome.Failed(SkipReason.NO_ROOM)
        check(itemId.isNotEmpty())
        return Outcome.Placed(needsConfiguration = provider.configure != null)
    }
}
