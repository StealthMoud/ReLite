package io.relite.home.ui.home

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.Menu
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupMenu
import io.relite.home.R
import io.relite.home.data.AppEntry
import io.relite.home.util.IconCache
import kotlin.math.abs

/**
 * Fixed row of shortcuts anchored to the bottom of the screen, plus a
 * dedicated "open app drawer" button — the One UI-inspired one-handed
 * reach affordance (master plan section 19/20), built with plain Views.
 *
 * Section 26-27 (v0.4.0): dock is a first-class editable surface — long
 * press offers Remove from Dock / App info, and a long-press-then-drag
 * horizontally reorders shortcuts, committed via [onReorder] only on a
 * valid drop (mirroring [HomePageFragment]'s same-page drag semantics).
 */
class WorkspaceDockView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    private var componentKeys: List<String> = emptyList()
    private val touchSlop by lazy { ViewConfiguration.get(context).scaledTouchSlop }

    init {
        orientation = HORIZONTAL
    }

    fun bind(
        componentKeys: List<String>,
        allApps: Map<String, AppEntry>,
        iconCache: IconCache,
        onAppClick: (AppEntry) -> Unit,
        onAppsButtonClick: () -> Unit,
        onRemoveFromDock: (String) -> Unit = {},
        onAppInfo: (String) -> Unit = {},
        onReorder: (List<String>) -> Unit = {},
    ) {
        this.componentKeys = componentKeys
        removeAllViews()
        val inflater = LayoutInflater.from(context)

        for (key in componentKeys) {
            val app = allApps[key] ?: continue
            val button = inflater.inflate(R.layout.item_dock_icon, this, false) as ImageButton
            button.setImageDrawable(iconCache.get(app.packageName, app.activityName))
            button.contentDescription = app.label
            button.setOnClickListener { onAppClick(app) }
            wireDockInteractions(button, key, onRemoveFromDock, onAppInfo, onReorder)
            addView(button)
        }

        val appsButton = inflater.inflate(R.layout.item_dock_apps_button, this, false) as ImageButton
        appsButton.setOnClickListener { onAppsButtonClick() }
        addView(appsButton)
    }

    private fun wireDockInteractions(
        button: ImageButton,
        componentKey: String,
        onRemoveFromDock: (String) -> Unit,
        onAppInfo: (String) -> Unit,
        onReorder: (List<String>) -> Unit,
    ) {
        var dragArmed = false
        var downRawX = 0f
        var dragStartOrder = componentKeys

        button.setOnLongClickListener {
            dragArmed = true
            dragStartOrder = componentKeys
            button.animate().scaleX(1.15f).scaleY(1.15f).alpha(0.85f).setDuration(120).start()
            true
        }
        button.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragArmed = false
                    downRawX = event.rawX
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (dragArmed) {
                        v.translationX = event.rawX - downRawX
                        updateHoverOrder(v, componentKey, dragStartOrder)
                        true
                    } else {
                        false
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragArmed) {
                        dragArmed = false
                        finishDockDrag(v, componentKey, onRemoveFromDock, onAppInfo, onReorder)
                        true
                    } else {
                        false
                    }
                }
                else -> false
            }
        }
    }

    /**
     * Recomputes [componentKeys] fresh from [dragStartOrder] on every move
     * event, rather than mutating incrementally — physical child views never
     * move or rebuild mid-gesture (only the dragged view translates), so
     * `getChildAt(i)` always corresponds to `dragStartOrder[i]`, and
     * recomputing from that fixed baseline avoids compounding index drift
     * that an incremental remove/add approach would accumulate.
     */
    private fun updateHoverOrder(dragged: View, componentKey: String, dragStartOrder: List<String>) {
        val fromIndex = dragStartOrder.indexOf(componentKey)
        if (fromIndex < 0) return
        val draggedCenterX = dragged.left + dragged.translationX + dragged.width / 2f

        var targetIndex = fromIndex
        for (i in 0 until minOf(childCount, dragStartOrder.size)) {
            val child = getChildAt(i)
            if (child === dragged) continue
            val childCenterX = child.left + child.width / 2f
            if (draggedCenterX > childCenterX) targetIndex = i
        }
        componentKeys = dragStartOrder.toMutableList().apply {
            removeAt(fromIndex)
            add(targetIndex, componentKey)
        }
    }

    private fun finishDockDrag(
        v: View,
        componentKey: String,
        onRemoveFromDock: (String) -> Unit,
        onAppInfo: (String) -> Unit,
        onReorder: (List<String>) -> Unit,
    ) {
        v.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
        val moved = abs(v.translationX) > touchSlop
        v.translationX = 0f
        v.alpha = 1f
        if (!moved) {
            showLongPressMenu(v, componentKey, onRemoveFromDock, onAppInfo)
            return
        }
        onReorder(componentKeys)
    }

    private fun showLongPressMenu(
        anchor: View,
        componentKey: String,
        onRemoveFromDock: (String) -> Unit,
        onAppInfo: (String) -> Unit,
    ) {
        PopupMenu(context, anchor).apply {
            menu.add(Menu.NONE, MENU_ID_REMOVE_FROM_DOCK, Menu.NONE, R.string.action_remove_from_dock)
            menu.add(Menu.NONE, MENU_ID_APP_INFO, Menu.NONE, R.string.action_app_info)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    MENU_ID_REMOVE_FROM_DOCK -> {
                        onRemoveFromDock(componentKey)
                        true
                    }
                    MENU_ID_APP_INFO -> {
                        onAppInfo(componentKey.substringBefore("/"))
                        true
                    }
                    else -> false
                }
            }
        }.show()
    }

    companion object {
        private const val MENU_ID_REMOVE_FROM_DOCK = 1
        private const val MENU_ID_APP_INFO = 2
    }
}
