# ReLite

**Make Android lighter without replacing the hardware layer.**

ReLite is an open-source Android optimization toolkit and a lightweight,
original launcher. It works over ADB, without root, without an unlocked
bootloader, and without a custom recovery — and every change it makes is
reversible.

First target device: **RMX5303 (realme C71)**, UNISOC T7250/UMS9230
platform, 6 GB RAM. See `docs/supported-devices.md` for how to add
another.

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

Pre-1.0, Stage 1 (stock-ROM CLI + launcher) functionally complete and
tested against fixtures/mocks and, for ReLite Home, an actual Android SDK
build. **No physical RMX5303 has been used to validate this yet** — see
`devices/realme/RMX5303/findings.md` for exactly what is and isn't
confirmed on real hardware, and `docs/supported-devices.md` for how to
help close that gap.

## Contributing

See `CONTRIBUTING.md`. Ground rules in one sentence: every change must be
reversible, evidence-backed, privacy-respecting, and free of telemetry —
no exceptions.

## License

Apache-2.0. See `LICENSE` and `NOTICE.md` for third-party references and
design-inspiration attribution (ReLite Home does not redistribute any
Samsung/One UI assets, and its debloat database is independently
validated rather than imported from any external tool).
