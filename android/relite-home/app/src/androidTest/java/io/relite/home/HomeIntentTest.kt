package io.relite.home

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Section 34: the package must actually expose a HOME/LAUNCHER activity. */
@RunWith(AndroidJUnit4::class)
class HomeIntentTest {

    @Test
    fun packageExposesAHomeActivity() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val resolved = context.packageManager.queryIntentActivities(homeIntent, 0)
        assertTrue(resolved.any { it.activityInfo.packageName == context.packageName })
    }
}
