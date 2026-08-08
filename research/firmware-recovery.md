# Stock firmware recovery research — RMX5303

Desk research only — no flashing tool has been installed or run in this
environment. This exists so that *if* bootloader unlock / GSI work is
ever pursued (both currently blocked — see `research/bootloader.md`,
`research/treble-gsi.md`), a recovery path is documented and understood
**before** any irreversible step, per master plan section 20.

## What's confirmed from this device

- Partition scheme: A/B with dynamic partitions (`super`), Virtual A/B
  enabled, current slot `b` — see `research/treble-gsi.md`.
- `df -h` on the live device shows separate `dm-*` mounts for `/`,
  `/system_ext`, `/vendor`, `/odm`, `/product`, `/vendor_dlkm`, each
  backed by the dynamic-partition `super` image.
- Bootloader is locked (`research/bootloader.md`) — no partition can be
  flashed in the current state regardless of recovery-tooling
  availability.

## Realme/UNISOC firmware format (general, not yet device-verified)

Realme (BBK/OPPO family) devices, including UNISOC-platform models,
typically distribute stock firmware as:

- An **OFP** (OPPO Firmware Package) or **OZIP** container for
  OPPO/realme-branded UNISOC and MediaTek devices — these are
  proprietary archive formats requiring OEM-aware extraction tooling
  (e.g. community `ofp_qc_decrypt`/`ofp_mtk_decrypt`-style tools; the
  UNISOC-specific equivalent has not been identified/verified for this
  device family in this research pass).
- Official firmware is normally only distributed through realme's
  own OTA channel and regional support/service-center tooling, not as a
  freely downloadable end-user package — unlike some other Android OEMs.

**Not yet verified for RMX5303 specifically**: the exact container
format, whether a full stock ROM package is publicly obtainable for this
model/region (`RMX5303EEA`, EEA/Germany), and which flashing tool (if
any) the realme service-center process uses for UNISOC T7250/UMS9230
devices. This needs dedicated research against realme's own support
channels before being treated as fact.

## Recovery mechanisms available without unlocking

These work today, on the locked bootloader, and require no flashing
tool:

1. **`relite restore`** — reverses every ReLite-applied change (package
   disable/uninstall-for-user, settings) — see `docs/recovery.md`. This
   is the primary and, for anything ReLite itself did, sufficient
   recovery path.
2. **OTA-based recovery** — since the device uses Virtual A/B with a
   `super` partition and OTA updates apply to the *other* slot, a
   forced OTA re-installation (via Settings → System Update, if the OEM
   exposes a "redownload" or full-package option) can restore the
   active slot's system image without unlocking. Not tested in this
   pass (would consume mobile data / take time without being necessary
   for any state ReLite itself changed).
3. **Standard Android Recovery** (`adb reboot recovery`) — provides
   "Wipe data/factory reset" as a last resort (see `docs/recovery.md`
   section 6). This is a normal, OEM-supported recovery boot mode, not a
   custom recovery, and does not require an unlocked bootloader.

## What would be needed before any unlock/flash work

If bootloader unlock is ever pursued (a deliberate, human-executed,
documented step per `docs/manual-actions.md` — never automatic):

1. A verified, legitimate source for a full stock firmware image
   matching `RMX5303EEA` / `RE60B8` / the exact build fingerprint
   (`AP3A.240905.015.A2`), so a flash-back path exists if a GSI or
   experimental change breaks the device.
2. Confirmation of which flashing tool works with this SoC family
   (UNISOC-specific, not the Qualcomm/MediaTek tools referenced in most
   public OPPO/realme flashing guides).
3. A full `relite snapshot` plus off-device backup of anything not
   ReLite-tracked (personal files, app data) — since unlocking itself
   triggers a factory data reset before any flashing even begins.

None of this is required for ReLite's Stage-1 (stock-ROM CLI + launcher)
functionality, which this research does not block or depend on.
