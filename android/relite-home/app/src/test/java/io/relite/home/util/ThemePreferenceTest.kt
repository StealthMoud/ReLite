package io.relite.home.util

import org.junit.Assert.assertEquals
import org.junit.Test

class ThemePreferenceTest {

    @Test
    fun `no stored value defaults to system`() {
        assertEquals(ThemeMode.SYSTEM, ThemePreference.parseMode(null))
    }

    @Test
    fun `stored value round-trips to its mode`() {
        assertEquals(ThemeMode.LIGHT, ThemePreference.parseMode(ThemeMode.LIGHT.name))
        assertEquals(ThemeMode.DARK, ThemePreference.parseMode(ThemeMode.DARK.name))
        assertEquals(ThemeMode.SYSTEM, ThemePreference.parseMode(ThemeMode.SYSTEM.name))
    }

    @Test
    fun `an unrecognized stored value fails safe to system instead of throwing`() {
        assertEquals(ThemeMode.SYSTEM, ThemePreference.parseMode("not-a-real-mode"))
    }
}
