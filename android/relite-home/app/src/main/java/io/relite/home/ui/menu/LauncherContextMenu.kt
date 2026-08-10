package io.relite.home.ui.menu

import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import androidx.core.content.ContextCompat
import io.relite.home.R

/** One row in a [LauncherContextMenu] — [id] is what the caller's `onSelected` switches on, never the displayed label. */
data class LauncherAction(val id: Int, val label: String)

/**
 * Sections 106-111 (v0.5.0): a shared, Samsung-like rounded-card context
 * menu, replacing the generic system [android.widget.PopupMenu] used
 * throughout the launcher (Home item long-press, Apps screen long-press,
 * dock long-press, folder member long-press) — same rounded-surface
 * language as the dock/folder ([R.drawable.bg_context_menu]), same
 * 48dp-minimum touch targets as everywhere else in this launcher.
 */
object LauncherContextMenu {

    fun show(anchor: View, actions: List<LauncherAction>, onSelected: (Int) -> Unit) {
        val context = anchor.context
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(context, R.drawable.bg_context_menu)
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        val rowHeight = context.resources.getDimensionPixelSize(R.dimen.touch_target_min)
        val horizontalPadding = context.resources.getDimensionPixelSize(R.dimen.context_menu_padding_horizontal)
        val selectableBackground = TypedValue().also {
            context.theme.resolveAttribute(android.R.attr.selectableItemBackground, it, true)
        }.resourceId

        val popup = PopupWindow(container, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
            isOutsideTouchable = true
            elevation = context.resources.getDimension(R.dimen.context_menu_elevation)
        }

        for (action in actions) {
            val row = TextView(context).apply {
                text = action.label
                setTextColor(ContextCompat.getColor(context, R.color.relite_text_primary))
                textSize = 15f
                gravity = Gravity.CENTER_VERTICAL
                setPadding(horizontalPadding, 0, horizontalPadding, 0)
                isClickable = true
                isFocusable = true
                setBackgroundResource(selectableBackground)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, rowHeight)
                setOnClickListener {
                    popup.dismiss()
                    onSelected(action.id)
                }
            }
            container.addView(row)
        }

        popup.showAsDropDown(anchor)
    }
}
