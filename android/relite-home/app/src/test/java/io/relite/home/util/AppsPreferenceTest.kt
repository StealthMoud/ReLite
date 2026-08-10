package io.relite.home.util

import org.junit.Assert.assertEquals
import org.junit.Test

class AppsPreferenceTest {

    @Test
    fun `no stored sort mode defaults to custom`() {
        assertEquals(AppsSortMode.CUSTOM, AppsPreference.parseSortMode(null))
    }

    @Test
    fun `stored sort mode round-trips`() {
        assertEquals(AppsSortMode.ALPHABETICAL, AppsPreference.parseSortMode(AppsSortMode.ALPHABETICAL.name))
        assertEquals(AppsSortMode.CUSTOM, AppsPreference.parseSortMode(AppsSortMode.CUSTOM.name))
    }

    @Test
    fun `an unrecognized stored sort mode fails safe to custom instead of throwing`() {
        assertEquals(AppsSortMode.CUSTOM, AppsPreference.parseSortMode("not-a-real-mode"))
    }

    @Test
    fun `no stored custom order decodes to empty`() {
        assertEquals(emptyList<String>(), AppsPreference.decodeOrder(null))
        assertEquals(emptyList<String>(), AppsPreference.decodeOrder(""))
    }

    @Test
    fun `custom order round-trips through encode and decode`() {
        val order = listOf("io.relite.a/Main", "io.relite.b/Main", "io.relite.c/Main")
        assertEquals(order, AppsPreference.decodeOrder(AppsPreference.encodeOrder(order)))
    }
}
