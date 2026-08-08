# ReLite Home

A lightweight, original Android launcher. Kotlin + standard Android Views
(no Jetpack Compose — predictable memory/startup overhead matters more
than declarative UI convenience on a 6 GB device with OEM RAM Expansion in
the picture).

## What's here (v1 skeleton)

- **Home workspace** — paged grid (`ViewPager2` + `HomePageFragment`),
  page indicator, dock with a dedicated "open drawer" button.
- **App drawer** — alphabetical + local search, no network, no
  recommendations (`AppDrawerFragment`, `AppSearch`).
- **Folders** — large rounded dialog presentation (`FolderSheetDialog`).
- **Widgets** — `AppWidgetHost` wrapper (`ReliteAppWidgetHost`) and a
  pick/bind trampoline (`WidgetPickerActivity`).
- **Persistence** — `WorkspaceRepository` (schema-versioned JSON,
  pure-Kotlin and unit-testable via `Storage`/`InMemoryStorage`).

No `INTERNET` permission, no analytics, no accounts, no ads. See
`app/src/main/AndroidManifest.xml`.

## Building

```bash
cd android/relite-home
echo "sdk.dir=$ANDROID_HOME" > local.properties   # or use Android Studio, which does this for you
./gradlew assembleDebug
./gradlew testDebugUnitTest
```

Unit tests (`app/src/test/`) cover the framework-independent logic —
`AppSearch` and `WorkspaceRepository` — without needing an emulator or
Robolectric. UI/integration behavior (drawer scrolling, folder open
animation, widget binding) requires a device/emulator and is not yet
covered by `androidTest`.

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
