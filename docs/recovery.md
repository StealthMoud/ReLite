# Recovery

What to do if a ReLite change causes a problem, from least to most
drastic.

## 1. `relite restore` (first, always)

```bash
relite restore --snapshot stock
```

Reverses package enabled/disabled/uninstalled state and restores
settings (including animation scale) exactly as recorded in the named
snapshot. This is the primary recovery path and should resolve the large
majority of issues, since every ReLite action is reversible by design
(`docs/safety.md`).

```bash
relite restore              # reverses the action journal only (no snapshot needed)
relite restore --all        # snapshot restore + journal + tuning (Private DNS, etc.)
```

If you didn't take a `relite snapshot` before applying a profile,
`relite restore` (no `--snapshot` flag) still works from the action
journal at `.local/actions.jsonl` — every applied action was recorded
with its own rollback command as it ran.

## 2. Re-enable a specific package manually

If you know exactly which package is causing trouble and don't want to
restore everything else:

```bash
adb shell pm enable <package>
# or, if it was uninstalled for user 0:
adb shell cmd package install-existing --user 0 <package>
```

Then consider filing an issue with the package name so it can be added to
`devices/realme/RMX5303/protected-packages.yaml` or have its confidence
lowered in `packages.yaml`.

## 3. Restore animation/settings only

```bash
adb shell settings put global window_animation_scale 1.0
adb shell settings put global transition_animation_scale 1.0
adb shell settings put global animator_duration_scale 1.0
```

`relite restore` does this for you from the snapshot, but it's here as a
manual fallback if the CLI itself is unavailable.

## 4. Disable network ad-blocking

```bash
relite network-adblock --disable
# or manually:
adb shell settings put global private_dns_mode off
```

## 5. Uninstall ReLite Home / switch launcher back

ReLite Home never removes the previous default launcher — it's protected
in `devices/realme/RMX5303/protected-packages.yaml` until ReLite Home is
confirmed working end-to-end. To switch back:

```bash
adb shell cmd package set-home-activity <previous-launcher>/<activity>
# or simply uninstall ReLite Home and pick the remaining launcher when prompted:
adb uninstall io.relite.home
```

## 6. Full factory reset (last resort)

Only if the above don't resolve the issue — this erases all user data.
This is a manual, deliberate action; ReLite does not trigger it. Use the
device's Settings → System → Reset options, or recovery mode's "Wipe data
/ factory reset" if the device won't boot into Settings.

## What ReLite cannot recover from

- Anything modified outside of ReLite's tracked state (e.g. changes made
  directly via `adb shell` or another tool) is not in the snapshot/journal
  and won't be reversed by `relite restore`.
- If a package was manually deleted from `/system` via a rooted/custom
  ROM path (never done by ReLite itself on the stock-ROM path — see
  `docs/safety.md`), `cmd package install-existing` cannot bring it back;
  a full firmware reflash would be required, which is out of ReLite's
  automated scope.
