package io.relite.home

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Section 8 (v0.5.0 completion pass): a real, deterministic regression guard
 * for the debug widget test fixture itself — a manifest-merge or build-
 * config mistake that silently dropped `ReliteTestWidgetProvider` from the
 * debug build would otherwise only be caught by a human noticing it missing
 * from the live widget picker. The full interactive bind -> place -> resize
 * pipeline against this fixture was verified live on the RMX5303 (system
 * bind-permission dialog, initial size render, live resize re-render, all
 * matching real AppWidgetManager-supplied values) — that path isn't
 * re-automated here since it requires responding to a real system
 * permission dialog, which isn't something `connectedDebugAndroidTest` can
 * drive without flaking across OEM Settings-app UI differences.
 */
@RunWith(AndroidJUnit4::class)
class ReliteTestWidgetProviderInstrumentationTest {

    @Test
    fun theDebugTestWidgetProviderIsRegisteredAndDiscoverable() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val expected = ComponentName(context.packageName, "io.relite.home.debug.ReliteTestWidgetProvider")
        val providers = AppWidgetManager.getInstance(context).installedProviders.map { it.provider }
        assertTrue("expected $expected among $providers", expected in providers)
    }
}
