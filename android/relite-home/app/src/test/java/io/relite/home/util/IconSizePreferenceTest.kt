package io.relite.home.util

import org.junit.Assert.assertEquals
import org.junit.Test

class IconSizePreferenceTest {

    @Test
    fun `no stored value defaults to default size`() {
        assertEquals(IconSize.DEFAULT, IconSizePreference.parse(null))
    }

    @Test
    fun `stored values round-trip`() {
        assertEquals(IconSize.SMALL, IconSizePreference.parse(IconSize.SMALL.name))
        assertEquals(IconSize.DEFAULT, IconSizePreference.parse(IconSize.DEFAULT.name))
        assertEquals(IconSize.LARGE, IconSizePreference.parse(IconSize.LARGE.name))
    }

    @Test
    fun `an unrecognized stored value fails safe to default instead of throwing`() {
        assertEquals(IconSize.DEFAULT, IconSizePreference.parse("not-a-real-size"))
    }

    @Test
    fun `scale factors are ordered small less than default less than large`() {
        assert(IconSize.SMALL.scaleFactor < IconSize.DEFAULT.scaleFactor)
        assert(IconSize.DEFAULT.scaleFactor < IconSize.LARGE.scaleFactor)
    }
}
