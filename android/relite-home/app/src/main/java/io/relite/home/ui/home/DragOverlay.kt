package io.relite.home.ui.home

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView

/**
 * Section 4-6 (v0.4.0): a same-page drag can translate the actual dragged
 * cell View in place (the original approach), but a cross-page drag needs
 * the dragged icon to keep rendering on screen while the ViewPager2 swaps
 * out the fragment that view belongs to — a child of one page's grid can't
 * survive that swap. Instead, [beginDrag] snapshots the source view into a
 * free-floating bitmap added to a full-screen overlay that sits above the
 * pager (not inside it), so it's unaffected by which page is current.
 */
class DragOverlay(private val overlay: FrameLayout) {

    fun beginDrag(source: View): View {
        val bitmap = Bitmap.createBitmap(
            source.width.coerceAtLeast(1),
            source.height.coerceAtLeast(1),
            Bitmap.Config.ARGB_8888,
        )
        source.draw(Canvas(bitmap))

        val overlayLocation = IntArray(2).also { overlay.getLocationOnScreen(it) }
        val sourceLocation = IntArray(2).also { source.getLocationOnScreen(it) }

        val proxy = ImageView(overlay.context).apply {
            setImageBitmap(bitmap)
            alpha = 0.9f
            layoutParams = FrameLayout.LayoutParams(source.width, source.height).apply {
                leftMargin = sourceLocation[0] - overlayLocation[0]
                topMargin = sourceLocation[1] - overlayLocation[1]
            }
        }
        overlay.addView(proxy)
        return proxy
    }

    /** [dx]/[dy] are deltas from the initial touch-down point, same convention as the same-page drag used before it. */
    fun moveDrag(proxy: View, dx: Float, dy: Float) {
        proxy.translationX = dx
        proxy.translationY = dy
    }

    fun endDrag(proxy: View) {
        overlay.removeView(proxy)
    }
}
