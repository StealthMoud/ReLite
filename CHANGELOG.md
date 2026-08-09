# Changelog

All notable changes to ReLite are documented in this file.

The format loosely follows [Keep a Changelog](https://keepachangelog.com/),
and versioning targets follow `docs/architecture.md` / the master plan
milestones (0.1, 0.2, 1.0).

## [Unreleased]

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
