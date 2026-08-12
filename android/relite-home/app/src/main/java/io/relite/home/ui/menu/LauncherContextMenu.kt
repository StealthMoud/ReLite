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
        // Real vertical breathing room so a row that *does* grow past the
        // 48dp minimum (large font scale, or a wrapped two-line label) isn't
        // rendered edge-to-edge against the card's rounded border.
        val verticalPadding = context.resources.getDimensionPixelSize(R.dimen.context_menu_padding_vertical)
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
                isClickable = true
                isFocusable = true
                setBackgroundResource(selectableBackground)
                // Accessibility (v0.5.0 completion pass): 48dp is the row's
                // *minimum*, not its fixed height. Pinning the height meant a
                // user at a large system font scale got the label clipped
                // inside a box that could never grow; a minHeight keeps the
                // same touch target at default scale and lets the row grow
                // with the text instead of truncating it.
                minHeight = rowHeight
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
                setPadding(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding)
                setOnClickListener {
                    popup.dismiss()
                    onSelected(action.id)
                }
            }
            container.addView(row)
        }

        // Flip above the anchor when there isn't room below it.
        //
        // A real bug seen on the RMX5303: a plain showAsDropDown always
        // places the menu *under* its anchor, so opening it from anything
        // near the bottom of the screen — the Apps screen's bottom "More
        // options" button, or a long-press on a dock icon — pushed the card
        // off the bottom edge, mostly or entirely out of view. Measuring the
        // card first and offsetting by its own height puts it above the
        // anchor instead, which is also what the anchor's own position
        // implies the user expects.
        container.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val menuHeight = container.measuredHeight
        val menuWidth = container.measuredWidth
        val anchorLocation = IntArray(2).also { anchor.getLocationOnScreen(it) }
        val screenHeight = context.resources.displayMetrics.heightPixels
        val screenWidth = context.resources.displayMetrics.widthPixels

        val spaceBelow = screenHeight - (anchorLocation[1] + anchor.height)
        val flipAbove = menuHeight > spaceBelow
        val yOffset = if (flipAbove) -(anchor.height + menuHeight) else 0

        // Same clamp on the other axis: showAsDropDown aligns the card's
        // start edge to the anchor's, so a menu opened from a control near
        // the right edge (again, the Apps screen's "More options" button)
        // ran off the side. Shift it back just far enough to fit.
        // Keep a small gap rather than sitting flush against the screen edge.
        val edgeMargin = context.resources.getDimensionPixelSize(R.dimen.context_menu_edge_margin)
        val overflowX = (anchorLocation[0] + menuWidth) - (screenWidth - edgeMargin)
        val xOffset = if (overflowX > 0) -overflowX else 0

        popup.showAsDropDown(anchor, xOffset, yOffset)

        // Section 5/73 (v0.5.0 completion pass): the menu animates out of the
        // anchor it belongs to and confirms itself with a light tick, instead
        // of appearing instantly and silently. The pivot is the corner
        // nearest the item it came from — the top-start corner normally, the
        // bottom-start one when the card had to flip above its anchor — so
        // the growth always visibly originates at the anchor.
        io.relite.home.ui.motion.MotionTokens.popIn(
            container,
            pivotX = 0f,
            pivotY = if (flipAbove) menuHeight.toFloat() else 0f,
        )
        anchor.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
    }
}
