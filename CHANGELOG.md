# Changelog

All notable changes to ReLite are documented in this file.

The format loosely follows [Keep a Changelog](https://keepachangelog.com/),
and versioning targets follow `docs/architecture.md` / the master plan
milestones (0.1, 0.2, 1.0).

## [Unreleased] — working toward 0.1

### Added

- Repository scaffolding: license (Apache-2.0), contributing guide,
  security policy, docs tree.
- `relite` Python CLI package: `doctor`, `device`, `snapshot`, `scan`,
  `analyze`, `plan`, `apply`, `restore`, `tune`, `benchmark`, `report`,
  `network-adblock`.
- ADB wrapper (`relite/adb.py`) with graceful handling of no-device,
  unauthorized, multi-device, and timeout states.
- Device reconnaissance (`relite/device.py`) covering build props,
  Treble/VNDK/AVB/dynamic-partition state, memory, and storage.
- Snapshot engine with schema versioning (`relite/snapshot.py`).
- Package inventory + classification engine (`relite/packages.py`,
  `relite/classifier.py`) with a conservative "unknown packages default
  to keep" policy.
- Protected-package policy and reversible debloat actions
  (`relite/actions.py`, `relite/restore.py`).
- `safe` / `performance` / `maximum` performance profiles.
- Privacy sanitizer (`relite/sanitize.py`) scrubbing IMEI/serial/Android
  ID/MAC/SSID/tokens/etc. before anything is written under
  `devices/`, `research/`, or `benchmarks/results/`.
- Benchmark harness (`relite/benchmark.py`) for package counts, memory,
  cold/warm app start timing, and boot-completion timing, with
  median/min/max/p95 reporting.
- RMX5303 device profile skeleton (`devices/realme/RMX5303/`).
- ReLite Home: initial Kotlin/Views Android launcher skeleton (home
  workspace, dock, app drawer, folders, `AppWidgetHost` scaffolding), no
  `INTERNET` permission, no analytics.
- Research documentation: RMX5303 platform notes, bootloader feasibility
  methodology, Treble/GSI investigation methodology, kernel source
  survey.
- GitHub Actions CI: Python lint/format/type-check/tests, profile schema
  validation, shellcheck, Gradle build, secret scanning — all
  device-independent (mocked ADB fixtures).

### Notes

- No physical RMX5303 was connected to the environment that produced
  this initial scaffolding. All device-facing code is real and
  functional, but first-run output against actual hardware is pending
  and tracked in `devices/realme/RMX5303/findings.md`.
- No destructive/irreversible commands (bootloader unlock, `fastboot
  flash`/`erase`, `dd`) have been run or are run automatically. See
  `docs/manual-actions.md`.
