package io.relite.home.util

import io.relite.home.data.AppEntry
import org.junit.Assert.assertEquals
import org.junit.Test

class AppSearchTest {

    private val camera = AppEntry("io.relite.camera", "Main", "Camera")
    private val calculator = AppEntry("io.relite.calc", "Main", "Calculator")
    private val settings = AppEntry("io.relite.settings", "Main", "Settings")
    private val webcam = AppEntry("io.relite.webcam", "Main", "WebcamViewer")
    private val apps = listOf(settings, camera, calculator, webcam)

    @Test
    fun `alphabetical sorts case-insensitively`() {
        val sorted = AppSearch.alphabetical(apps)
        assertEquals(listOf("Calculator", "Camera", "Settings", "WebcamViewer"), sorted.map { it.label })
    }

    @Test
    fun `empty query returns alphabetical order`() {
        assertEquals(AppSearch.alphabetical(apps), AppSearch.search(apps, ""))
    }

    @Test
    fun `prefix matches rank above substring matches`() {
        val results = AppSearch.search(apps, "cam")
        assertEquals(listOf("Camera", "WebcamViewer"), results.map { it.label })
    }

    @Test
    fun `search is case-insensitive`() {
        val results = AppSearch.search(apps, "SETTINGS")
        assertEquals(listOf("Settings"), results.map { it.label })
    }

    @Test
    fun `search with no matches returns empty list`() {
        assertEquals(emptyList<AppEntry>(), AppSearch.search(apps, "zzz-not-present"))
    }
}
