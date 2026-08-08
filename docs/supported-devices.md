# Supported devices

| Model | Marketing name | Status | Profile location |
|---|---|---|---|
| RMX5303 | realme C71 | Research-and-tooling-ready; classification database seeded but not yet on-device-validated | `devices/realme/RMX5303/` |

"Research-and-tooling-ready" means: the full CLI engine, profiles,
benchmark harness, and restore tooling all work against this device
definition, and the package classification data is a documented starting
candidate — but no run against a physical unit has been recorded yet. See
`devices/realme/RMX5303/findings.md` for exactly what has and hasn't been
confirmed, and run `relite doctor` / `relite device` / `relite scan`
yourself against a real unit to help close that gap.

## Adding a new device

The engine in `relite/` has zero device-specific logic — all device
knowledge lives under `devices/<oem>/<model>/`. To add support for a new
device:

1. Copy the RMX5303 layout as a starting template:
   ```text
   devices/<oem>/<model>/
   ├── device.yaml
   ├── packages.yaml
   ├── protected-packages.yaml
   ├── tuning.yaml
   ├── README.md
   └── findings.md
   ```
2. Fill in `device.yaml` with hardware facts you actually know (RAM, SoC,
   marketing name) — leave firmware-version fields to be read live via
   `relite device`, never hard-coded.
3. Start `protected-packages.yaml` from the RMX5303's AOSP-framework-name
   baseline (those package names are stable across nearly all OEM skins)
   and add any OEM-specific critical services you identify.
4. Run `relite scan` / `relite analyze` against a real unit and use the
   findings to populate `packages.yaml` — do not import another device's
   classifications wholesale; each entry should be justified for *this*
   device (see `docs/safety.md` and master plan section 10 on using
   external debloat databases as secondary references only).
5. Log every observation in `findings.md` with a dated entry.
6. Add a row to the table above.

## Why this device first

RMX5303 (realme C71) was chosen as ReLite's first target: a
representative low/mid-range UNISOC-platform device where OEM bloat and
RAM pressure are most likely to produce a noticeable, measurable
improvement from debloating and a lightweight launcher — see
`research/RMX5303.md`.
