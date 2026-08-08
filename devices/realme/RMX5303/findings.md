# RMX5303 findings log

Running, dated log of what has actually been observed by running ReLite's
read-only reconnaissance (`relite doctor`, `relite device`, `relite scan`,
`relite tune ram-expansion probe`, `research/bootloader.md` procedure,
`research/treble-gsi.md` procedure) against a real RMX5303 unit. Anything
not listed here as **observed** is inferred from public specifications or
from the classifier's conservative defaults, and must not be treated as
confirmed device behavior.

All entries here must already be sanitized (see `relite/sanitize.py`) — no
IMEI, serial, Android ID, MAC address, SSID, or account identifier.

## 2026-08-08 — initial scaffolding, no device attached

- ReLite CLI, classification engine, protected-package policy, profiles,
  benchmark harness, restore engine, and ReLite Home launcher skeleton were
  implemented and unit-tested against fixtures (`tests/fixtures/`), without
  a physical RMX5303 connected to the development environment.
- `packages.yaml` entries in this directory are seeded from names publicly
  documented across multiple realme/ColorOS-family debloat references
  (secondary signal only, per master plan section 10) and are capped at
  `confidence: medium` pending on-device validation.
- `protected-packages.yaml` is seeded from well-known AOSP framework
  package names (`com.android.systemui`, `com.android.phone`, etc.) that
  are stable across virtually all Android OEM skins including realme's.
  The exact realme-branded launcher package name
  (`protected-packages.yaml` currently guesses `com.oppo.launcher` as a
  placeholder) is **unconfirmed** and must be corrected from a real
  `relite scan` dump before `relite apply` is run against a live device.
- RAM Expansion: no on-device setting key has been observed yet. See
  `relite/tuning.py::RAM_EXPANSION_CANDIDATE_KEYS` for the candidate keys
  `relite tune ram-expansion probe` will search for once a device is
  connected. If no key is found, the manual toggle path (Settings app UI)
  must be documented in `docs/manual-actions.md` instead.
- Bootloader / Treble / GSI feasibility: not yet investigated on real
  hardware. See `research/bootloader.md` and `research/treble-gsi.md` for
  the exact read-only procedure that must be run and the verdict grid to
  fill in once a device is available.
- Android version, security patch level, and build fingerprint: **not
  assumed**. Per the master plan, these must be read live via
  `relite device` from an actual unit rather than taken from any online
  listing. This log intentionally leaves them blank until that happens.

## Template for future entries

```markdown
## YYYY-MM-DD — <what was done>

- Firmware observed: <ro.build.fingerprint, sanitized>
- Android release / SDK / security patch: <values>
- Findings: <what was confirmed, corrected, or ruled out>
- Action taken in packages.yaml / protected-packages.yaml / tuning.yaml: <diff summary>
```
