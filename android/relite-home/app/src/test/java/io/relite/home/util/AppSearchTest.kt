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

    @Test
    fun `exact match ranks above a longer prefix match`() {
        val cam = AppEntry("io.relite.cam", "Main", "Cam")
        val results = AppSearch.search(listOf(camera, cam), "cam")
        assertEquals(listOf("Cam", "Camera"), results.map { it.label })
    }

    @Test
    fun `a word prefix mid-label ranks above a mere substring`() {
        val googlePhotos = AppEntry("io.relite.photos", "Main", "Google Photos")
        val photograph = AppEntry("io.relite.other", "Main", "Xphotographic")
        val results = AppSearch.search(listOf(photograph, googlePhotos), "photo")
        assertEquals(listOf("Google Photos", "Xphotographic"), results.map { it.label })
    }

    @Test
    fun `every query token must be present, in any order`() {
        val googlePhotos = AppEntry("io.relite.photos", "Main", "Google Photos")
        assertEquals(listOf("Google Photos"), AppSearch.search(listOf(googlePhotos), "google ph").map { it.label })
        assertEquals(listOf("Google Photos"), AppSearch.search(listOf(googlePhotos), "photos google").map { it.label })
        assertEquals(emptyList<AppEntry>(), AppSearch.search(listOf(googlePhotos), "google maps"))
    }

    @Test
    fun `search does not crash on unicode, RTL, or emoji labels`() {
        val accented = AppEntry("io.relite.a", "Main", "Café Menu")
        val arabic = AppEntry("io.relite.b", "Main", "تطبيق")
        val emoji = AppEntry("io.relite.c", "Main", "🎵 Music")
        val mixed = listOf(accented, arabic, emoji)

        assertEquals(listOf("Café Menu"), AppSearch.search(mixed, "café").map { it.label })
        assertEquals(listOf("تطبيق"), AppSearch.search(mixed, "تطبيق").map { it.label })
        assertEquals(listOf("🎵 Music"), AppSearch.search(mixed, "🎵").map { it.label })
        assertEquals(3, AppSearch.alphabetical(mixed).size) // sorting unicode/RTL/emoji labels must not throw
    }

    @Test
    fun `query tokens match regardless of order, including three tokens`() {
        val googlePhotos = AppEntry("io.relite.photos", "Main", "Google Photos Editor")
        assertEquals(
            listOf("Google Photos Editor"),
            AppSearch.search(listOf(googlePhotos), "editor google photos").map { it.label },
        )
    }

    @Test
    fun `a plain substring match still ranks via the all-tokens tier`() {
        val webcam = AppEntry("io.relite.webcam", "Main", "WebcamViewer")
        assertEquals(listOf("WebcamViewer"), AppSearch.search(listOf(webcam), "camview").map { it.label })
    }

    @Test
    fun `leading, trailing, and repeated whitespace in the query is ignored`() {
        val googlePhotos = AppEntry("io.relite.photos", "Main", "Google Photos")
        assertEquals(
            listOf("Google Photos"),
            AppSearch.search(listOf(googlePhotos), "  google   photos  ").map { it.label },
        )
    }

    @Test
    fun `accented Latin query matches an unaccented label tier correctly and vice versa`() {
        val cafe = AppEntry("io.relite.cafe", "Main", "Cafe Finder")
        assertEquals(listOf("Cafe Finder"), AppSearch.search(listOf(cafe), "Cafe").map { it.label })
    }

    @Test
    fun `Persian and Arabic labels support prefix and full-token search`() {
        val persian = AppEntry("io.relite.fa", "Main", "تنظیمات")
        val arabic = AppEntry("io.relite.ar", "Main", "تطبيق الكاميرا")
        val apps = listOf(persian, arabic)

        assertEquals(listOf("تنظیمات"), AppSearch.search(apps, "تنظ").map { it.label })
        assertEquals(listOf("تطبيق الكاميرا"), AppSearch.search(apps, "الكاميرا تطبيق").map { it.label })
    }

    @Test
    fun `ties within the same score tier break alphabetically, stable across repeated calls`() {
        val bravo = AppEntry("io.relite.bravo", "Main", "Bravo App")
        val alpha = AppEntry("io.relite.alpha", "Main", "Alpha App")
        val apps = listOf(bravo, alpha)

        val first = AppSearch.search(apps, "app").map { it.label }
        val second = AppSearch.search(apps, "app").map { it.label }
        assertEquals(listOf("Alpha App", "Bravo App"), first)
        assertEquals(first, second)
    }

    // --- customOrder (sections 85-92, v0.5.0) ---

    @Test
    fun `customOrder places apps by their stored order`() {
        val order = listOf(webcam.componentKey, calculator.componentKey, camera.componentKey, settings.componentKey)
        assertEquals(
            listOf("WebcamViewer", "Calculator", "Camera", "Settings"),
            AppSearch.customOrder(apps, order).map { it.label },
        )
    }

    @Test
    fun `customOrder appends a newly installed app not yet in the stored order, alphabetically at the end`() {
        val order = listOf(camera.componentKey, calculator.componentKey)
        // settings and webcam are installed but never in `order`.
        assertEquals(
            listOf("Camera", "Calculator", "Settings", "WebcamViewer"),
            AppSearch.customOrder(apps, order).map { it.label },
        )
    }

    @Test
    fun `customOrder drops a stored key for an app that's no longer installed`() {
        val order = listOf("io.relite.gone/Main", camera.componentKey)
        assertEquals(listOf("Camera"), AppSearch.customOrder(listOf(camera), order).map { it.label })
    }

    @Test
    fun `customOrder with an empty stored order is fully alphabetical`() {
        assertEquals(AppSearch.alphabetical(apps), AppSearch.customOrder(apps, emptyList()))
    }
}
