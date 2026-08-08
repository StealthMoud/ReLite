# Architecture

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

## Stage 1 (current): stock-ROM optimization

Everything under `relite/` and `android/relite-home/` operates against an
unmodified, locked-bootloader stock realme Android install, over ADB,
with no root. This is deliberate — see section 1 of the original project
plan: root, unlocked bootloader, custom recovery, a replacement kernel,
Magisk, and modified vendor partitions are all explicitly *not*
prerequisites for ReLite's core value.

Three components make up Stage 1:

- **ReLite CLI** (`relite/`) — device reconnaissance, snapshotting,
  package classification, reversible debloat, tuning, benchmarking,
  restore. See `relite/cli.py` for the command surface and
  `docs/development.md` for the module layout.
- **ReLite Home** (`android/relite-home/`) — a lightweight, original
  Android launcher. See `android/relite-home/README.md`.
- **Device profiles** (`devices/<oem>/<model>/`) — per-device
  classification data, protected-package policy, and tuning parameters.
  The engine in `relite/` is entirely device-agnostic; all device-specific
  knowledge lives here so a second device can be added without touching
  the engine.

## Stage 2 (future, evidence-gated): ReLite Stock → GSI → OS

```text
ReLite Stock
    ↓
ReLite GSI
    ↓
ReLite OS
```

Each arrow is gated on the previous stage's feasibility research turning
out positive — never assumed:

1. **ReLite GSI** — only pursued after `research/bootloader.md` and
   `research/treble-gsi.md` establish that unlocking + a compatible
   Generic System Image are actually possible on this hardware. The
   preferred architecture keeps the realme vendor implementation and
   replaces only the AOSP/GSI userspace:

   ```text
   AOSP / GSI userspace
           │
           ▼
   Realme vendor implementation
           │
           ▼
   C71 hardware
   ```

2. **ReLite OS** — full device bring-up (device tree, BoardConfig,
   fstab, VINTF manifests, proprietary-files extraction, SELinux policy,
   HAL compatibility for camera/radio/audio/sensors/biometrics/graphics/
   power). Only attempted after GSI validation succeeds — see section 33
   of the original project plan for the full component list. Proprietary
   vendor blobs are never vendored into this repository; extraction
   scripts are preferred over committed binaries.

Stage 2 progress never blocks Stage 1: if the bootloader can't be
unlocked, or a GSI doesn't boot, the CLI/launcher/benchmark/restore
tooling from Stage 1 remains the complete, useful deliverable on its own.

## Why the engine and device data are split

`relite/classifier.py`, `relite/actions.py`, `relite/restore.py`, etc.
contain zero RMX5303-specific logic. All of the RMX5303's package
classifications, protected-package list, and tuning parameters live in
`devices/realme/RMX5303/`. Adding a second device means adding a second
directory under `devices/<oem>/<model>/`, not touching the engine — see
`docs/supported-devices.md`.

## Why ReLite Home can't replace SystemUI on stock firmware

A launcher (the `HOME` intent-filter role) and SystemUI (status bar,
notification shade, quick settings, lock screen, power menu) are
different system roles with different privilege levels. On locked stock
firmware, ReLite Home can legitimately become the default launcher; it
cannot legitimately replace SystemUI without either OEM cooperation or a
custom ROM. ReLite will not fake this with intrusive Accessibility-service
overlays — see `docs/safety.md`. Full SystemUI customization is a Stage 2
(ReLite OS) goal.
