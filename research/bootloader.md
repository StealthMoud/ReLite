# Bootloader feasibility research — RMX5303

Read-only investigation only. **No unlock command is ever run
automatically.** See `docs/manual-actions.md` for what a human would need
to do if unlocking is ever pursued, and `docs/safety.md` for why ReLite's
default execution rules forbid automating it.

## Procedure (safe to run repeatedly)

All of the following are read-only or, in the case of `fastboot reboot`,
fully reversible.

```bash
# From Android, read-only:
adb shell getprop ro.boot.flash.locked
adb shell getprop ro.boot.vbmeta.device_state
adb shell getprop ro.boot.verifiedbootstate

# Confirm the device enters bootloader mode and fastboot sees it:
adb reboot bootloader
fastboot devices

# Read-only fastboot queries — do NOT run any `fastboot flashing unlock`,
# `fastboot oem unlock`, `fastboot erase`, or `fastboot flash` command:
fastboot getvar product
fastboot getvar current-slot
fastboot getvar unlocked
fastboot getvar all           # broad dump; still read-only

# Some UNISOC/realme builds do not implement OEM unlock-ability queries —
# handle "unknown command" / empty output gracefully, don't treat it as
# a crash.
fastboot flashing get_unlock_ability

# Return to Android and confirm adb reconnects (i.e. nothing was left in
# a broken state):
fastboot reboot
adb wait-for-device
adb shell getprop sys.boot_completed
```

## What to record

| Property | Meaning |
|---|---|
| `ro.boot.flash.locked` | `1` = locked, `0` = unlocked |
| `ro.boot.vbmeta.device_state` | `locked` / `unlocked` per Android Verified Boot |
| `ro.boot.verifiedbootstate` | `green` (verified) / `yellow` / `orange` / `red` |
| `fastboot getvar unlocked` | Bootloader's own unlocked-state report, if implemented |
| `fastboot flashing get_unlock_ability` | Whether OEM unlocking is allowed (requires "OEM unlocking" toggled on in Developer Options first, which is itself a manual, reversible Settings change — not scripted here) |

## Verdict (pending on-device run)

**UNKNOWN.** No physical RMX5303 was available in the environment that
produced this scaffolding. Fill in the table above and this verdict once
the procedure has been run against a real device, following the dated
entry format used in `devices/realme/RMX5303/findings.md`.

Realme devices sold in regions with carrier or regional locking
restrictions frequently ship with OEM unlocking disabled or gated behind
a waiting period / account requirement — this is common across the
realme/BBK family and should be checked for, not assumed absent.

## Why this stays manual even if unlock-able

Unlocking the bootloader:

- triggers a factory data reset (irreversible data loss without a prior
  backup);
- trips Verified Boot to an unlocked/orange state, which some banking
  and DRM-gated apps detect and refuse to run on;
- is a prerequisite for GSI/custom-ROM work but is **not** required for
  ReLite's Stage-1 (stock-ROM) functionality at all.

If and when unlocking is pursued, the exact command sequence, backup
requirements, and rollback plan will be written to
`docs/manual-actions.md` for a human to execute deliberately — ReLite
will not run `fastboot flashing unlock` / `fastboot oem unlock` on its
own.
