package io.relite.home.ui.home

import android.content.Context
import android.content.pm.LauncherApps
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.ImageButton
import android.widget.LinearLayout
import io.relite.home.R
import io.relite.home.data.AppEntry
import io.relite.home.util.IconCache

/**
 * Fixed row of shortcuts anchored to the bottom of the screen, plus a
 * dedicated "open app drawer" button — the One UI-inspired one-handed
 * reach affordance (master plan section 19/20), built with plain Views.
 */
class WorkspaceDockView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    init {
        orientation = HORIZONTAL
    }

    fun bind(
        componentKeys: List<String>,
        allApps: Map<String, AppEntry>,
        launcherApps: LauncherApps,
        onAppClick: (AppEntry) -> Unit,
        onAppsButtonClick: () -> Unit,
    ) {
        removeAllViews()
        val iconCache = IconCache(launcherApps)
        val inflater = LayoutInflater.from(context)

        for (key in componentKeys) {
            val app = allApps[key] ?: continue
            val button = inflater.inflate(R.layout.item_dock_icon, this, false) as ImageButton
            button.setImageDrawable(iconCache.get(app.packageName, app.activityName))
            button.contentDescription = app.label
            button.setOnClickListener { onAppClick(app) }
            addView(button)
        }

        val appsButton = inflater.inflate(R.layout.item_dock_apps_button, this, false) as ImageButton
        appsButton.setOnClickListener { onAppsButtonClick() }
        addView(appsButton)
    }
}
