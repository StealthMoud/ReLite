# Changelog

All notable changes to ReLite are documented in this file.

The format loosely follows [Keep a Changelog](https://keepachangelog.com/),
and versioning targets follow `docs/architecture.md` / the master plan
milestones (0.1, 0.2, 1.0).

## [Unreleased]

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
