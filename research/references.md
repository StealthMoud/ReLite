# References

External resources ReLite's research and tooling draw on. Listed here so
provenance is traceable — see `NOTICE.md` for licensing/attribution
requirements before importing any code or data from these.

## Debloat / package classification (secondary reference only)

- **Universal Android Debloater NG**
  (`Universal-Debloater-Alliance/universal-android-debloater-next-generation`)
  — used only as a secondary classification signal per master plan
  section 10. RMX5303-specific observations in
  `devices/realme/RMX5303/findings.md` always override generic lists from
  this or any other external debloat database. License must be reviewed
  before importing any of its data verbatim; as of this writing, ReLite
  has not done so — the seed entries in `packages.yaml` were written
  independently from publicly documented package names, not copied from
  this project's dataset files.

## Kernel source

- `realme-kernel-opensource/realme_C71-AndroidV-common-source`
- `realme-kernel-opensource/realme_Note80-Note80s-C71-NARZO_80Lite-P4_Lite-Note70-Note70s-Note70T-AndroidB-kernel-source`
  (branch `realme/ums9230_b_16.0`)

  See `research/kernel.md` for what's been checked so far.

## Platform / AOSP concepts referenced throughout

- Android Verified Boot (AVB) / `ro.boot.vbmeta.device_state`,
  `ro.boot.verifiedbootstate` — used in `research/bootloader.md`.
- Project Treble, VNDK, Dynamic Partitions, Virtual A/B, Dynamic System
  Updates (DSU) — used in `research/treble-gsi.md`.
- Android Private DNS (DNS-over-TLS) — used by `relite network-adblock`
  (`relite/tuning.py::set_private_dns`).

## Design inspiration

- Samsung One UI — referenced only as an ergonomics/design-philosophy
  inspiration (large touch targets, lower-screen interactions,
  one-handed reach, rounded geometry). No Samsung assets, code, or
  branding are used — see `NOTICE.md`.

## How to add a reference

If you incorporate a new external database, kernel tree, icon set, or
design source, add it here with: what it is, what ReLite uses it for,
and its license (or "license not yet reviewed" if incorporation hasn't
happened yet and is only planned/researched).
