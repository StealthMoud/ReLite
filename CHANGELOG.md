# Changelog

All notable changes to ReLite are documented in this file.

The format loosely follows [Keep a Changelog](https://keepachangelog.com/),
and versioning targets follow `docs/architecture.md` / the master plan
milestones (0.1, 0.2, 1.0).

## [Unreleased]

## [0.5.0] — 2026-08-10

A One UI-inspired transformation pass on ReLite Home on top of v0.4.1's
hardening work — dynamic Home grids, a rebuilt Apps screen, a real Home
edit mode, a redesigned Settings screen, a shared custom context menu, and
several genuine correctness fixes found by actually running the app live
on the RMX5303 during this pass, not just compiling it. See
`docs/design/one-ui-current-reference.md` for the sourced Samsung
references this work is grounded in, and
`docs/design/one-ui-parity-matrix.md` for an honest per-surface parity
grade — several major surfaces (the widget picker's visual redesign,
portable widget rebind, the full stress/jank benchmark campaign) are
explicitly out of scope for this pass; see Known gaps below.

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
- **Widgets**: no debug test widget fixture, no picker preview
  cards/grouping, no drag/edit-mode resize handles (still +/- buttons), no
  portable widget descriptor/rebind-on-import flow — export still drops
  widgets entirely, same as v0.4.1. An opt-in "Show widget labels" overlay
  (off by default) is now implemented, closing the widget-labels gap
  below.
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
