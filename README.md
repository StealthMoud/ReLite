# ReLite

**A reversible Android performance and debloating toolkit, with an
original lightweight launcher.**

First validated device: **realme C71 / RMX5303**

```text
Settings cold launch:  1177 ms  →  544 ms    (performance profile)
Camera cold launch:     724 ms  →  612 ms    (performance profile)
Launcher settled PSS:  123 MB   →  54 MB     (ReLite Home vs. stock, −56.2%)
Enabled packages:       400     →   390      (performance profile)

Recommended profile: performance
```

Full methodology and every number's provenance:
`benchmarks/results/RMX5303/v0.2.0.md` (device/profile numbers unchanged
since v0.1.0; the launcher PSS comparison was re-measured for v0.2.0 —
see that report for why the gap widened between sessions and what that
does and doesn't prove). v0.3.0 was a correctness/packaging/security
release with no package-action or launcher-UI changes, so these numbers
are still current and were not re-measured — see CHANGELOG.md's v0.3.0
entry. These are real measurements from a physical unit, not
projections — see `docs/safety.md` and `benchmarks/methodology.md` for
how they were produced and what they don't claim.

ReLite works over ADB, without root, without an unlocked bootloader, and
without a custom recovery — every change it makes is reversible. First
target device: **RMX5303 (realme C71)**, UNISOC T7250/UMS9230 platform.
(The physical unit ReLite was validated against reports ~8 GB RAM via
`/proc/meminfo`, not the 6 GB originally assumed — see
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

## Installation

Requires: a computer (macOS/Linux; Windows works but is less tested),
Python 3.11+, and an Android phone with **USB debugging enabled**
(Settings → About phone → tap "Build number" 7 times to unlock Developer
Options, then Settings → Developer options → USB debugging → on). Plug
the phone in with a USB cable and tap "Allow" on the "Allow USB
debugging?" prompt that appears on the phone — this authorizes your
computer, once, and is how `adb` (and therefore ReLite) is allowed to
talk to the device at all. No root, no unlocked bootloader, nothing else
to configure on the phone.

```bash
git clone https://github.com/StealthMoud/ReLite.git
cd ReLite
./scripts/bootstrap.sh
source .venv/bin/activate

adb devices          # confirm your phone shows up as "device", not "unauthorized"
relite doctor        # confirms adb + a connected device
relite device         # shows what ReLite detected about your phone
relite snapshot --name stock   # back up the current state before changing anything
relite apply --profile performance
```

That's the entire normal install path — you do not need to know
anything about Android internals, and nothing above touches the
bootloader, recovery, or system partitions. Something look wrong
afterward?

```bash
relite restore --snapshot stock
```

See `docs/recovery.md` for the full recovery ladder, and
`docs/RMX5303-validation-checklist.md` for what's worth manually
double-checking on your specific phone/apps after applying a profile.

## Real RMX5303 results (last re-measured v0.2.0, 2026-08-09)

Validated end-to-end against a physical RMX5303EEA unit (firmware
`AP3A.240905.015.A2`, Android 15). Full methodology and every number's
provenance: `benchmarks/results/RMX5303/v0.2.0.md`. Package-by-package
classification evidence: `devices/realme/RMX5303/findings.md` and the
generated table at `devices/realme/RMX5303/PACKAGES.md`.

| Metric | stock | safe | performance | maximum |
|---|---:|---:|---:|---:|
| Enabled packages | 400 | 397 | 390 | 385 |
| Camera cold start (median) | 724 ms | 629 ms | 612 ms | 596 ms |
| Settings cold start (median) | 1177 ms | 1170 ms | 544 ms | 583 ms |

Device/profile numbers are unchanged from v0.1.0 (no package action has
changed across v0.2.0 or v0.3.0). All four profiles were applied and
verified stable on this unit across multiple independent validation
passes (boot completes, SystemUI/Settings/Camera/telephony/Bluetooth
intact, no crashes in logcat), including a complete restore round-trip.
v0.3.0 additionally validated the profile engine's bidirectionality
live — `performance -> maximum -> performance` with **no intermediate
restore step**, `relite status` reporting a clean, verified result both
times (this specific direct transition silently failed before v0.3.0's
planner rewrite; see CHANGELOG.md). `performance` is the
ReLite-recommended default for this device — see `docs/profiles.md` for
the full inheritance model and what qualifies a package for each level.

ReLite Home, installed and tested on this same unit: settled PSS
**53,957 kB, ~56.2% lower** than the stock launcher's 123,310 kB
(median of 3 runs each, same decay-curve-verified settle time as
v0.1.0 — see `benchmarks/results/RMX5303/v0.2.0.md` for why this gap
widened since v0.1.0's 31.5% and what that measurement can't tell you
on its own).

Manual, human-only validation (calls, SMS, GPS, fingerprint, etc.) is
tracked separately and was not a blocker for any of the above — see
`docs/RMX5303-validation-checklist.md`.

## What ReLite is (and isn't)

ReLite currently **is**:

- reversible Android package debloating over ADB, no root;
- RMX5303-specific optimization, validated on real hardware;
- a benchmarking harness that reports real, reproducible measurements;
- an original, lightweight, One UI-*inspired* launcher (ergonomics and
  spacing, not copied assets — see `NOTICE.md`);
- experimental research toward a future ReLite OS (`research/`),
  entirely separate from and non-blocking for the above.

ReLite currently is **not**:

- a custom ROM;
- a Samsung One UI port (no Samsung code, assets, or branding — ever);
- a bootloader unlocker (RMX5303's bootloader is confirmed locked and
  ReLite does not attempt to change that — see `research/bootloader.md`);
- a root framework;
- a kernel overclocking or CPU-governor-tweaking tool (see
  `docs/safety.md` for the full list of "Android optimization folklore"
  ReLite deliberately refuses to do).

## Safety, in short

- **`relite snapshot --name stock` first, always** — the quick start
  above does this before anything else, so there's a known-good state to
  return to.
- Every package change is per-user (`--user 0`) and reversible: `disable`
  reverses with `enable`; `uninstall-user` reverses with
  `install-existing --user 0`. Nothing is ever deleted from `/system`,
  `/product`, `/vendor`, or `/system_ext`, and no partition is ever
  erased or reflashed.
- The bootloader is never unlocked, and no `fastboot flash`/`erase`
  command is ever run automatically — see `docs/manual-actions.md` for
  the (currently empty, nothing-needed-yet) list of steps ReLite treats
  as too destructive to automate.
- `relite restore` exists specifically to undo everything above, and is
  tested as a first-class feature, not an afterthought — see
  `docs/recovery.md`. `relite status` tells you what profile is active
  and whether the device's live state actually matches it.

Full detail: `docs/safety.md`.

## Building ReLite Home

```bash
./scripts/build-launcher.sh
adb install -r android/relite-home/app/build/outputs/apk/debug/app-debug.apk
```

See `android/relite-home/README.md` for setting it as your default
launcher and exactly what's implemented versus domain-logic-only so far.

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
