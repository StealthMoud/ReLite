package io.relite.home.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore

/**
 * Builds the layout a brand-new install starts with.
 *
 * Every previous release opened on a **completely empty home screen** — no
 * dock apps, no shortcuts, just a wallpaper and the Apps button. That is
 * the single biggest reason ReLite Home did not read as a phone home
 * screen at all, let alone a One UI one: a real device ships with the
 * common apps already placed and its dock already filled, and no amount of
 * correct margins, radii or icon shapes compensates for an empty grid.
 *
 * ## Resolved by role, never hardcoded
 *
 * The apps placed here are found by asking the system which app *handles a
 * given job* — dialling a number, opening a web page, taking a photo —
 * rather than by naming packages. A hardcoded list would be wrong on every
 * device that isn't the one it was written against, and this project
 * explicitly targets arbitrary Android phones (see docs/supported-devices.md).
 * Anything that doesn't resolve is simply skipped, so a device with no
 * dialer or no browser seeds a shorter layout rather than a broken one.
 *
 * Seeding runs **only on a genuine first run** — when no layout file exists
 * at all. A user who deliberately empties their home screen must find it
 * still empty next launch, so an empty-but-persisted workspace is never
 * re-seeded.
 */
object DefaultLayout {

    /**
     * The dock, in order. Four pinned apps alongside the always-present
     * Apps button — the arrangement a stock phone dock ships with.
     */
    private val DOCK_ROLES = listOf(
        Role.Phone,
        Role.Messaging,
        Role.Browser,
        Role.Camera,
    )

    /** Home page 1, in placement order (row-major). */
    private val HOME_ROLES = listOf(
        Role.Contacts,
        Role.Gallery,
        Role.Clock,
        Role.Calculator,
        Role.Calendar,
        Role.Email,
        Role.Music,
        Role.Maps,
        Role.Market,
        Role.Settings,
    )

    /**
     * A job an app can handle, plus the [Intent] that asks the system who
     * handles it. `CATEGORY_APP_*` are the platform's own constants for
     * exactly this purpose.
     */
    private sealed class Role(val intent: Intent) {
        object Phone : Role(Intent(Intent.ACTION_DIAL))
        object Messaging : Role(appCategory(Intent.CATEGORY_APP_MESSAGING))
        object Browser : Role(Intent(Intent.ACTION_VIEW, Uri.parse("http://example.com")))
        object Camera : Role(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA))
        object Contacts : Role(appCategory(Intent.CATEGORY_APP_CONTACTS))
        object Gallery : Role(appCategory(Intent.CATEGORY_APP_GALLERY))
        object Clock : Role(Intent(android.provider.AlarmClock.ACTION_SHOW_ALARMS))
        object Calculator : Role(appCategory(Intent.CATEGORY_APP_CALCULATOR))
        object Calendar : Role(appCategory(Intent.CATEGORY_APP_CALENDAR))
        object Email : Role(appCategory(Intent.CATEGORY_APP_EMAIL))
        object Music : Role(appCategory(Intent.CATEGORY_APP_MUSIC))
        object Maps : Role(appCategory(Intent.CATEGORY_APP_MAPS))
        object Market : Role(appCategory(Intent.CATEGORY_APP_MARKET))
        object Settings : Role(Intent(android.provider.Settings.ACTION_SETTINGS))

        companion object {
            fun appCategory(category: String): Intent =
                Intent(Intent.ACTION_MAIN).addCategory(category)
        }
    }

    /**
     * Produces the first-run workspace, or null when there is nothing worth
     * seeding (no app resolved at all — an unusual device, or a caller that
     * ran before app enumeration was ready).
     *
     * [installed] is the authoritative set of launchable apps; a resolved
     * package is only placed if it actually appears there, so seeding can
     * never create a shortcut to something the drawer doesn't list.
     */
    fun build(context: Context, installed: List<AppEntry>, grid: HomeGridPreset): Workspace? {
        if (installed.isEmpty()) return null
        val byPackage = installed.groupBy { it.packageName }

        fun resolve(role: Role): AppEntry? {
            val packageName = runCatching {
                context.packageManager.resolveActivity(role.intent, 0)?.activityInfo?.packageName
            }.getOrNull() ?: return null
            // Resolution names the handling activity, which is often not the
            // app's *launcher* activity. Match back by package and take the
            // real launcher entry, so the shortcut opens the app normally
            // rather than deep-linking into whatever handled the probe.
            return byPackage[packageName]?.firstOrNull()
        }

        val used = mutableSetOf<String>()
        fun claim(role: Role): AppEntry? {
            val entry = resolve(role) ?: return null
            return if (used.add(entry.componentKey)) entry else null
        }

        val dock = DOCK_ROLES.mapNotNull { claim(it) }.map { it.componentKey }
        val homeEntries = HOME_ROLES.mapNotNull { claim(it) }
        if (dock.isEmpty() && homeEntries.isEmpty()) return null

        val items = homeEntries.mapIndexed { index, entry ->
            WorkspaceItem.AppIcon(
                id = "seed-$index",
                position = GridPosition(
                    page = 0,
                    column = index % grid.columns,
                    row = index / grid.columns,
                ),
                componentKey = entry.componentKey,
            )
        }.filter { it.position.row < grid.rows } // never overflow page 1

        return Workspace(
            pageCount = 1,
            items = items,
            dockComponentKeys = dock,
            homeGrid = grid,
        )
    }
}
