# Changelog

All notable changes to ReLite are documented in this file.

The format loosely follows [Keep a Changelog](https://keepachangelog.com/),
and versioning targets follow `docs/architecture.md` / the master plan
milestones (0.1, 0.2, 1.0).

## [Unreleased]

A final v0.5.0 completion pass closing the last One UI parity surfaces
that were still recorded as unimplemented, plus a truth-sync of
`docs/design/one-ui-parity-matrix.md`, which had drifted — several rows
still read `LOW (not implemented)` for work that had already shipped in
an earlier pass. Every change below was built, unit-tested, and run on
the physical RMX5303.

### Visual pass — real One UI values, from Samsung's published guide

The previous reference doc recorded that no pixel-level One UI values
were obtainable without a Galaxy handset. That was **wrong**: Samsung
publishes them. The **One UI Design Guidelines** PDF, distributed openly
on Samsung's design site for third-party developers, gives concrete
figures, and the ones it gives are now implemented:

- **24dp minimum side margins** (Architecture 04, p.14) as
  `screen_margin_horizontal`, applied to the Home grid and dock.
- **The 26/20/12dp thumbnail-radius scale** (Visual Design 04, p.67) as
  `radius_large`/`radius_medium`/`radius_small`, mapped to dock+folder /
  context-menu card / app icons.
- **The accent palette** (Visual Design 02, p.62-63): Primary `#0381fe`,
  Primary dark `#0072de` light / `#3e91ff` dark, Color control activated
  `#3e91ff` — including wiring `colorControlActivated`, without which
  every Settings switch was drawing in AppCompat's *default* accent
  rather than this launcher's palette.
- **Typography**: the guide states One UI's default font is Roboto,
  which is Android's own system default and what this app already
  renders in. Exact match, nothing of Samsung's shipped.

`NOTICE.md` and `README.md` are updated to state precisely which values
are borrowed and from where — the previous "no Samsung color values"
claim would otherwise have become untrue. No Samsung asset, icon, font,
wallpaper, or extracted device resource is used; the guide's numbers are
published developer guidance, not extracted resources. Backgrounds,
icon sizing, dock/indicator geometry and motion curves stay ReLite's
own, because the guide publishes no values for them.

### Home screen — a default layout instead of an empty grid

Every release before this opened on a **completely empty home screen**: no
shortcuts, an empty dock, just wallpaper and the Apps button. That, not
any spacing or colour detail, was the biggest single reason the launcher
did not read as a phone home screen at all — a real device ships with the
common apps already placed and its dock filled.

`DefaultLayout` now seeds a starting layout on first run: Phone,
Messaging, Browser and Camera in the dock; Contacts, Gallery, Clock,
Calculator, Calendar, Email, Music, Maps, Store and Settings on page one.

Apps are found by **asking the system which app handles a given job**
(`ACTION_DIAL`, `CATEGORY_APP_*`, `INTENT_ACTION_STILL_IMAGE_CAMERA`, …),
never by hardcoded package name — a fixed list would be wrong on every
device except the one it was written against, and this project targets
arbitrary phones. Anything that doesn't resolve is skipped, so a device
without a dialer seeds a shorter layout rather than a broken one, and a
resolved package is only placed if it actually appears in the launchable
app list.

Seeding is guarded on "no layout file exists at all"
(`WorkspaceRepository.lastLoadWasFirstRun`), not "the workspace is
empty" — a user who deliberately clears their home screen must find it
still cleared next launch.

### Home legibility and density

- **Labels were unreadable over the wallpaper.** They used the theme's
  text colour, which is near-white in dark mode and near-black in light
  mode — so on the RMX5303's bright wallpaper, "Calculator", "Play Store"
  and "Settings" washed out completely. Home labels are now fixed white
  with a soft drop shadow (`HomeIconLabel`), which survives both extremes
  without knowing anything about the image behind them. The drawer keeps
  the plain theme-coloured label; it draws on an opaque background where
  a shadow would just be grime.
- **The page indicator had the same problem** and is now fixed
  white/translucent-white rather than theme-coloured. It also draws the
  active page as an **elongated pill** rather than a differently-coloured
  dot, which is the One UI shape and reads far better as "you are here".
  A second on-surface palette exists for the Apps screen, whose indicator
  draws on an opaque background where white-on-white would be invisible.
- **The Apps screen now has a page indicator at all** — it pages
  horizontally and previously gave no clue how many pages existed or
  which was showing. Hidden for a single page and in Alphabetical order,
  which scrolls vertically and has no pages to indicate.
- **Icons enlarged** from 52dp to 60dp: at 52dp icons floated in their
  cells rather than forming the dense field a Samsung home screen shows.
  The guide publishes no icon dimension, so this is ReLite's own
  measurement against the real 4×6 cell.
- **Folder tiles are masked with the same squircle as app icons.** A
  folder sits in the grid among those icons, so a rounded-rect tile read
  as the odd one out — the exact mixed-silhouette problem the icon mask
  was introduced to solve.
- The widget picker's provider cards are now real focus blocks
  (`bg_focus_block`) at the 24dp margin; they had been using the dock
  background, which is now translucent and looked washed out over an
  opaque screen.

### Dialogs — bottom-anchored, full width, rounded

Samsung's guide is explicit, and it is one of the most recognizable
differences between a One UI dialog and a stock Android one (Component
07. Dialog, p.37): *"Provide a dialog pop-up, which requires a user
action, at the bottom."* Phone dialogs are additionally specified at
**min width 100%** (p.40). The reasoning is the same one behind One UI's
Viewing-area / Interaction-area split (Architecture 01, p.7) — anything
demanding a decision belongs in comfortable thumb reach, not stranded
mid-screen.

All **16** `AlertDialog`s in the launcher now go through a shared
`showOneUi()` helper that anchors them to the bottom at full width on a
rounded surface. Without the explicit background they kept the platform's
default *square* dialog panel, which put a hard-cornered box in a UI
whose every other floating surface is rounded.

The guide's one carve-out — a purely informational, non-actionable
"Loading…" dialog stays centred — has no instance anywhere in this app,
so no centred variant was written rather than shipping an unused branch.

### Settings — focus blocks instead of a flat list

Architecture 01 (p.8) names One UI's card container directly: *"a
card-type container called a 'focus block' … A focus block's big rounded
corners can capture the user's attention visually with its shape. You can
make your content stand out even more by creating a high contrast between
the focus block's background color and blank space behind them."*

Settings had section headers but laid its rows straight onto the
background, which is what made it read as a generic preference list. Each
group is now a `bg_focus_block` card at the guide's 24dp safe-area margin,
with its header sitting outside and above it.

Also fixed there: every settings row button was rendering in **ALL CAPS**
("SORT", "EXPORT LAYOUT") because that is Android's default borderless
button style. One UI's typography rule is to *"capitalize the first
letter in every word and sentence … while leaving all other letters as
lowercase"* (Visual Design 03, p.65) — caps is one of the clearest tells
of a stock-Android control. A shared `SettingsButton` style sets
`textAllCaps=false`, and the one string that was mis-cased in the
resources ("Reset Home Layout") is now "Reset home layout".

Verified against the guide and already compliant, so left alone: the
edit-mode scrim already dismisses on tap, which is what Component 15
(p.53) requires of a dimmed area; contextual menus are already dropdown
menus without a title, per Component 07 (p.39); and no toast uses a
dismiss-style action button, which Component 13 (p.50) explicitly
forbids.

### Icons — one consistent squircle silhouette

The single most recognizable property of a One UI home screen, and the
reason ReLite still read as "not Samsung" after the margins, radii and
colors were already correct: **every** app icon is presented in the same
rounded-square silhouette. Samsung's guide states it directly — "One UI
app icons have square backgrounds with smooth rounded corners and
outlines" (Visual Design, Iconography).

`IconNormalizer` previously applied no shape at all. An adaptive icon got
whatever mask the OEM system happened to use; a legacy icon got none. The
drawer was a mix of hard squares, circles and assorted corner radii,
which is exactly what One UI does not look like. Now:

- A true **squircle** (superellipse, cubic-Bézier approximation) is
  clipped over every icon — not a plain rounded rectangle, whose abrupt
  curvature change at the straight edge is what makes it read as "a
  rectangle with its corners cut" rather than "soft".
- **Adaptive icons** have their background/foreground layers drawn
  manually rather than via `draw()`, which would re-apply the system mask
  this is meant to override. Layers are placed on the spec's 108dp canvas
  with the inner 72dp as the visible viewport.
- **Legacy icons** are composited onto a neutral plate and inset, since
  they have no separate background layer to mask against — which also
  subsumes the older brightness/weight compensation this class existed
  for.

Corner geometry (`CORNER_RATIO`, `SMOOTHING`) is ReLite's own: the guide
describes the shape but publishes no numbers for it.

### Fixed — the Apps screen had 4 rows instead of 6

`GridLayoutManager`'s span count is measured across the axis
*perpendicular* to scrolling — the column count when vertical, but the
**row** count when horizontal, which is what Custom order uses. Both
paths passed `gridSpec.columns`, so a horizontally-paged Apps page had 4
rows rather than 6: 16 apps in four sparse bands with large gaps instead
of a dense 4×6 of 24.

### Fixed — the Home key did nothing

`MainActivity` is `launchMode="singleTask"`, so the system delivers a
Home press as a new Intent, and there was no `onNewIntent` override at
all — the event was dropped. With the Apps screen open, pressing Home did
nothing whatsoever, leaving Back or the swipe gesture as the only way
out. It now closes the Apps screen, leaves edit mode, and returns to the
user's chosen default page, which is what the Home key means on any
launcher.

### Fixed — the app drawer was showing 10 apps out of 405

The most serious bug in this pass, and one that made ReLite Home
effectively unusable as a real launcher rather than merely imperfect.

`AndroidManifest.xml` declared no `<queries>` element, on the stated
reasoning that Android "exempts the app holding the default-launcher role
from package-visibility filtering" when discovery goes through
`LauncherApps`. That reasoning was tested directly on the RMX5303 and is
**false on this device**: ReLite was made the genuine `ROLE_HOME` holder
(`cmd role get-role-holders android.app.role.HOME` → `io.relite.home`),
its process was restarted so visibility would be recomputed, and the
drawer still listed the same 10 apps out of 405 installed.

Fixed by declaring the narrow, correct element — a `<queries>` intent for
`MAIN`/`LAUNCHER`, which makes exactly the apps publishing a launcher
activity visible. `QUERY_ALL_PACKAGES` remains undeclared and unnecessary:
it is far broader, is policy-restricted on Play, and would expose apps a
launcher has no business seeing. After the fix the drawer lists the full
set.

### Fixed — Home painted a black sheet over the wallpaper

`Theme.ReliteHome.Home` set `windowShowWallpaper=true` with
`windowBackground=@null`. With `@null` there is no window background
drawable and the framework falls back to the theme's
`android:colorBackground` — this launcher's opaque dark neutral — so Home
rendered solid black while a real `ImageWallpaper` was set and visible
under every other launcher. Now explicitly
`@android:color/transparent`, which is what AOSP's own launcher themes
use, and the status/navigation bar colors are pinned transparent in the
same theme so nothing opaque can creep back over the wallpaper.

With the wallpaper finally rendering, the dock's opaque fill read as a
heavy block cut out of it, so `relite_dock_background` is now translucent
(`#C2`). The context menu, which shared that color, was given its own
opaque `relite_menu_background` — a floating menu has to stay readable
whatever the backdrop. The dock alpha is ReLite's judgement; the design
guide publishes no value for it.

### Fixed — four more, all found by running the app on the RMX5303

None of these were caught by the test suite; all were found by looking at
the running launcher.

- **Apps screen Custom order rendered as a single full-width column**
  instead of a paged 4-wide grid. `item_app_drawer`'s `match_parent`
  width means "fill one span" in a *vertical* `GridLayoutManager`, but
  Custom order uses a *horizontal* one, where width is the scrolling axis
  and therefore unconstrained — so every tile took the full viewport
  width. Fixed with an explicit `DrawerGridAdapter.itemWidthPx`. (The
  first attempt at the fix set that width from inside a layout pass via
  `doOnLayout`, where the adapter's `notifyDataSetChanged` is ignored and
  it silently did nothing; it is now computed from the display width.)
- **The Apps screen's "More options" and Sort menus were still system
  `AlertDialog`s** — square-cornered system dialogs in an otherwise fully
  rounded UI. The v0.5.0 pass replaced the system menu at every
  *long-press* site and missed these two, which open on a plain click.
  Both now use the shared `LauncherContextMenu`.
- **Context menus opened off-screen near screen edges.**
  `showAsDropDown` always places the card *below* and start-aligned to
  its anchor, so opening one from the bottom-anchored "More options"
  button pushed it off the bottom edge, and its width ran off the right.
  The card is now measured first and flipped above / shifted inboard as
  needed, with the pop-in pivot following. This also fixes dock-icon
  long-presses, which had the same latent problem.
- **A solid blue status bar**, introduced in this very pass by setting
  `colorPrimaryDark`, which Android maps to the status-bar color. Wrong
  for a launcher, whose status bar must stay transparent for the
  wallpaper. Removed, and the Home theme now pins `statusBarColor` and
  `navigationBarColor` transparent explicitly so this cannot recur.

### Widgets — drag/resize edit mode

- Long-pressing a widget now offers **Edit**, opening a real
  `WidgetEditOverlayView` with live drag-to-move, corner-handle
  drag-to-resize, Done and Remove. Both gestures snap to the real
  measured (non-square) `GridMetrics` and fire a per-snap haptic tick.
- Implemented as a bitmap-snapshot overlay added as a same-span cell,
  not as touch handling on the widget itself: an `AppWidgetHostView`
  owns its own input (its scroll views, its buttons), which is exactly
  why widgets never had the live drag that apps and folders already did.
- The existing "Move to page…" / "Resize" dialogs stay in the context
  menu unchanged, as the accessible non-drag path every other drag
  affordance in this launcher already has.
- A real `StackOverflowError` found live on the RMX5303: committing a
  move/resize removes the overlay, and removing the active touch target
  makes Android synchronously redeliver `ACTION_CANCEL` to it, which
  re-entered the same close-and-remove logic until the stack overflowed.
  Fixed with a re-entrancy guard plus deferring the removal via `post{}`.
- `WorkspaceGridLayout.updateCellPosition`/`updateCellSpan` reposition an
  already-added child mid-drag without a remove/re-add cycle.

### Widgets — portable descriptors and rebind on import

Layout export previously **dropped widgets entirely**. It no longer does.

- `exportPortable` now writes a `PortableWidget` descriptor per widget —
  provider component, grid position, and span — under a separate
  top-level `"widgets"` key. `importPortable` returns them as
  `ImportResult.Success.pendingWidgets`.
- The internal workspace schema stays at **3, deliberately un-bumped**:
  `"widgets"` is written only into an export, never the internal file,
  and is strictly ignorable — so an older ReLite build reading a newer
  export degrades to exactly its old behavior (layout imports, widgets
  dropped) instead of rejecting the whole file over a schema number.
- `WidgetRestorer` rebinds them after the imported workspace commits:
  a silent `bindAppWidgetIdIfAllowed` pass first, then a per-widget
  system consent queue in `HomeSettingsActivity` for the rest. Every
  allocated id is released on every failure path.
- The result is reported **itemised** — restored, restored-but-needs-
  setting-up-again, provider-not-installed, consent-refused, no-room —
  rather than a bare "imported" that would let a half-restored layout
  read as a complete one.
- The whole in-progress restore survives Activity recreation
  (`PortableWidget.flatten`/`unflatten` + `WidgetRestorer.Result.saveTo`/
  `restoreFrom`). The system consent dialog can recreate its caller on a
  low-memory device — the same hazard `WidgetPickerActivity` already
  guards its own pending flow against — and without this the queue, the
  in-flight request and the running tally were all lost: the result
  callback would return early on a null in-flight, the already-allocated
  widget id would leak, and every remaining widget would silently never
  be offered. Found by reviewing this pass's own new code against the
  standard the widget picker already set, not by a failing test.

Two limits here are platform limits, not gaps, and are documented as
such: a rebind necessarily allocates a fresh `appWidgetId`, so a
**configured** widget returns unconfigured (its settings live in the
provider's storage keyed by the old id); and `BIND_APPWIDGET` is a
signature/privileged permission, so a third-party launcher can never
restore silently the way Samsung's own privileged restore path does.

### Motion and haptics

- `MotionTokens` gains a shared `popIn` appear transition (grow + fade
  from a caller-chosen pivot) and now drives the context menu (pivoted
  at the anchor it dropped from), folder open, and edit-mode enter/exit.
- Edit mode's local `EDIT_MODE_ANIM_MS = 200L` is gone; that transition
  now takes its duration and curve from `MotionTokens`, so it cannot
  drift out of step with the rest again. Its overlay fade is coordinated
  with the workspace scale instead of snapping in at full opacity, and
  exit hides the overlay only once the fade has actually finished —
  previously `visibility = GONE` cut the animation off at its first frame.
- Haptics extended: `LONG_PRESS` on context-menu open and edit-mode
  entry, `KEYBOARD_TAP` per grid snap during a widget move/resize.

### Accessibility

- Fixed-height text rows that would clip at a large system font scale are
  now `minHeight` + `wrap_content`: context-menu rows (which also gained
  real vertical padding), the Apps screen search field, and the widget
  picker's empty-state button. The 48dp touch target is preserved at
  default scale.
- The widget edit overlay labels itself for TalkBack and marks its
  decorative snapshot image `importantForAccessibility="no"`.

### Fixed

- Two dead parameters removed (`HomePageFragment.resolveWidgetProviderLabel`,
  `WidgetRestorer.consentRequest`); the Kotlin build is now warning-free.

### Verification

- 30 instrumentation tests on the physical RMX5303 (23 before this pass,
  7 new in `WidgetRestoreInstrumentationTest`) plus the JVM unit suite
  (`WorkspaceRepositoryTest` now 27 tests). Full suite run three times.
- Two assumptions about widget binding were **disproved on the device**
  and are recorded rather than glossed over: a host binding a provider
  from its *own* package is **not** exempt from the consent requirement,
  and `adb shell appwidget grantbind` does not work on this unit (exits
  137, `dumpsys appwidget` shows `Grants:` empty). The consequence is
  that the silently-granted branch cannot be set up on this hardware, so
  the system's half of a restore is left honestly undriven; ReLite's own
  half — descriptor parsing, allocation, exact position/span placement,
  every failure report, and no leaked host ids — is covered in full,
  including the post-consent `restoreOne(alreadyBoundId=…)` path.

### Known gaps

- **`HomeSettingsInstrumentationTest.theWidgetLabelsToggleReflectsAndPersistsThePreference`
  is intermittent** — across this milestone's runs it failed 3 times out
  of 8 full-suite runs, and passes every time the class is run in
  isolation. It predates this work. The most likely cause is Espresso
  resolving a stale window left resumed by an earlier test class (the
  toggle's click reports success, yet the preference is unchanged
  afterwards), but that was not confirmed, and no fix is claimed. It is
  recorded rather than left as an unexplained flake in the history.
- **Home geometry, theme palette, motion curves, haptic patterns**: still
  not measured against a real Samsung device, because none was available.
  These stay `LOW`/`MEDIUM` confidence in the parity matrix. See that
  document's new "ceiling" section — ReLite deliberately copies no
  Samsung assets or resource values, so pixel-level parity is a non-goal,
  not a backlog item.
- **Performance**: still no jank/frame-timing measurement or fresh
  controlled A/B against the stock launcher; the v0.4.1 −59.2% PSS figure
  is not re-validated or claimed for this build.
- **Release signing**: no real release key exists; packaged artifacts
  remain debug-signed, honestly labeled as such.

## [0.5.0] — 2026-08-10

A One UI-inspired transformation pass on ReLite Home on top of v0.4.1's
hardening work — dynamic Home grids, a rebuilt Apps screen, a real Home
edit mode, a redesigned Settings screen, a shared custom context menu, and
several genuine correctness fixes found by actually running the app live
on the RMX5303 during this pass, not just compiling it. See
`docs/design/one-ui-current-reference.md` for the sourced Samsung
references this work is grounded in, and
`docs/design/one-ui-parity-matrix.md` for an honest per-surface parity
grade — several major surfaces (portable widget rebind, the full
stress/jank benchmark campaign) are explicitly out of scope for this
pass; see Known gaps below.

A second completion pass on top of the above (same v0.5.0 milestone,
same physical-device verification discipline) closed several of the
gaps this section originally recorded: a `LauncherHost` interface fixes
a real fragment-restoration hazard (activity-set lambda fields on a
`HomePageFragment`/`AppDrawerFragment` reused rather than recreated by
`FragmentStateAdapter` after process death silently stayed null); the
Home↔Apps swipe gesture, runtime icon-size scaling, drag-app-onto-app
folder creation, expanded 2×2 folders, and drag page reordering are now
implemented and covered by live instrumentation. See the updated Known
gaps below for what's still genuinely outstanding.

### Reliability

- `ReliteHomeApplication.onCreate()` now creates every dependency the
  package-reconciliation listener touches before `AppRepository.start()`
  registers the `LauncherApps` callback, closing a window where an early
  package broadcast could hit an uninitialized `lateinit` property.
- Widget-provider reconciliation now sources from
  `AppWidgetManager.installedProviders` with exact `package/providerClass`
  comparison, instead of incorrectly inferring availability from launcher
  activities (a widget-only provider has no launcher icon at all).
- `MainActivity.launchApp` is now the one canonical safe launch path:
  catches `ActivityNotFoundException`/`SecurityException`/
  `IllegalArgumentException`/`IllegalStateException`, shows feedback, and
  immediately reconciles the dead component instead of crashing.
- `WorkspaceRepository.save()` now validates the candidate itself before
  writing — defense-in-depth against ever persisting a structurally
  invalid workspace, even from a caller that bypasses `WorkspaceController`.
- `WorkspaceController.moveHomeAppIntoFolder`/`convertHomeAppToFolder` fold
  what used to be two separate persisted saves into one transaction,
  closing a real app-duplication risk on a save failure between them.
- Reset and import now delete orphaned `AppWidgetHost` bindings
  (`WorkspaceController.replaceWorkspaceSafely`) instead of leaving every
  prior widget's host allocation behind forever.

### Workspace / Home grid

- Workspace schema v3 adds a persisted `HomeGridPreset` (4×6 default, 5×6)
  matching the two grids current One UI 7 actually offers. Schema 1/2
  files migrate for free — 4×6 is a strict superset of the old fixed 4×5
  grid, so no existing item ever moves.
- `WorkspaceController.changeHomeGrid()` reflows any item that no longer
  fits under a new preset (own-page free rect, then a new page) — items
  are never dropped.
- A persisted `defaultPage` (also schema v3) — set from the new edit-mode
  page strip — determines which page Home opens on first launch.
- Real, non-square `GridMetrics` (from the actual measured grid) now drive
  both a widget's initial span and its resize notification, replacing a
  width-derived square-cell approximation that was wrong on both axes for
  any non-square grid — which is now always, since 4×6/5×6 are never square.

### Home

- Long-pressing empty Home now enters a real edit-mode overlay (workspace
  scales down slightly, a page strip, and Wallpaper and style/Widgets/Home
  screen settings bottom actions) instead of a plain `AlertDialog` list.
- "Show app labels" and "Show Apps screen button" toggles, actually wired
  into rendering (not just persisted and ignored).
- A shared, rounded-card `LauncherContextMenu` replaces the system
  `PopupMenu` at every long-press site (Home items, Apps screen, dock,
  folder members).

### Apps screen

- `AppsSortMode.CUSTOM` (default): horizontal paged grid, drag-reorderable
  via `ItemTouchHelper`, order persisted. `AppsSortMode.ALPHABETICAL`:
  vertical, always alphabetical — matches the axis coupling described in
  the sourced Samsung reference.
- Search bar moved to the bottom of the screen; a real bug in that move was
  found and fixed live on the RMX5303 (see Fixed, below).
- A "More" menu (Sort chooser + Home screen settings), reachable from both
  the Apps screen and Settings.

### Settings

- Replaced the flat unsectioned button list with grouped sections (Home
  screen, App and widget style, Apps screen, Appearance, Layout backup,
  Default launcher/About) and a real Home-grid picker (tappable 4×6/5×6
  cards driving the live `WorkspaceController`, not just a preference flag).

### Fixed

- A real bug found live on the RMX5303: the new bottom search bar's fixed
  dp margin placed its tappable area inside the device's bottom gesture-nav
  swipe zone, so a tap meant to focus the field was swallowed by the system
  gesture instead of reaching the `EditText` (caught by
  `DrawerSearchTest` failing on-device). Fixed by adding the real
  `systemBars` bottom inset to the margin, the same way `MainActivity`'s
  dock margin already handled it.
- A real tooling gap found while stress-testing: `monkey -p io.relite.home`
  cannot launch this app at all, because it deliberately has no
  `CATEGORY_LAUNCHER` activity (v0.4.1, so it doesn't list itself in its
  own drawer) — `monkey` resolves its target that way and aborts. Worked
  around with direct `input tap`/`input swipe` injection in
  `scripts/stress-relite-home.sh`.

### Testing

- 147 Kotlin JVM tests (up from 118 in v0.4.1), 13 instrumentation tests
  (up from 9) — all passing live on the physical RMX5303 after every
  feature batch in this pass, not just at the end.
- Added `docs/design/one-ui-current-reference.md` (every claim sourced and
  dated) and `docs/design/one-ui-parity-matrix.md` (per-surface grade with
  a confidence qualifier, honest about what's LOW/not implemented).
- Added `scripts/stress-relite-home.sh` and ran it for real; results in
  `benchmarks/results/RMX5303/v0.5.0-stress-pass.md` — no crash/ANR
  attributable to ReLite Home across a 300-event randomized input pass,
  cold start ~780-820ms across 3 runs. Explicitly a scoped pass, not the
  full multi-hour campaign (no jank measurement, no 5-minute idle CPU
  figure, no fresh stock-launcher A/B).

### Known gaps

Deliberately not attempted or not completed this pass — recorded honestly
rather than silently glossed over. Items struck through below were true
gaps as of the first v0.5.0 pass and have since been closed in a second
completion pass on the same milestone; see the dated bullets above.

- **Folders**: drag-app-onto-app creation and an expanded/enlarged 2×2
  folder view are now implemented (real member icons, direct launch,
  Enlarge/Shrink context-menu actions) and covered by live instrumentation
  (`HomeDragToFolderInstrumentationTest`, `ExpandedFolderInstrumentationTest`).
  ~~Still missing: **Apps-screen-Custom-mode folders**~~ — implemented:
  a `DrawerFolder` (persisted in `AppsPreference`, no relation to a Home
  `WorkspaceItem.FolderIcon`) occupying a slot in Custom order, created
  via a menu-driven "Add to Apps folder" flow (not drag — Custom order's
  own long-press already starts a reorder drag with no merge detection).
- **Widgets**: ~~no debug test widget fixture~~ — implemented
  (`ReliteTestWidgetProvider`, `src/debug/` only). ~~no picker preview
  cards/grouping~~ — implemented: the picker now groups by source app
  with real preview-image cards (provider preview API +
  icon/dimension fallback). Still missing: no drag/edit-mode resize
  handles (still +/- buttons), no portable widget descriptor/rebind-on-
  import flow — export still drops widgets entirely, same as v0.4.1. An
  opt-in "Show widget labels" overlay (off by default) is now
  implemented, closing the widget-labels gap below.
- ~~**Icon size scaling**: not implemented~~ — implemented: `IconSizePreference`
  (Small/Default/Large) plus a size-aware `IconCache` keyed on
  `"$pkg/$activity@$sizePx"` and an `IconNormalizer` that renders
  adaptive icons at full bounds and legacy icons with a documented inset.
- ~~**Widget labels toggle**: no widget label UI exists at all to toggle~~ —
  implemented as an opt-in overlay; see Widgets above.
- ~~**Home↔Apps swipe gesture**: not implemented~~ — implemented as an
  Activity-level `dispatchTouchEvent` gesture with live-following
  translation and fling/threshold settle, covered by
  `HomeAppsSwipeInstrumentationTest`. The dock's Apps button default stays
  on regardless, since a swipe gesture and an explicit button are not
  mutually exclusive affordances.
- ~~**Page reordering**: pages can be added/removed/set-default, not
  dragged into a new order~~ — `WorkspaceController.reorderPages` plus a
  drag-capable edit-mode page strip now exist; the accessible non-drag
  "Move page left/right" menu fallback ships alongside it.
- **Motion/haptics**: `MotionTokens` (duration/easing constants) now drives
  the Home↔Apps swipe settle and folder drag-hover scale, and a
  `HapticFeedbackConstants.LONG_PRESS` fires on drag pickup — but this is
  still partial: most other surfaces (context menus, folder open/close,
  widget edit) have no dedicated motion or haptic treatment yet.
- **Accessibility**: existing 48dp targets and non-drag alternatives
  preserved; new surfaces (edit mode, context menu, Apps sort) were not
  run through a dedicated TalkBack/font-scale pass this session.
- **Performance**: no jank/frame-timing measurement, no 5-minute idle CPU
  figure, no fresh controlled A/B against the stock launcher for v0.5.0 —
  the v0.4.1 −59.2% PSS figure is not re-validated or claimed for this
  build.
- **Physical validation**: covered by live instrumentation runs and the
  scoped stress pass above; no dedicated reboot/upgrade-continuity/
  full-feature-matrix pass on physical hardware beyond that.
- **Release signing**: no real release key exists; packaged artifacts
  remain debug-signed, honestly labeled as such.

## [0.4.1] — 2026-08-10

A hardening pass on ReLite Home's widget pipeline, package lifecycle, and
transactional persistence — the v0.4.1 plan's first-priority items — plus
folder reorder/preview. Validated with the full Python + Android JVM +
instrumentation gate on the RMX5303 after every change; the full Samsung
One UI visual-parity redesign (plan Phase J) and the stress/jank
measurement pass (Phase I) are not part of this release — see "Known
gaps" below.

### Fixed (widget pipeline)

- `WidgetPickerActivity` never called `adapter.submitList(providers)`,
  so the picker was always empty regardless of installed providers —
  the actual release blocker this plan's Phase A opened with.
- Added an explicit empty-state UX (no providers installed) instead of a
  blank Activity, and pending widget-id/provider selection now survives
  Activity recreation via `onSaveInstanceState`.
- Widget removal is now transactional (`WidgetLifecycle.removeWidgetSafely`):
  persists the workspace removal first and only deletes the AppWidgetHost
  id if that succeeds, instead of the old delete-then-persist order that
  could strand a dangling reference on a failed save.
- A widget binding `AppWidgetManager.getAppWidgetInfo()` can't resolve
  (uninstalled provider, never-actually-bound id) now renders a removable
  "Widget unavailable" placeholder instead of crashing.
- Resizing a widget now notifies the provider of its real rendered size
  via `updateAppWidgetSize` on every rebuild of its host view.

### Fixed (package lifecycle)

- Package-change reconciliation now diffs the exact current
  `package/activity` component set instead of package names only — a
  package that renamed its launcher activity while staying installed used
  to leave a dead shortcut pointing at the old activity forever
  (`WorkspaceController.removeStaleComponents`).
- Widget providers that disappear are now cleaned up too, including host
  id deletion (`WorkspaceController.removeWidgetsForMissingProviders`).
- The icon cache is invalidated on every reconciliation pass, so an
  updated app's icon doesn't stay stale.
- Package-change listener dispatch is now explicitly main-thread-safe and
  snapshot-iterated, so a listener unsubscribing mid-dispatch can't throw
  or get silently skipped.
- ReLite Home no longer lists itself in its own app drawer: `MainActivity`'s
  intent-filter no longer declares `CATEGORY_LAUNCHER` (only `HOME`), and
  `AppRepository.loadAll()` explicitly filters out ReLite Home's own
  package as a second line of defense.

### Fixed (transactional persistence)

- `WorkspaceController.addApp`/`addWidget` used to persist a new page as
  its own save, then the new item as a second, separate save — a failed
  second save left an empty extra page committed to disk. Both now fold
  into one `mutate()` call.
- Added `WorkspaceController.moveToNewPage(itemId)` as a single-transaction
  replacement for the UI's old "add a page, then move the item there" two-call
  pattern, which had the same orphan-page failure mode.
- `HomeSettingsActivity.exportTo()` reported export success even when
  `openOutputStream()` returned null (`?.use` on a null receiver never
  throws). Layout import and reset now check `replaceWorkspace()`'s result
  instead of assuming success.

### Added (folders)

- Folder member reorder ("Move left"/"Move right" from the member
  long-press menu), backed by `WorkspaceController.reorderFolderMembers`.
- A real 2x2 preview of a folder's first four member icons on the Home
  grid (`FolderPreview`), replacing what used to render as an empty icon
  slot with only a label.

### Testing

- Added JVM tests for every fix above with a real state assertion (not
  just "didn't throw") — including simulated persistence failures during
  auto-page-creation and `moveToNewPage` proving no orphan page is left.
- Added `FolderInstrumentationTest`, exercising real folder creation and
  Home-grid rendering (including the new preview) through the actual
  `MainActivity`/`HomePageFragment` path on-device.
- Full instrumentation suite: 9/9 passing on the RMX5303 (Android 15)
  after every change in this pass.

### Known gaps

Not completed in this pass, honestly carried forward rather than claimed
done:

- Debug-only test widget fixture, widget-picker recreation/cancellation
  instrumentation matrix, and live on-device widget bind/resize/remove
  validation (plan sections 5-9, 15) — the picker-population fix itself
  was verified via code + JVM-testable logic, not a live bind/configure
  round-trip with a real provider this pass.
- Portable widget backup / rebind-on-import (Phase G).
- Structured `AppChangeEvent` sealed hierarchy (plan section 16) — package
  lifecycle was hardened (exact reconciliation, main-thread/snapshot-safe
  dispatch, icon invalidation, self-filter) without the full event-type
  refactor; the existing single-callback `onAppsChanged` API was kept.
- Fragment host-contract refactor away from transient callback fields
  (Phase C, section 28) — recreation already passes instrumentation
  (`ActivityRecreationTest`, `ThemeRecreationTest`, `FolderInstrumentationTest`
  recreate cycle), but the specific process-death case of a `ViewPager2`-restored
  `HomePageFragment` never receiving fresh callbacks was not independently
  re-audited or fixed this pass.
- Stress/memory/jank measurement pass (Phase I) and the full Samsung One
  UI visual-parity redesign (Phase J) — both explicitly large,
  multi-surface efforts out of scope for this hardening-focused release;
  not attempted rather than partially done and mis-reported.
- Final controlled A/B benchmark and multi-sample cold start were not
  re-run this pass — no UI/behavior change in this release should affect
  the v0.4.0 measured numbers, but they are not re-validated figures.

## [0.4.0] — 2026-08-10

ReLite Home's interactive editable-workspace UI — the thing v0.2.0/v0.3.0
explicitly left at `WorkspaceController`-level only — is now wired up
and validated live on the RMX5303:

### Added (ReLite Home)

- Cell-aware `WorkspaceGridLayout` rendering each item's real
  (column, row, span) instead of list-order placement.
- Long-press-then-drag to move apps/folders/widgets within a page, and
  across pages via an edge-hover timer (`DragOverlay`, `EdgeHover`) that
  keeps the dragged icon rendering while `ViewPager2` swaps the
  underlying page fragment mid-gesture.
- Fully editable dock (remove, App info, drag-reorder, pin from
  drawer/home, "Add to Home" from the dock).
- Folder creation/rename/add-member/remove-member/delete through
  `FolderSheetDialog` and a shared `FolderPicker`.
- Page management (add page, remove an empty page, "Move to page…").
- Complete widget pipeline: pick, allocate, bind (with permission
  fallback), configure, place, persist, resize, remove, with widget-id
  cleanup on every failure path.
- Layout export/import via the Storage Access Framework, and a Home
  Settings screen (export/import/reset/default-launcher helper/About).
- System wallpaper behind the home screen.
- Deterministic tiered drawer search (exact > prefix > word-prefix >
  all-tokens-any-order).
- Explicit System/Light/Dark theme selection (`ThemePreference`).
- WindowInsets handling for the workspace, drawer, and dock (edge-to-edge,
  no hardcoded padding constants).
- Accessibility: contentDescriptions for folders/widgets, 48dp minimum
  touch targets, non-drag alternatives for every drag-based move.
- The mandatory `androidTest` instrumentation suite
  (`app/src/androidTest/`) and `scripts/test-launcher-emulator.sh`.

### Fixed (found via live device testing on the RMX5303)

- `ActivityScenario.state` was read from inside `onActivity{}` in the
  instrumentation suite's `MainActivityTest` — that callback runs on the
  main thread, and `getState()` explicitly forbids being called from it;
  crashed the very first time the suite ran against real hardware.
- `IllegalStateException: already recycled once`
  (`ViewGroup$TouchTarget.recycle`) during a real long-press-then-drag
  gesture — `HomePageFragment`'s drag start/finish mutated the view
  hierarchy (adding/removing the drag proxy, potentially rebuilding the
  pager) synchronously from inside the touch-dispatch callback that
  triggered them. Both are now deferred via `post {}`.
- `MainActivity` replaced `pager.adapter` with a brand-new
  `HomePagerAdapter` on every single workspace edit — exactly the
  anti-pattern `FragmentStateAdapter`'s own docs warn against. Now one
  adapter instance is created per Activity and updated via
  `notifyDataSetChanged()` with a generation-based stable id.
- `relite benchmark-launchers` crashed (`MeasurementFailedError`
  uncaught) the first time it was run against a real device for this
  release: `device.yaml`'s stock-launcher activity name was stale for
  this ROM build, and a label with zero valid samples crashed result
  serialization instead of being reported as a partial failure. Both
  are fixed (`devices/realme/RMX5303/device.yaml`, `relite/benchmark.py`,
  `relite/cli.py`).

### Validated live on the RMX5303 (Android 15)

Full `androidTest` instrumentation suite (8/8) via
`connectedDebugAndroidTest`; manual exercise of the drawer, search,
add-to-home, cross-page "Move to page…", dock pin, Home Settings
(export/import picker, theme switch to Dark and back across the
recreation, reset with confirmation); a full device reboot with ReLite
Home surviving as the active default launcher; a controlled, same-
session, alternating-order A/B benchmark against the stock launcher
(`relite benchmark-launchers`, 7 samples each) — settled PSS 53,020 kB
vs. 129,958 kB, **-59.2%**; and a 15-second idle-CPU spot-check showing
0% CPU with no interaction, consistent with the no-polling code audit.

### Known gaps

Widgets don't support drag (same-page or cross-page) — `AppWidgetHostView`
owns its own touch input, so they move through "Move to page…" instead.
No dedicated frame/jank or memory-stress pass has been run against this
build. Package add/update/remove reconciliation while ReLite Home is
foregrounded was not separately live-tested this pass (the reconciliation
logic itself predates v0.4.0 and has JVM test coverage).

## [0.3.0] — 2026-08-09

A correctness- and packaging-focused release: the profile engine is now
genuinely bidirectional and baseline-aware, the CLI is a self-contained
installable wheel, release signing is cryptographically verified rather
than assumed, and benchmark methodology no longer fabricates failed
samples. ReLite Home gets targeted correctness fixes (atomic
persistence, stable menu handling); the interactive editable-workspace
UI (drag-to-move, dock/folder editing dialogs, live widget rendering,
theming, wallpaper) remains `WorkspaceController`-level only, unchanged
from v0.2.0 — not attempted this pass, and not claimed as done.

### Fixed (correctness)

- Profile transitions only worked "loosening from stock" — a package
  `maximum` uninstalled was invisible to the old planner
  (`list_packages()`-only) when moving back toward `safe`/`performance`,
  so `maximum -> performance` silently failed to restore it.
  `relite/profile_planner.py` computes each package's desired state from
  an explicit pre-ReLite baseline plus the target profile's action —
  `keep` means "whatever the baseline was", never "must be enabled" —
  and transitions now work directly in both directions. Verified live on
  the RMX5303: `performance -> maximum -> performance` with no
  intermediate restore, `relite status` confirming a clean result both
  times.
- Snapshot schema bumped to v2 with an explicit `managed_package_states`
  baseline map; v1 snapshots still load, falling back to deriving the
  same baseline from the full package inventory.
- Snapshots are now bound to the physical device they came from
  (`relite/baseline.py`) — checked against device model, a pseudonymous
  device key, and firmware fingerprint, distinguishing "wrong device"
  from "same device, OTA since this was taken" instead of silently
  trusting a possibly-stale baseline.
- `state.json`'s `record_profile_applied()`/`record_snapshot_restored()`
  each used to construct a fresh `DeviceState`, silently discarding
  whatever the other had recorded. Both now load-modify-save.
  `baseline_snapshot` is explicitly recorded rather than inferred from a
  snapshot happening to be named "stock"; `restore --all` uses it.
- `apply_plan()`/`build_plan()` replaced by transitions executed
  directly from the same planner `relite plan` renders — there is no
  longer a second, independently-derived way to decide a package's
  command. Journal bumped to schema v3 (transaction `apply_id`,
  baseline/requested/observed state); rollback goes through the
  verified `package_state` engine instead of replaying a stored raw
  command string, with old-journal-string fallback only when a record
  genuinely lacks the newer explicit state fields. New `relite undo`
  reverses only the most recent apply transaction, distinct from
  `relite restore --all`'s return-to-baseline.
- Profile integrity now verifies managed tuning (animation scale) via a
  live device, not package state alone — a failed `settings put` can no
  longer hide behind an otherwise-clean package report.
- `list_packages()` used `pm` output regardless of each query's exit
  code; a failed/offline query silently became an empty package set
  (every profile's plan then looked like "already matches, nothing to
  do"). Now raises `PackageInventoryError` on any failed query or a
  violated set invariant (e.g. disabled ⊄ all).
- `AdbClient.require_single_device()` returned the requested `--serial`
  unconditionally as soon as *any* device was usable, without checking
  that serial was actually connected.
- ReLite Home: `FileStorage` used plain `File.writeText()` — a process
  death mid-write could leave `workspace.json` truncated with no
  fallback. Now uses `android.util.AtomicFile`. A corrupt/unreadable
  workspace file is preserved to a sibling `.corrupt` file before
  falling back to an empty in-memory workspace, instead of being
  silently destroyed the moment the next edit triggers a save.
- ReLite Home: long-press menus compared `menuItem.title` against a
  localized string to decide the tapped action; now uses stable integer
  menu item IDs.
- `devices/realme/RMX5303/device.yaml`'s `bootloader_investigated: false`
  / `gsi_investigated: false` predated research that had already
  happened; replaced with structured verdicts taken directly from
  `research/bootloader.md`/`research/treble-gsi.md`'s existing
  conclusions.

### Fixed (security)

- The pseudonymous per-device key (`relite/device_identity.py`) was
  described as "non-reversible" but was a bare `sha256(serial)[:8]` — an
  unsalted hash of a short, structured ADB serial is realistically
  brute-forceable offline. Now HMAC-SHA256 keyed by a random per-install
  salt (`.local/.device_salt`, gitignored). Found while validating
  snapshot ownership: the privacy sanitizer's `android_id_hex`/
  `bearer_token` patterns happened to match a 16-hex-char serial and a
  20-hex-char device key respectively, mangling both on save — both are
  now exempted from the general sanitizer pass (they're pseudonymous by
  construction, not something that pass needs to protect).
- User-controlled local artifact names (snapshot names) validated
  against a conservative charset before being used to build a path under
  `.local/` — `relite snapshot --name ../../escape` can't do what it says.
- Release-signing status is now verified cryptographically
  (`apksigner verify` against the actual built APK), not inferred from
  whether credentials were configured at build time. A partially-
  configured signing setup (1-3 of 4 credentials) now hard-fails the
  Gradle build instead of silently producing an unsigned release build
  something downstream could mislabel.

### Added

- `relite/data_paths.py` + `relite/resources/` make the installed wheel
  self-contained: `RELITE_DATA_DIR` (or `--data-dir`) > a source
  checkout's `profiles/`/`devices/` > packaged data via
  `importlib.resources`. Verified end-to-end — built the wheel,
  installed it into a venv that never saw the checkout, confirmed CLI
  and RMX5303 package-database loading both work from `/tmp`. Previously
  a release blocker (a `pip install`'d wheel had no built-in data at all
  outside a checkout).
- `scripts/release_manifest.py` generates `dist/release-manifest.json`
  (version, git commit, APK/wheel name+SHA-256, verified signed status,
  certificate DN, build timestamp); optional certificate pinning via
  `docs/release-signing-cert.sha256` (public digest only).
- Stricter `packages.yaml` schema validation: confidence/risk enums,
  dependency entries validated as real package names, duplicate package
  entries rejected, and profile-monotonicity checking (an action map may
  not get less aggressive from safe to performance to maximum unless
  explicitly documented via `monotonicity_exception`).
  `rollback.supported: false` combined with any non-keep action is now a
  schema error. `find_protected_conflicts()` flags a package that's both
  protected and has a non-keep classified action.
- `relite/device_metadata.py` validates `device.yaml` through a real
  loader (model/support_status enum, every benchmark/PSS target's
  package+component name) instead of a bare `yaml.safe_load()`.
- Benchmark correctness: `TimingStats`/`PssStats` raise
  `MeasurementFailedError` when constructed with zero samples instead of
  silently substituting a fabricated `0.0` — a genuine `TotalTime: 0` is
  still recorded as a valid sample, only "no sample at all" is treated
  as a failure. `--runs`/`runs` reject non-positive values.
  `PssStats`/`measure_pss_settled_stats()` make PSS a proper statistical
  measurement (median of N samples) instead of one reading.
  `run_launcher_ab_benchmark()` adds a controlled, single-session,
  alternating-order comparison mode between two launcher targets.
- CI: wheel build + fresh-venv-outside-checkout install test,
  `relite/resources/` freshness check, `device.yaml`/profile schema
  validation through real loaders, and a release-packaging test that
  asserts the no-secrets path produces an honestly-labeled debug
  artifact.

### Notes

- ReLite Home's interactive editable-workspace UI (drag-to-reposition,
  dock editing, folder creation/editing, live widget rendering, layout
  import/export, wallpaper, theming, accessibility pass, insets
  handling) is unchanged from v0.2.0 — `WorkspaceController` supports
  all of it at the domain-logic level; none of the interactive UI for it
  was built this pass. Neither were structured package-change events,
  main-thread listener dispatch, or lifecycle-safe fragment callback
  restoration.
- Device/profile package counts, cold-start numbers, and ReLite Home PSS
  are unchanged from v0.2.0 — no package action changed, and the launcher
  UI work that would justify re-measuring wasn't done this pass.
- Manual, human-only validation (calls, SMS, GPS, fingerprint, Bluetooth
  audio, banking apps) unchanged — see
  `docs/RMX5303-validation-checklist.md`. Screenshots remain blocked on
  the device's lock screen (owner PIN required; not attempted).

## [0.2.0] — 2026-08-09

Correctness, security, and multi-device-safety hardening of the v0.1.0
engine, plus a domain-logic foundation (`WorkspaceController`) and a
first, minimal editable-workspace UI for ReLite Home. See
`benchmarks/results/RMX5303/v0.2.0.md` for full revalidated numbers.

### Fixed (correctness)

- `restore_from_snapshot()` only handled the enabled/absent case
  correctly; a package the snapshot wanted disabled but the device now
  has fully absent (or vice versa) silently stayed wrong. Package state
  is now an explicit `PRESENT_ENABLED`/`PRESENT_DISABLED`/
  `ABSENT_FOR_USER` model (`relite/package_state.py`) with a verified
  minimal transition for every `current -> desired` pair.
- Settings restore was animation-scale-only. It's now driven by an
  explicit ReLite-managed-settings list (animation scale + Private DNS)
  that distinguishes "value existed" from "key was absent", and never
  blindly turns Private DNS off if the user had it configured before
  ReLite ran.
- `apply_plan()` trusted `pm`/`cmd package` output text as sufficient
  evidence of success. It now re-queries live package state once after
  a plan's commands run and compares against what each action should
  have produced — the authoritative check.
- `check_profile_integrity()` folded "uninstall-user requested, disable
  observed" and "documented platform limitation" into the same flat
  PASS as an exact match. Now reports four distinct states — PASS /
  PASS_WITH_LIMITATIONS / DEGRADED / FAIL — and `relite apply` only
  records a profile as active when the result is clean enough to trust.
- ReLite Home: `MainActivity.onDestroy()` unregistered the
  `AppRepository`'s `LauncherApps` callback, silently breaking
  package-change updates after the first activity recreation (rotation,
  config change) for the rest of the process's life. `AppRepository` is
  now exclusively Application-owned.
- ReLite Home: `AppRepository.onAppsChanged()` grew an unbounded,
  never-pruned listener list — every time the app drawer was shown, a
  new listener was registered without disposing the old one. It now
  returns a `Subscription` the caller disposes.
- ReLite Home: four independent `IconCache` instances (dock, each home
  page, drawer, folder dialog) are now one process-wide instance owned
  by `ReliteHomeApplication`, trimmed under real memory pressure
  (`onTrimMemory`).
- `devices/realme/RMX5303/packages.yaml`: `com.heytap.market`'s reason
  text contradicted its own action map and `docs/profiles.md`; fixed
  the prose, not the (already-validated) action.

### Fixed (security)

- Every external value that reaches an `adb shell` command string (a
  Private DNS hostname, a package/component name) is now validated
  against real Android identifier syntax before being placed in a
  command, rejecting shell metacharacters by construction
  (`relite/validate.py`).

### Added

- Local state (`state.json`, `actions.jsonl`, snapshots) is now isolated
  per physical device — `.local/<model>-<sha256(serial)[:8]>/` — instead
  of keyed by model alone, which let two units of the same model (or a
  reconnected unit after another device was used) share and corrupt
  each other's rollback data. A one-time migration moves any pre-0.2.0
  layout into the new directory without destroying it.
- `relite apply` creates an automatic `auto-pre-relite` safety snapshot
  before its first live change on a device with no snapshot yet
  (`--dry-run` never touches snapshots).
- `relite/profiles.py` makes `profiles/{safe,performance,maximum}.yaml`
  the single, schema-validated source of truth for profile labels and
  animation scale — previously duplicated as hardcoded strings in
  `relite/cli.py` and `relite/tuning.py`.
- Action journal schema v2 (`requested_state`/`observed_state`/
  `verified` fields); v0.1.0 journals still load unchanged.
- ReLite Home: `WorkspaceController` — the single place workspace
  mutations go through (add/remove/move app, pages, dock, folder
  create/rename/membership, widget add/resize/remove), validated
  against the grid and persisted atomically, with 34 new unit tests.
  Dead shortcuts/dock entries/folder memberships for uninstalled
  packages are now cleaned up automatically.
- ReLite Home: minimal "Add to Home" (drawer long-press, auto-placed),
  "Remove from Home", and "App info" (standard Settings intent)
  affordances. Drag-to-reposition, dock editing, folder-editing
  dialogs, and the full widget add-flow are supported at the
  `WorkspaceController` level but not yet wired to interactive UI.
- Optional release signing (`android/relite-home/keystore.properties`,
  gitignored, or `RELITE_RELEASE_STORE_*`/`KEY_*` env vars for CI);
  `scripts/package-release.sh` packages a real signed `-release.apk`
  when credentials exist and the existing debug-signed fallback
  otherwise.
- `./gradlew lint` added to CI; fixed the three errors it caught
  (`MissingSuperCall` on the deprecated `onBackPressed()`, and two
  `ProtectedPermissions`/`QueryAllPackagesPermission` findings — see
  Removed, below).

### Removed

- `QUERY_ALL_PACKAGES` and `BIND_APPWIDGET` manifest permissions.
  Neither was actually needed: app discovery goes entirely through
  `LauncherApps` (visibility-exempt for the default-launcher role), and
  `BIND_APPWIDGET` is only required by AppWidget *providers*, not the
  standard-picker *host* flow ReLite Home already used.

### Notes

- Real-device validation this pass: restore/apply round trip
  (stock → performance → restore → performance) re-verified live on the
  RMX5303 unit; ReLite Home v0.2.0 debug APK installed and its settled
  PSS re-measured on the same unit. See
  `benchmarks/results/RMX5303/v0.2.0.md`.
- Full interactive drag-and-drop grid repositioning, dock-editing UI,
  folder-creation UI, and the end-to-end widget picker→render→persist
  flow remain `WorkspaceController`-level only in this release — real
  UI work for a future version, not claimed as shipped here.
- Manual, human-only validation (calls, SMS, GPS, fingerprint,
  Bluetooth audio, banking apps) unchanged from v0.1.0 — see
  `docs/RMX5303-validation-checklist.md`.

## [0.1.0] — 2026-08-08

First public release. Validated end-to-end against a physical RMX5303
(realme C71) unit — not just fixtures. See
`benchmarks/results/RMX5303/v0.1.0.md` for full results and
`devices/realme/RMX5303/findings.md` for the complete evidence trail.

### Highlights

- **Recommended profile: `performance`.** Settings cold start
  1177 ms → 544 ms, camera cold start 724 ms → 612 ms, 400 → 390 enabled
  packages, all verified stable across two independent full validation
  passes with zero crashes/ANRs.
- **ReLite Home is 31.5% lighter than the stock launcher** (74,817 kB vs.
  109,181 kB settled PSS, median of 3 runs each, decay-curve-verified
  settle time) after fixing a real icon-cache memory regression found
  during profiling.
- **Bootloader: locked.** Treble/VNDK 33/dynamic-partitions/Virtual A-B
  all confirmed present — GSI is architecturally possible but blocked by
  the lock and the absence of an in-Android DSU service on this build.
  No unlock/flash command was run or is run automatically.
- `relite status` reports the active profile and verifies live package
  state against it, distinguishing genuine compliance failures from
  documented, unfixable platform limitations (e.g. one package this OEM
  build silently refuses to let any app disable).

### Added

- `relite status` command with profile-integrity checking
  (`relite/state.py`).
- CLI UX: `plan`/`apply` show a device/profile/rollback-availability
  header and group changes by action before executing; profiles are
  labeled conservative/recommended/aggressive-experimental.
- `scripts/generate_package_docs.py` renders each device's
  human-readable `PACKAGES.md` from its `packages.yaml`/
  `protected-packages.yaml`, checked for staleness in CI and by a
  pytest test — the table can never drift from the source of truth.
- `scripts/package-release.sh` packages a debug-signed ReLite Home APK
  with a SHA-256 checksum for release artifacts.
- `docs/profiles.md`, `docs/releasing.md`,
  `docs/RMX5303-validation-checklist.md`.
- `platform_limitation` field on package classifications, for OEM
  quirks no ReLite action can resolve.
- ReLite Home: `BoundedByteCache`, a framework-independent byte-bounded
  LRU cache, with unit tests covering the exact memory regression it
  exists to prevent.
- `measure_pss_settled` in the benchmark harness — PSS is only
  meaningful after a verified settle time, not immediately post-launch.

### Fixed

Real bugs found and fixed during physical-device validation:

- `relite/packages.py::list_packages()` ignored per-user install state
  (`pm list packages` without `--user 0` lists packages uninstalled via
  `pm uninstall --user 0` as if still present/enabled).
- `relite/restore.py::restore_from_snapshot()` tried `pm enable` first
  and only fell back to `install-existing` on failure — but `pm enable`
  exits 0 unconditionally without reinstalling, so uninstalled packages
  were reported "restored" while remaining genuinely absent. Also now
  diffs against current state first instead of touching all ~400
  snapshot packages unconditionally, fixing both a performance problem
  and spurious `SecurityException`s on OS-protected packages ReLite
  never touched.
- `relite/actions.py::apply_plan()` trusted `pm disable-user`'s exit
  code alone; the platform can exit 0 while silently refusing the
  change (`new state: default` instead of `disabled-user`).
- `relite report`'s benchmark comparison sorted results alphabetically,
  putting "maximum" before "stock" as the baseline column.
- `relite/sanitize.py`'s `phone_number` and `home_path` patterns
  false-positived on firmware build timestamps and package paths
  containing the substring "home".
- ReLite Home's `IconCache` cached full-resolution icon `Drawable`s
  bounded by entry count, not memory — pushed Native Heap to ~136 MB.
- A `rich` `Console.print` markup bug silently swallowed profile labels
  written as `[recommended]` (rich interprets bare `[text]` as a style
  tag).

### Notes

- Manual, human-only validation (calls, SMS, GPS, fingerprint, Bluetooth
  audio, banking apps) is tracked in
  `docs/RMX5303-validation-checklist.md` and was not a blocker for this
  release.
- ReLite Home screenshots for the README are pending — capturing them
  requires the device owner to unlock the phone's lock-screen credential
  (a normal Android FBE/Direct-Boot behavior after screen timeout, not a
  ReLite issue), which this session correctly did not attempt to bypass.
- No destructive/irreversible commands (bootloader unlock, `fastboot
  flash`/`erase`, `dd`) have been run or are run automatically. See
  `docs/manual-actions.md`.

<!--
  Pre-0.1.0 scaffolding (repository bootstrap, initial CLI engine,
  ReLite Home skeleton, CI) was built before a physical device was
  available and is folded into the 0.1.0 entry above rather than kept
  as a separate historical "Unreleased" section — see git history for
  the original commit-by-commit progression.
-->
