# ReLite

**Make Android lighter without replacing the hardware layer.**

ReLite is an open-source Android optimization toolkit and a lightweight,
original launcher. It works over ADB, without root, without an unlocked
bootloader, and without a custom recovery — and every change it makes is
reversible.

First target device: **RMX5303 (realme C71)**, UNISOC T7250/UMS9230
platform. (The physical unit ReLite was validated against reports ~8 GB
RAM via `/proc/meminfo`, not the 6 GB originally assumed — see
`devices/realme/RMX5303/findings.md`; RAM varies by regional SKU, always
check your own unit with `relite device`.) See `docs/supported-devices.md`
for how to add another.

## What's in the box

- **`relite` CLI** — device reconnaissance, stock snapshots, package
  inventory/classification, reversible debloating, performance profiles,
  benchmarking, and one-command restore.
- **ReLite Home** — a lightweight Android launcher (Kotlin, standard
  Views, no Jetpack Compose overhead). One UI-inspired ergonomics —
  large touch targets, rounded geometry, one-handed reach — built with
  entirely original assets and code. No ads, no analytics, no accounts,
  no `INTERNET` permission.
- **Per-device profiles** — `safe` / `performance` / `maximum`, each
  backed by a documented, protected-package-respecting classification
  database per device, not a hard-coded blocklist.
- **Benchmark harness** — every performance claim ships with a
  reproducible measurement (median/min/max/p95 across multiple runs),
  never a single cherry-picked number.

## Why

Low-and-mid-range Android phones ship with a lot of OEM promotional and
analytics weight on top of the OS. ReLite removes what can be safely,
reversibly removed — and measures whether that removal actually helped —
without root, without voiding warranty expectations tied to bootloader
state, and without the "optimization folklore" (CPU governor hacks,
disabling zRAM, aggressive background-process limits, task killers) that
often makes a phone *less* responsive. See `docs/safety.md` for the exact
list of what ReLite refuses to do and why.

## Quick start

```bash
git clone https://github.com/ReLite/ReLite.git
cd ReLite
./scripts/bootstrap.sh
source .venv/bin/activate

relite doctor
relite snapshot --name stock
relite scan
relite analyze
relite plan --profile safe
relite apply --profile safe
relite benchmark --label stock
relite benchmark --label safe
relite report
```

Something look wrong afterward?

```bash
relite restore --snapshot stock
```

See `docs/recovery.md` for the full recovery ladder.

## Real RMX5303 results (2026-08-08)

Validated end-to-end against a physical RMX5303EEA unit (firmware
`AP3A.240905.015.A2`, Android 15). Full methodology, all four profiles,
and PSS/warm-start figures: `benchmarks/results/RMX5303/latest.md`.
Package-by-package classification evidence:
`devices/realme/RMX5303/findings.md`.

| Metric | stock | safe | performance | maximum |
|---|---:|---:|---:|---:|
| Enabled packages | 400 | 397 | 390 | 385 |
| Camera cold start (median) | 724 ms | 629 ms | 612 ms | 596 ms |
| Settings cold start (median) | 1177 ms | 1170 ms | 544 ms | 583 ms |

All three profiles were applied and verified stable on this unit (boot
completes, SystemUI/Settings/Camera/telephony/Bluetooth intact, no
crashes in logcat). `performance` is the ReLite-recommended default for
this device: it captures the confirmed ad/promotional/duplicate-app
removals with low practical risk, while `maximum` additionally touches a
few genuinely-useful convenience features (Kids mode, Riding mode, Clone
Phone) for marginal further gains. MemAvailable/PSS deltas between
profiles were within normal single-pass measurement noise and are
reported as-is rather than oversold — see the methodology doc for why.

ReLite Home, installed and tested on this same unit: after fixing a
real icon-cache regression found during profiling (see
`devices/realme/RMX5303/findings.md`), its settled PSS (~143 MB) is
**~29% lower** than the stock launcher's (~202 MB), measured back-to-back
on-device.

Manual, human-only validation (calls, SMS, GPS, fingerprint, etc.) is
tracked separately and was not a blocker for any of the above — see
`docs/RMX5303-validation-checklist.md`.

## Building ReLite Home

```bash
./scripts/build-launcher.sh
adb install -r android/relite-home/app/build/outputs/apk/debug/app-debug.apk
```

See `android/relite-home/README.md` for setting it as your default
launcher and what the v1 skeleton does and doesn't cover yet.

## Architecture

```text
                    ReLite
                      │
          ┌───────────┼───────────┐
          │           │           │
          ▼           ▼           ▼
       ReLite      ReLite       ReLite
         CLI        Home       Profiles
          │           │           │
          └──────┬────┴────┬──────┘
                 │         │
                 ▼         ▼
          Stock realme   Benchmarking
             Android
                 │
                 ▼
       OEM hardware/vendor layer
```

Stage 1 (this repository today) never requires root, an unlocked
bootloader, custom recovery, a replacement kernel, or modified vendor
partitions. A future Stage 2 (ReLite Stock → GSI → OS) is investigated in
`research/` and is strictly evidence-gated — see `docs/architecture.md`
for the full picture, including exactly what's blocked on what and why
none of it blocks Stage 1's usefulness on its own.

**Dangerous flashing is not the installation path.** If you're looking
for "how do I flash a custom ROM," that's not what the quick start above
does — see `docs/manual-actions.md` for what ReLite treats as
destructive-and-therefore-manual, and why.

## Project layout

| Path | What |
|---|---|
| `relite/` | Python CLI engine (device-agnostic) |
| `devices/<oem>/<model>/` | Per-device classification data, protected packages, tuning |
| `profiles/` | `safe` / `performance` / `maximum` profile definitions |
| `android/relite-home/` | ReLite Home launcher |
| `benchmarks/` | Methodology, scripts, per-device results |
| `research/` | Read-only platform research (bootloader, Treble/GSI, kernel) |
| `docs/` | Architecture, safety policy, recovery, manual actions |
| `tests/` | `relite/` unit tests (no device/adb binary required) |

## Status

Pre-1.0. Stage 1 (stock-ROM CLI + launcher) is functionally complete,
unit-tested (`pytest`, no device required), and validated end-to-end
against a physical RMX5303 unit — device detection, all three profiles,
benchmarking, restore, and ReLite Home were all exercised on real
hardware; see `devices/realme/RMX5303/findings.md` for the full evidence
trail, including two real bugs found and fixed during that pass (a
false-success bug in `pm disable-user` handling, and a launcher icon-cache
memory regression). Manual, human-only checks (calls, SMS, fingerprint,
etc. — see `docs/RMX5303-validation-checklist.md`) remain pending, as
does bootloader unlock/GSI work (currently blocked by a locked
bootloader — see `research/bootloader.md`).

## Contributing

See `CONTRIBUTING.md`. Ground rules in one sentence: every change must be
reversible, evidence-backed, privacy-respecting, and free of telemetry —
no exceptions.

## License

Apache-2.0. See `LICENSE` and `NOTICE.md` for third-party references and
design-inspiration attribution (ReLite Home does not redistribute any
Samsung/One UI assets, and its debloat database is independently
validated rather than imported from any external tool).
