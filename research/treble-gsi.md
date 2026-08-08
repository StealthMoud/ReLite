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

## Verdict — POSSIBLY SUPPORTED, BLOCKED BY BOOTLOADER (confirmed 2026-08-08)

Run against a real RMX5303 unit. Every technical (userspace-visible)
prerequisite is present and confirmed:

| Signal | Observed value |
|---|---|
| `ro.treble.enabled` | `true` |
| `ro.vndk.version` | `33` |
| `ro.product.cpu.abilist` | `arm64-v8a,armeabi-v7a,armeabi` |
| `ro.boot.dynamic_partitions` | `true` |
| `ro.virtual_ab.enabled` | `true` |
| `ro.boot.slot_suffix` | `_b` (A/B device) |
| `fastboot getvar is-userspace` (via `adb reboot fastboot`) | `yes` — **fastbootd confirmed working** |
| `fastboot getvar super-partition-name` (fastbootd) | `super` — standard dynamic-partition layout |
| `cmd gsi status` | `Can't find service: gsi` — **no in-Android DSU (Dynamic System Updates) service on this build** |
| `pm list packages \| grep -i dsu` | no matches — no DSU-related package installed |

**Verdict: `POSSIBLY SUPPORTED, BLOCKED BY BOOTLOADER`.** Treble, a
recent VNDK (33), dynamic partitions with a confirmed `super` partition,
Virtual A/B, and a working fastbootd are exactly the prerequisites a
mainline GSI needs — this device is architecturally GSI-capable. Two
things stand between that and an actual GSI boot:

1. **The bootloader is locked** (`research/bootloader.md`) — fastbootd
   can be entered and queried, but `fastboot flash system <gsi>.img`
   would be rejected (or blocked by AVB) without an OEM unlock, which
   ReLite does not perform automatically.
2. **No on-device DSU path exists.** This build ships without the `gsi`
   system service and without any DSU-related package, so even a
   theoretical future unlock would require flashing a GSI via fastbootd
   directly (`fastboot flash system` after unlock) — there is no
   "install GSI temporarily via Settings" convenience path here the way
   some AOSP-close devices offer.

Both fastboot-mode and fastbootd-mode round-trips (`adb reboot
bootloader` → query → `fastboot reboot`, and `adb reboot fastboot` →
query → `fastboot reboot`) completed cleanly with `adb` reconnecting and
`sys.boot_completed=1` afterward both times — confirming this entire
investigation was non-destructive and repeatable.

## UNISOC platform caveat

GSI compatibility on UNISOC (T7250/UMS9230) devices has historically been
inconsistent across the broader Android GSI community compared to
Qualcomm/MediaTek devices with more mainstream vendor HAL support. A
positive Treble signal does not guarantee a mainline AOSP GSI will boot
with working radio/camera/display — this must be validated empirically
(and cautiously — see master plan section 31: userspace validation only,
never assume vendor blob compatibility) before investing in
`rom/device/realme/RMX5303/` bring-up work.
