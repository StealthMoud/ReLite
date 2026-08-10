package io.relite.home.util

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [IconNormalizer.renderToBitmap] itself needs android.graphics.Bitmap/
 * Canvas (no Robolectric in this project — see IconCache's kdoc on why
 * live device verification covers the visual rendering), but the scale
 * constant it uses is a plain value worth guarding against an accidental
 * out-of-range edit (e.g. a value >= 1 would silently disable the legacy
 * icon inset entirely, or a value <= 0 would render a zero-size/invalid
 * icon rect).
 */
class IconNormalizerTest {

    @Test
    fun `legacy icon scale is a real reduction, not a no-op or degenerate value`() {
        assertTrue(IconNormalizer.LEGACY_ICON_SCALE > 0f)
        assertTrue(IconNormalizer.LEGACY_ICON_SCALE < 1f)
    }
}
