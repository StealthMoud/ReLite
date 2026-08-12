package io.relite.home.ui.home

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import io.relite.home.R

/**
 * Section 11 (v0.5.0 completion pass): a widget's real drag-move/drag-resize
 * edit surface — added as a same-span replacement cell directly over the
 * widget's current position (see [WorkspaceGridLayout.addCell]/
 * [WorkspaceGridLayout.updateCellPosition]/[WorkspaceGridLayout.updateCellSpan]),
 * so it inherits the exact same pixel geometry the widget itself uses
 * without any separate coordinate math. Shows a static bitmap snapshot of
 * the widget's current content (same technique as [DragOverlay]) rather
 * than the live [android.appwidget.AppWidgetHostView] — that view owns its
 * own touch input (its own scroll views, buttons), which is exactly why
 * widgets never got the same live drag apps/folders already have; this
 * overlay fully covers it and owns all touch itself while active, then
 * disappears (letting the real widget receive touch again) once its
 * caller's `host.workspaceChanged()` rebuilds the page after a commit, or
 * immediately on Done/Cancel with no commit.
 *
 * Three independent child views, each handling its own touch/click rather
 * than one manual hit-region dispatcher: [doneButton], [removeButton], and
 * [resizeHandle] (bottom-right corner); everywhere else in the bounds is
 * the "body" — dragging it moves the widget.
 */
class WidgetEditOverlayView(context: Context, snapshotOf: View?) : FrameLayout(context) {

    var onMoveDragStart: (() -> Unit)? = null
    var onMoveDrag: ((dxPx: Float, dyPx: Float) -> Unit)? = null
    var onMoveDragEnd: (() -> Unit)? = null

    var onResizeDragStart: (() -> Unit)? = null
    var onResizeDrag: ((dxPx: Float, dyPx: Float) -> Unit)? = null
    var onResizeDragEnd: (() -> Unit)? = null

    var onDoneClicked: (() -> Unit)? = null
    var onRemoveClicked: (() -> Unit)? = null

    private val resizeHandle: View
    private val doneButton: View
    private val removeButton: View

    init {
        setBackgroundResource(R.drawable.bg_widget_edit_overlay)

        // Accessibility (v0.5.0 completion pass): the overlay as a whole is
        // what a screen reader should describe — "widget, drag to move" —
        // rather than reading out an unlabelled image. The drag itself is a
        // pointer gesture with no accessible equivalent, which is exactly why
        // the Move to page… / Resize dialogs stay in the context menu as the
        // non-drag path (see HomePageFragment.showWidgetEditOverlay's kdoc).
        contentDescription = context.getString(R.string.widget_edit_body)

        if (snapshotOf != null && snapshotOf.width > 0 && snapshotOf.height > 0) {
            val bitmap = Bitmap.createBitmap(snapshotOf.width, snapshotOf.height, Bitmap.Config.ARGB_8888)
            snapshotOf.draw(Canvas(bitmap))
            addView(
                ImageView(context).apply {
                    setImageBitmap(bitmap)
                    alpha = 0.85f
                    // Purely decorative — a frozen picture of the widget
                    // underneath. Announcing it would just add noise before
                    // the controls that actually do something.
                    importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
                },
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
            )
        }

        var moveDownX = 0f
        var moveDownY = 0f
        // Section 11 (v0.5.0 completion pass): a real StackOverflowError
        // found live on the RMX5303 — onMoveDragEnd/onResizeDragEnd (below)
        // can remove this overlay from its parent (a successful move/resize
        // commit closes the overlay). Removing a view that's the active
        // touch target makes Android redeliver an ACTION_CANCEL to it
        // synchronously as part of that same removal
        // (ViewGroup.cancelAndClearTouchTargets), which re-entered this same
        // branch, called the same close-and-remove logic again, and so on
        // until the stack overflowed. `ending` blocks the re-entrant call;
        // `post{}` defers the actual view-hierarchy mutation until the
        // current touch dispatch has fully unwound, the same pattern
        // HomePageFragment's own drag-finish already uses for the identical
        // class of hazard (see its kdoc).
        var moveEnding = false
        setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    moveEnding = false
                    moveDownX = event.rawX
                    moveDownY = event.rawY
                    // This overlay lives inside ViewPager2's internal
                    // RecyclerView (via WorkspaceGridLayout) — without this,
                    // a drag whose path accumulates enough movement gets
                    // stolen mid-gesture by ViewPager2's own page-swipe
                    // detector, the same ViewPager2-interception hazard
                    // HomePageFragment's own app/folder drag already works
                    // around (see its kdoc): found live here as MOVE events
                    // silently never reaching this listener after ACTION_DOWN.
                    parent?.requestDisallowInterceptTouchEvent(true)
                    onMoveDragStart?.invoke()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    onMoveDrag?.invoke(event.rawX - moveDownX, event.rawY - moveDownY)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!moveEnding) {
                        moveEnding = true
                        post { onMoveDragEnd?.invoke() }
                    }
                    true
                }
                else -> false
            }
        }

        val handleSizePx = (HANDLE_SIZE_DP * resources.displayMetrics.density).toInt()
        resizeHandle = View(context).apply {
            setBackgroundResource(R.drawable.bg_widget_edit_handle)
            contentDescription = context.getString(R.string.widget_edit_resize_handle)
            layoutParams = LayoutParams(handleSizePx, handleSizePx, Gravity.BOTTOM or Gravity.END).apply {
                bottomMargin = -handleSizePx / 2
                marginEnd = -handleSizePx / 2
            }
            var resizeDownX = 0f
            var resizeDownY = 0f
            var resizeEnding = false
            setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        resizeEnding = false
                        resizeDownX = event.rawX
                        resizeDownY = event.rawY
                        parent?.requestDisallowInterceptTouchEvent(true)
                        onResizeDragStart?.invoke()
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        onResizeDrag?.invoke(event.rawX - resizeDownX, event.rawY - resizeDownY)
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (!resizeEnding) {
                            resizeEnding = true
                            post { onResizeDragEnd?.invoke() }
                        }
                        true
                    }
                    else -> false
                }
            }
        }
        addView(resizeHandle)

        val buttonSizePx = (BUTTON_SIZE_DP * resources.displayMetrics.density).toInt()
        doneButton = ImageView(context).apply {
            setImageResource(android.R.drawable.checkbox_on_background)
            contentDescription = context.getString(R.string.widget_edit_done)
            setBackgroundResource(R.drawable.bg_widget_edit_handle)
            layoutParams = LayoutParams(buttonSizePx, buttonSizePx, Gravity.TOP or Gravity.START).apply {
                topMargin = -buttonSizePx / 4
                marginStart = -buttonSizePx / 4
            }
            setOnClickListener { onDoneClicked?.invoke() }
        }
        addView(doneButton)

        removeButton = ImageView(context).apply {
            setImageResource(android.R.drawable.ic_menu_delete)
            contentDescription = context.getString(R.string.widget_edit_remove)
            setBackgroundResource(R.drawable.bg_widget_edit_handle)
            layoutParams = LayoutParams(buttonSizePx, buttonSizePx, Gravity.TOP or Gravity.END).apply {
                topMargin = -buttonSizePx / 4
                marginEnd = -buttonSizePx / 4
            }
            setOnClickListener { onRemoveClicked?.invoke() }
        }
        addView(removeButton)
    }

    /** A short tick each time a live drag/resize crosses into a new grid cell/span — the "resize snap" haptic the master plan calls for. */
    fun snapTick() {
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    companion object {
        private const val HANDLE_SIZE_DP = 28
        private const val BUTTON_SIZE_DP = 32
    }
}
