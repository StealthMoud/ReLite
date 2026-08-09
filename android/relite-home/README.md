# ReLite Home

A lightweight, original Android launcher. Kotlin + standard Android Views
(no Jetpack Compose — predictable memory/startup overhead matters more
than declarative UI convenience). Validated on a physical RMX5303 unit
with ~8 GB of RAM; ReLite Home itself makes no fixed RAM assumption —
actual RAM varies by SKU/region and is read from the device, not
hardcoded anywhere in this module.

## What's actually working (v0.3.0)

Unchanged from v0.2.0 — v0.3.0 was correctness-focused (atomic
persistence, stable menu handling) rather than new-feature work; see
CHANGELOG.md for what actually changed.

- **Home workspace** — paged grid (`ViewPager2` + `HomePageFragment`),
  page indicator, dock with a dedicated "open drawer" button.
- **App drawer** — alphabetical + local search, no network, no
  recommendations (`AppDrawerFragment`, `AppSearch`).
- **Add to Home / Remove from Home / App info** — long-press in the
  drawer or on a home icon (`WorkspaceController`). Placement is
  auto-assigned to the first free grid cell; there is no drag-to-move
  gesture yet.
- **Folders** — large rounded dialog presentation (`FolderSheetDialog`)
  and full create/rename/add-member/remove-member/delete-if-empty
  support at the `WorkspaceController` level — not yet wired to a
  folder-editing UI (creation/editing dialogs don't exist yet).
- **Dock, page, and widget editing** — supported by `WorkspaceController`
  (add/remove/reorder dock, add/remove pages, add/resize/remove
  widgets) but likewise not yet wired to interactive UI; only the
  underlying widget host/pick trampoline (`ReliteAppWidgetHost`,
  `WidgetPickerActivity`) exists.
- **Dead-shortcut cleanup** — a workspace item, dock entry, or folder
  membership referring to an uninstalled package is automatically
  dropped (`ReliteHomeApplication`'s `AppRepository` reconciliation).
- **Shared, bounded icon cache** — one `IconCache` instance for the
  whole process (not one per screen/dialog), byte-budgeted, trimmed
  under memory pressure (`onTrimMemory`).
- **Persistence** — `WorkspaceRepository` (schema-versioned JSON,
  pure-Kotlin and unit-testable via `Storage`/`InMemoryStorage`) plus
  `WorkspaceController`, the single place mutations go through.

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
`AppSearch`, `WorkspaceRepository`, `WorkspaceController`,
`BoundedByteCache` — without needing an emulator or Robolectric.
UI/integration behavior (drawer scrolling, folder open animation,
widget binding) requires a device/emulator and is not yet covered by
`androidTest`.

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
