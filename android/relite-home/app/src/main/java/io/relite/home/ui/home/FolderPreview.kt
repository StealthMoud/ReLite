package io.relite.home.ui.home

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.core.content.ContextCompat
import io.relite.home.R
import io.relite.home.data.WorkspaceItem
import io.relite.home.util.IconCache

/**
 * Section 43-44 (v0.4.1): a real 2x2 preview of a folder's first four
 * member icons over the shared themed folder background, rather than an
 * empty icon slot with just a label. Composited once into a single bitmap
 * the size of a normal grid icon — cheap enough to rebuild on every full
 * page rebind (this launcher already rebuilds all cell views on every
 * workspace change, see HomePagerAdapter) without needing its own
 * invalidation tracking; an empty folder falls back to the plain themed
 * background with no member tiles.
 */
object FolderPreview {
    fun render(context: Context, iconCache: IconCache, folder: WorkspaceItem.FolderIcon, sizePx: Int): Drawable {
        val background = ContextCompat.getDrawable(context, R.drawable.bg_folder)?.mutate()
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        background?.setBounds(0, 0, sizePx, sizePx)
        background?.draw(canvas)

        val cell = sizePx / 2
        val padding = (cell * 0.14f).toInt()
        folder.itemComponentKeys.take(4).forEachIndexed { index, componentKey ->
            val parts = componentKey.split("/", limit = 2)
            if (parts.size != 2) return@forEachIndexed
            val icon = iconCache.get(parts[0], parts[1], sizePx) ?: return@forEachIndexed
            val column = index % 2
            val row = index / 2
            icon.setBounds(
                column * cell + padding,
                row * cell + padding,
                (column + 1) * cell - padding,
                (row + 1) * cell - padding,
            )
            icon.draw(canvas)
        }
        return BitmapDrawable(context.resources, bitmap)
    }
}
