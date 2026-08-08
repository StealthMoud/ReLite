# Safety

ReLite is performance-first, but performance is never an excuse to skip
evidence. This document is the concrete list of what ReLite refuses to do
automatically, and why.

## Every optimization needs a reason or a benchmark

Every optimization ReLite ships must have either:

1. a defensible Android architectural reason (e.g. "this component has no
   function without an OEM account and starts a persistent service"), or
2. a benchmark demonstrating improvement (`benchmarks/methodology.md`).

"Common Android optimization folklore" is not enough on its own, and a
lot of it actively makes a device slower, hotter, or less stable. ReLite
does **not**:

| Folklore optimization | Why not |
|---|---|
| Change CPU governors | Governors are tuned by the SoC vendor for thermal/battery/perf balance across the whole system; naive changes cause throttling or instability without profiling |
| Disable thermal throttling | Directly risks hardware damage and unpredictable shutdowns |
| Alter LMKD (low memory killer daemon) tuning | LMKD's defaults are tuned against the platform's actual memory pressure behavior; blind changes cause more kills, not fewer, or OOM instability |
| Disable Android's normal zRAM | zRAM is a kernel-level compressed-swap mechanism distinct from OEM "RAM Expansion" — see `research/RMX5303.md`. Disabling it generally *reduces* usable memory headroom |
| Set arbitrary kernel sysctls | Unvalidated sysctl changes are a classic source of "my phone got weird after I flashed a tuning script" reports |
| Kill cached apps constantly / run a task killer | Android's cached-process model exists so app switching is fast; killing cached processes makes switching *slower*, not faster, and increases cold-start frequency |
| Set tiny background-process limits | Same root cause as above — see the explicit rule in section 16 of the original project plan |
| Flush caches repeatedly | Causes more disk I/O and cold reads, not less |
| Force-stop useful apps on a schedule | Breaks notifications, alarms, and background sync the user actually wants |
| Modify scheduler tunables without profiling | Same category as CPU governor changes — high risk, no evidence |
| Disable verified boot | Removes a real security boundary for no established performance gain |
| Remove SELinux enforcement | Same — a security regression with no performance justification |
| Overclock the device | Out of scope entirely: stability/thermal risk with no reversibility guarantee |

If you find yourself wanting to add one of these, the answer is: don't,
unless you can attach a reproducible `benchmarks/` result *and* a written
architectural justification, and even then, expect it to be scrutinized
hard in review — the master plan treats this list as close to a hard
line, not a strong suggestion.

## Reversibility is not optional

Every action ReLite applies to installed packages has a corresponding
rollback action, generated at plan time and journaled at apply time — see
`relite/actions.py::PlannedAction.rollback_command()` and
`relite/restore.py`. If an action type doesn't have a clean rollback, it
doesn't ship.

ReLite never:

- deletes APK files from `/system`, `/product`, `/vendor`, or
  `/system_ext` on the stock-ROM path;
- remounts system partitions;
- uninstalls a package in a way that can't be reversed with
  `cmd package install-existing --user 0`.

## Protected packages always win

`relite/classifier.py::ClassificationDatabase.decide()` checks
`protected-packages.yaml` before consulting `packages.yaml` at all — a
package on the protected list cannot be scheduled for removal by any
profile, full stop, regardless of what its classification entry says.
Unknown packages (not present in either file) default to `keep`. See
`devices/realme/RMX5303/protected-packages.yaml` for what's protected by
default and why.

## Destructive commands are never automatic

ReLite never automatically executes:

```bash
fastboot flashing unlock
fastboot oem unlock
fastboot erase ...
fastboot flash ...
dd if=... of=/dev/block/...
```

All bootloader/partition/GSI investigation in `research/` is read-only.
If a destructive step is ever genuinely required to make progress, the
exact procedure is written to `docs/manual-actions.md` for a human to
execute deliberately, with backup/recovery requirements spelled out —
ReLite skips only that step and continues everything else.

## Privacy

Nothing that could identify a specific device or person is committed to
this (public) repository: IMEI, IMSI, phone number, account email, Wi-Fi
SSIDs/BSSIDs, Bluetooth addresses, serial number, Android ID, advertising
identifiers, saved networks, tokens, or API keys. `relite/sanitize.py`
strips these before anything is written under `devices/`, `research/`,
or `benchmarks/results/`; CI scans committed fixtures for the same
patterns. See `CONTRIBUTING.md` for the contributor-facing version of
this rule.
