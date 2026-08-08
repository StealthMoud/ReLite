package io.relite.home.data

/**
 * A single launchable activity, as exposed by LauncherApps. Deliberately
 * framework-agnostic (no Drawable/ComponentName) so app-enumeration and
 * search logic can be unit tested on the plain JVM without Robolectric.
 */
data class AppEntry(
    val packageName: String,
    val activityName: String,
    val label: String,
    val userSerial: Long = 0L,
) {
    val componentKey: String get() = "$packageName/$activityName"
}
