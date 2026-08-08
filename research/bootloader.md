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

## Verdict — LOCKED (confirmed on real hardware, 2026-08-08)

Run against a real RMX5303 unit (firmware
`realme/RMX5303EEA/RE60B8:15/AP3A.240905.015.A2/V.R4T2.1776089958:user/release-keys`).
The full procedure above completed successfully and the device
reconnected to `adb` cleanly afterward (~16s host-observed), confirming
the round-trip is genuinely non-destructive.

| Property | Observed value |
|---|---|
| `ro.boot.flash.locked` | `1` (locked) |
| `ro.boot.vbmeta.device_state` | `locked` |
| `ro.boot.verifiedbootstate` | `green` |
| `fastboot getvar product` | *(empty — not populated by this bootloader)* |
| `fastboot getvar current-slot` | `b` (matches `ro.boot.slot_suffix=_b` from Android) |
| `fastboot getvar unlocked` (bootloader mode) | *(empty — not populated)* |
| `fastboot getvar unlocked` (fastbootd/userspace mode) | `no` |
| `fastboot flashing get_unlock_ability` | `FAILED (remote: 'Not implement.')` |

**Verdict: LOCKED.** Every signal agrees. This UNISOC-family bootloader
does not implement the standard `unlocked`/`product` getvars in
bootloader mode (both come back empty rather than erroring, which is a
real, if unhelpful, difference from AOSP reference fastboot behavior —
don't treat an empty response as a crash or as "unlocked"), and
`fastboot flashing get_unlock_ability` returns `Not implement.` rather
than a yes/no — so OEM-unlock-ability cannot be confirmed or ruled out
via that specific query on this build. The `unlocked: no` reported once
in fastbootd (userspace fastboot, see `research/treble-gsi.md`) is the
clearest and most authoritative single signal, and it agrees with the
Android-side properties.

No attempt was made to toggle "OEM unlocking" in Developer Options or to
run any unlock command — see "Why this stays manual" below, which
remains fully in force regardless of this verdict.

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
