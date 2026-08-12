package io.relite.home.ui.menu

import android.app.AlertDialog
import android.view.Gravity
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import io.relite.home.R

/**
 * Shows an [AlertDialog] the way One UI positions one.
 *
 * Samsung's design guide is explicit about this and it is one of the most
 * immediately recognizable differences between a One UI dialog and a stock
 * Android one (One UI Design Guidelines, Component 07. Dialog, p.37):
 *
 * > Provide a dialog pop-up, which requires a user action, at the bottom.
 * > If any action is not allowed (e.g. when 'Loading...' process gets
 * > displayed and any other action such as cancel is not allowed), then
 * > place the dialog pop-up in the middle of the screen.
 *
 * and, for phone form factors (p.40), a dialog's **min width is 100%**.
 *
 * The reason is the same one behind One UI's whole Viewing-area /
 * Interaction-area split (Architecture 01, p.7): anything demanding a
 * decision belongs within comfortable thumb reach, not stranded in the
 * middle of a tall screen.
 *
 * Every dialog in this launcher asks the user for something — rename this
 * folder, pick a page, confirm a reset — so all of them take [showOneUi].
 * The one exception the guide carves out (a purely informational,
 * non-actionable "Loading…") does not exist anywhere in this app, so no
 * centred variant is provided rather than shipping an unused branch.
 */
fun AlertDialog.showOneUi(): AlertDialog {
    show()
    window?.apply {
        setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
        setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        // A rounded surface, not the platform's default square dialog panel
        // — otherwise a hard-cornered box lands in the middle of a UI whose
        // every other floating surface is rounded.
        setBackgroundDrawable(ContextCompat.getDrawable(context, R.drawable.bg_dialog))
        // Full bleed would collide with the gesture-nav area and lose that
        // rounded shape at the screen edge, so the "100% min width" is
        // honoured as full width minus a consistent inset rather than
        // literally edge-to-edge.
        val margin = context.resources.getDimensionPixelSize(R.dimen.dialog_margin)
        decorView.setPadding(margin, 0, margin, margin)
    }
    return this
}

/** Convenience for the common `Builder.create()` + [showOneUi] pair. */
fun AlertDialog.Builder.showOneUi(): AlertDialog = create().showOneUi()
