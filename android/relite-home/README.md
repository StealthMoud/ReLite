# ReLite Home

A lightweight, original Android launcher. Kotlin + standard Android Views
(no Jetpack Compose — predictable memory/startup overhead matters more
than declarative UI convenience). Validated on a physical RMX5303 unit
with ~8 GB of RAM; ReLite Home itself makes no fixed RAM assumption —
actual RAM varies by SKU/region and is read from the device, not
hardcoded anywhere in this module.

## What's actually working (v0.4.1)

Unlike v0.3.0 (correctness/packaging only), v0.4.0 is the pass where
every domain feature below got its actual UI surface wired up — not just
a `WorkspaceController` method that could theoretically back one. v0.4.1
is a hardening pass on top: the widget-picker's provider list actually
populates now (it silently didn't before), package-lifecycle reconciliation
matches exact `package/activity` components instead of package names,
auto-page-creation is transactional, and folders got member reorder plus
a real 2x2 preview. See `CHANGELOG.md`'s `[0.4.1]` entry for the full list,
including what it deliberately doesn't cover yet (stress/jank measurement,
the Samsung One UI visual-parity redesign).

- **Home workspace** — cell-aware `WorkspaceGridLayout` (`ViewPager2` +
  `HomePageFragment`) rendering each item's real (column, row, span),
  page indicator, and page management (add page / remove an empty page /
  "Move to page…") reached from a long press on empty grid space.
- **Drag and drop** — long-press-then-drag to move an app, folder, or
  widget within a page; long-press-then-drag near a screen edge for
  ~650ms flips to the adjacent page mid-drag and the drag continues there
  (`DragOverlay`, `EdgeHover`). Widgets keep their own touch input for
  scrolling/tapping their own content, so widget moves go through
  "Move to page…" rather than a raw drag, same as this always was.
- **Dock** — fully editable: remove, App info, drag-to-reorder, pin an
  app from the drawer or home, "Add to Home" from the dock (independent
  shortcut, dock entry stays put).
- **App drawer** — alphabetical + local, deterministic tiered search
  (exact > full-prefix > word-prefix > all-tokens-any-order), no
  network, no recommendations (`AppDrawerFragment`, `AppSearch`).
- **Folders** — create (from the drawer, or by folding an existing home
  app into a new/existing folder via `FolderPicker`), rename, add/remove
  members, delete with or without members — all through
  `FolderSheetDialog`, which re-reads the folder live so edits show
  immediately.
- **Widgets** — ReLite's own provider picker → allocate → bind (with the
  `ACTION_APPWIDGET_BIND` permission-prompt fallback) → configure if the
  provider declares one → initial span derived from the provider's
  declared minimum size → place → persist, with the allocated widget id
  cleaned up on every cancel/failure path. Placed widgets are reachable
  via long-press for Remove/Resize (`+`/`-` width and height controls,
  each tap validated before committing) and Move to page.
- **Layout export/import** — portable JSON via the Storage Access
  Framework (`WorkspaceRepository.exportPortable`/`importPortable`),
  structurally validated, reports apps that aren't currently installed,
  and never replaces the live workspace without an explicit confirmation
  showing what will change. Widgets are deliberately dropped on
  export/import — they're device-local bindings that must be re-added,
  not carried across devices.
- **Home Settings** (`HomeSettingsActivity`) — export, import, reset,
  a default-launcher helper (`RoleManager` on API 29+, falling back to
  `Settings.ACTION_HOME_SETTINGS`), About, and an explicit System/Light/
  Dark theme picker (`ThemePreference`, applied via
  `AppCompatDelegate.setDefaultNightMode` before any Activity is created,
  so there's no flash of the wrong theme).
- **System wallpaper** shows through the home screen
  (`Theme.ReliteHome.Home`); the compositor renders it, ReLite Home never
  decodes a wallpaper bitmap itself.
- **WindowInsets** — the home screen draws edge-to-edge; the workspace,
  drawer, and dock all clear the status bar/cutout/gesture-nav region
  using the real inset values (`MainActivity.applyWindowInsets`), and the
  drawer's search results stay reachable under the IME instead of a
  hardcoded padding constant.
- **Accessibility** — folder and widget cells get explicit
  contentDescriptions (a folder icon has no other visible label text for
  TalkBack to read), actionable controls keep a 48dp minimum touch
  target, and every drag-based move has a menu-based non-drag
  alternative ("Move to page…", "Pin to Dock"/"Add to Home",
  "Remove from Dock").
- **Dead-shortcut cleanup** — a workspace item, dock entry, or folder
  membership referring to an uninstalled package is automatically
  dropped (`ReliteHomeApplication`'s `AppRepository` reconciliation).
- **Shared, bounded icon cache** — one `IconCache` instance for the
  whole process (not one per screen/dialog), byte-budgeted, trimmed
  under memory pressure (`onTrimMemory`).
- **Persistence** — `WorkspaceRepository` (schema-versioned JSON,
  pure-Kotlin and unit-testable via `Storage`/`InMemoryStorage`) plus
  `WorkspaceController`, the single place mutations go through; excluded
  from Android auto-backup (`backup_rules.xml`/`data_extraction_rules.xml`)
  since a restored `workspace.json` would resurrect widget bindings with
  a stale/foreign `appWidgetId`.

No `INTERNET` permission, no analytics, no accounts, no ads, no
`QUERY_ALL_PACKAGES` (app discovery goes through `LauncherApps`, which
is visibility-exempt for the default-launcher role). See
`app/src/main/AndroidManifest.xml`.

## Building

```bash
cd android/relite-home
echo "sdk.dir=$ANDROID_HOME" > local.properties   # or use Android Studio, which does this for you
./gradlew assembleDebug
./gradlew testDebugUnitTest
./gradlew lint
```

Unit tests (`app/src/test/`) cover the framework-independent logic —
`AppSearch`, `WorkspaceRepository`, `WorkspaceController`, `EdgeHover`,
`ThemePreference`'s parsing, `BoundedByteCache` — without needing an
emulator or Robolectric.

Instrumentation tests (`app/src/androidTest/`) cover MainActivity launch,
the HOME intent, the drawer actually opening/searching through the real
UI, Activity/theme recreation leaving the workspace interactive, and dock
state — run them with a device or emulator attached:

```bash
../../scripts/test-launcher-emulator.sh
```

## Setting ReLite Home as default launcher (for manual testing)

Install the debug APK, then either tap Home and choose ReLite Home from
the picker, or:

```bash
adb shell cmd package set-home-activity io.relite.home/.ui.MainActivity
```

Keep the previous launcher installed until ReLite Home has been used
end-to-end — see `devices/realme/RMX5303/protected-packages.yaml`, which
protects the OEM launcher package until then.

## What this is not (yet)

Per master plan section 21: on stock, locked firmware, ReLite Home
replaces the home screen, app drawer, folders, and widgets — it does not
and cannot legitimately replace SystemUI (notification shade, quick
settings, lock screen). See `docs/architecture.md`.

Not a custom ROM, not a SystemUI replacement, not a bootloader unlocker,
not a root framework.

## Known gaps

- **Not yet run on a physical device or emulator this pass** — no
  device was attached and no `emulator` binary was available in the
  environment that produced this work, so the drag gestures, edge-hover
  timing, theme recreation, and the `androidTest` suite have only been
  compiled and code-reviewed, not exercised live. Run
  `scripts/test-launcher-emulator.sh` once a device/emulator is
  available.
- Widgets don't support drag — same-page or cross-page — because
  `AppWidgetHostView` owns its own touch input; they move through
  "Move to page…" instead.
