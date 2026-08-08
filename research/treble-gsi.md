# Treble / dynamic partitions / GSI feasibility — RMX5303

Read-only investigation. Establishes whether a Generic System Image path
is even worth pursuing before any flashing is attempted (master plan
section 30/31 — GSI work only starts after this feasibility research is
positive).

## Procedure

```bash
adb shell getprop ro.treble.enabled
adb shell getprop ro.vndk.version
adb shell getprop ro.product.cpu.abilist
adb shell getprop ro.boot.slot_suffix
adb shell getprop ro.virtual_ab.enabled
adb shell getprop ro.boot.dynamic_partitions

# Partition layout, where permissions allow:
adb shell cat /proc/partitions
adb shell ls /dev/block/by-name/ 2>/dev/null

# DSU/GSI facility probes (read-only — do not invoke a DSU install):
adb shell pm list packages | grep -i dsu
adb shell cmd gsi status 2>/dev/null
adb shell service list | grep -i gsi

# fastbootd availability (from bootloader mode, read-only):
adb reboot fastboot
fastboot devices
fastboot getvar is-userspace
fastboot reboot
```

## What each signal means

| Signal | Interpretation |
|---|---|
| `ro.treble.enabled=true` | Device separates system/vendor per Project Treble — necessary but not sufficient for GSI |
| `ro.vndk.version` | VNDK version the vendor partition was built against; a GSI must match a compatible VNDK |
| `ro.boot.dynamic_partitions=true` | Uses a `super` partition (dynamic partitions) rather than fixed system/vendor partitions |
| `ro.virtual_ab.enabled=true` | Virtual A/B seamless updates — affects how a GSI would be flashed/booted |
| `ro.boot.slot_suffix` (`_a`/`_b`/empty) | A/B vs. A-only partition scheme |
| `fastboot getvar is-userspace` present | `fastbootd` (userspace fastboot) is available, generally required for GSI flashing on dynamic-partition devices |

## Verdict

**UNKNOWN.** No physical RMX5303 was available in the environment that
produced this scaffolding. Fill in the signal table and pick one of the
following once the procedure above has been run:

- `SUPPORTED` — Treble + compatible VNDK + dynamic partitions/fastbootd
  confirmed; a mainline GSI is plausible.
- `POSSIBLY SUPPORTED` — Treble present but one or more signals
  unconfirmed or ambiguous.
- `BLOCKED BY BOOTLOADER` — technical signals are favorable but
  `research/bootloader.md` concluded the bootloader cannot be unlocked.
- `NOT SUPPORTED` — Treble absent, or VNDK/ABI mismatch makes any
  available GSI unbootable.
- `UNKNOWN` — insufficient device access to determine (current status).

## UNISOC platform caveat

GSI compatibility on UNISOC (T7250/UMS9230) devices has historically been
inconsistent across the broader Android GSI community compared to
Qualcomm/MediaTek devices with more mainstream vendor HAL support. A
positive Treble signal does not guarantee a mainline AOSP GSI will boot
with working radio/camera/display — this must be validated empirically
(and cautiously — see master plan section 31: userspace validation only,
never assume vendor blob compatibility) before investing in
`rom/device/realme/RMX5303/` bring-up work.
