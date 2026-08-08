# Kernel source research — RMX5303 / UNISOC UMS9230

Read-only research survey. Nothing here is vendored into this repository
— see `NOTICE.md` for why (licensing needs review, and blob
redistribution rights are unconfirmed).

## Candidate source repositories

realme publishes kernel sources per their open-source obligations under
GPLv2. Two repository families are relevant to the RMX5303's platform
generation:

1. **`realme-kernel-opensource/realme_C71-AndroidV-common-source`**
   Named directly after the C71 product family. Believed to track the
   `Linux 5.15.x` family, consistent with UNISOC UMS9230-generation
   devices of this era.

2. **`realme-kernel-opensource/realme_Note80-Note80s-C71-NARZO_80Lite-P4_Lite-Note70-Note70s-Note70T-AndroidB-kernel-source`**
   A newer, multi-device combined source repository that includes C71 in
   its name, with a branch `realme/ums9230_b_16.0` — the `_b_` and
   `16.0` suggest an Android 16-generation baseline layered onto the same
   UMS9230 hardware platform.

**Do not assume which of these two (if either) matches the actual RMX5303
unit's currently installed firmware.** `relite device` reads
`ro.build.version.release`, `ro.build.version.sdk`,
`ro.build.version.security_patch`, and `ro.build.fingerprint` from the
live device; `uname -a` reports the running kernel version. Cross-check
those against each candidate repository's stated kernel version and
target Android version before treating either as authoritative for this
unit.

## What to check once a repository is selected

| Question | Why it matters |
|---|---|
| Kernel version (`uname -r` on device vs. repo's `Makefile` version) | Confirms the source tree actually matches the running kernel, not just the product family |
| Build system | UNISOC platforms typically use a Makefile/Kconfig flow layered under a vendor build wrapper — confirm what's actually present before assuming an AOSP-standard `build/` setup |
| Toolchain requirements | Clang/GCC version pinned by the source tree; mismatches silently produce broken or unbootable images |
| Device-specific defconfig | Whether a `RMX5303`-named (or a shared C71-family) defconfig exists, or only a generic UMS9230 reference config |
| Proprietary/vendor module stubs | Kernel modules that call into closed vendor userspace (camera, sensors, modem) — these determine whether the kernel alone is buildable/bootable without additional binary blobs |
| Completeness | Whether the published tree is genuinely buildable end-to-end, or a partial source drop (common with GPL-compliance-only kernel releases that omit build scripts or vendor glue) |

## Verdict

**Not yet evaluated against a real device.** This file records where to
look and what to check; it does not assert that either repository is
complete, buildable, or actually matches the RMX5303's shipped kernel.
Update this file once:

1. A real RMX5303's `uname -a` / build fingerprint has been captured
   (`devices/realme/RMX5303/findings.md`), and
2. One of the two repositories above (or a third, not-yet-identified one)
   has been checked out and inspected for buildability.

## Relationship to the GSI path

Master plan section 31 is explicit: don't start a from-scratch device ROM
before proving a generic AOSP/GSI system can work on this hardware at
all. Kernel source availability is necessary but not sufficient for that
— see `research/treble-gsi.md` for the userspace-first feasibility
question this file's findings feed into.
