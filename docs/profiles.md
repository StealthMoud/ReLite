# Profiles

ReLite ships three profiles. Each is a strict superset of the one above
it — nothing a lower profile disables is ever re-enabled by a higher
one, and every additional action a higher profile takes is listed
explicitly in the device's `packages.yaml`, never inferred.

```text
safe
  ↓
performance
  ↓
maximum
```

Per-package decisions always come from
`devices/<oem>/<model>/packages.yaml`'s `action.<profile>` field, never
from logic in `relite/classifier.py` — profiles are data, not code
branches. See `docs/development.md`.

## safe — conservative

Removes only what's unambiguous: high-confidence advertising and
promotional components. On RMX5303 this is the OEM app store, the
lock-screen "magazine" wallpaper service, and a marketing-middleware
component that holds a lock-screen-dismiss permission — see
`devices/realme/RMX5303/PACKAGES.md` for the full evidence trail per
package. Every other package is left alone. This is the floor: if a
package isn't safe to touch here, it isn't safe to touch by definition,
and higher profiles build on top of it rather than reconsidering it.

## performance — recommended

Everything in `safe`, plus optional OEM services (search overlay,
diagnostics tools, weather/duplicate-app bundles) whose removal has been
validated not to break core functionality but which weren't
unambiguous enough for `safe`. **This is the default recommendation** —
see `devices/realme/RMX5303/findings.md` for the measured basis (cold
-start latency improvements, no functional regressions across two full
apply/verify passes on real hardware).

## maximum — aggressive / experimental

Everything in `performance`, plus a handful of genuinely-useful
convenience features (parental controls, driving mode, phone-clone
migration tool, factory test menus) that most users won't miss but some
will. Labeled aggressive/experimental deliberately: the measured benefit
over `performance` on RMX5303 was marginal (see
`benchmarks/results/RMX5303/latest.md`), while the risk of removing
something a specific user actually wanted is higher. Still fully
reversible — `relite restore` undoes it exactly like any other profile.

## What never changes regardless of profile

Anything in `devices/<model>/protected-packages.yaml` — SystemUI,
telephony, Wi-Fi/Bluetooth stacks, camera, biometrics, storage/media
providers, WebView, the current launcher, and OEM infrastructure
discovered to be load-bearing during real-device validation (e.g.
`com.oplus.gesture`, confirmed `PERSISTENT`, controls system gesture
navigation). See `docs/safety.md` for the policy this enforces and why
it's checked before the classification database, not after.

## Regenerating the package reference

`devices/<model>/PACKAGES.md` is generated, not hand-maintained:

```bash
python scripts/generate_package_docs.py devices/realme/RMX5303 \
  > devices/realme/RMX5303/PACKAGES.md
```

Run this after any change to `packages.yaml` or `protected-packages.yaml`
— CI checks that the committed file matches what the generator produces
(see `.github/workflows/ci.yml`), so a stale table fails the build
instead of silently drifting from the source of truth.
