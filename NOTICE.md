# NOTICE

ReLite
Copyright 2026 The ReLite Contributors

This product includes software developed as part of the ReLite project
(https://github.com/StealthMoud/ReLite), licensed under the Apache License,
Version 2.0. See the `LICENSE` file for the full license text.

## Third-party references and inspirations

ReLite does not redistribute any of the following. They are cited here
because ReLite's design or research process references them.

- **Universal Android Debloater NG** (Universal-Debloater-Alliance/universal-android-debloater-next-generation) —
  used only as a secondary research signal for package classification.
  ReLite's own device package database (`devices/`) is independently
  validated against the actual RMX5303 and is not a copy of any external
  database. Review that project's license before importing any of its
  data or code verbatim; as of this writing ReLite has not done so.
- **realme-kernel-opensource** kernel source trees — referenced for
  read-only research in `research/kernel.md`. No kernel source is
  vendored into this repository.
- **Samsung One UI** — ReLite Home is "inspired by" One UI ergonomics
  (spacing, one-handed reach, rounded geometry). **No Samsung assets,
  icons, fonts, wallpapers, animations, or artwork are used or
  redistributed anywhere in this project**, and none are extracted from
  any Samsung device or firmware.

  What ReLite Home *does* use, as of the v0.5.0 visual pass, is a small
  set of numeric values published in Samsung's own public **"One UI
  Design Guidelines"** document
  (<https://design.samsung.com/global/contents/one-ui/download/oneui_design_guide_eng.pdf>),
  which is distributed openly for third-party developers building apps
  that fit the One UI look:

  - the accent colors `#0381fe` / `#0072de` / `#3e91ff` (Visual Design
    02. Color, p.62-63);
  - the 24dp minimum screen safe-area margin (Architecture 04. Margins
    and keylines, p.14);
  - the 26/20/12dp thumbnail-radius scale (Visual Design 04. Thumbnail
    radius, p.67).

  These are published design-guideline figures, not resources extracted
  from a device. ReLite Home's neutral background/surface palette,
  icon sizing, dock and indicator geometry, motion curves and all
  drawables remain its own — Samsung publishes no values for those. One
  UI's stated default typeface is Roboto, which is Android's own system
  default (Apache-2.0) and is used here as the platform font rather than
  bundled.

  ReLite Home is not affiliated with, endorsed by, or a product of
  Samsung, and does not use Samsung branding.

If you believe this file is missing an attribution, please open an issue.
