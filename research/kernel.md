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

## Verdict — running kernel identified, source tree not yet checked out

`adb shell uname -a` on a real RMX5303 unit (2026-08-08):

```text
Linux localhost 5.15.178-android13-8-00006-g0c6055fd2d8b-ab13363910 #1 SMP PREEMPT Tue Apr 15 22:34:18 UTC 2025 aarch64 Toybox
```

Parsed:

| Field | Value | Notes |
|---|---|---|
| Kernel version | `5.15.178` | Matches the "Linux 5.15.x family" documented for `realme_C71-AndroidV-common-source` above — **not** a match for a 6.x-series kernel, ruling out any newer GKI branch. |
| GKI branch marker | `android13-8` | Android *Common Kernel* branch naming (`android13-5.15`), tracking the kernel's GKI ABI generation — does **not** mean the device runs Android 13 userspace (this unit runs Android 15, confirmed via `ro.build.version.release`). OEMs frequently ship a kernel built against an older GKI branch than their current userspace Android version; normal, not evidence of a mismatch. |
| Build hash / date | `g0c6055fd2d8b-ab13363910`, dated 2025-04-15 | Useful as an exact anchor if/when a matching commit is sought in either candidate repository. |

**Assessment:** the `5.15.x` kernel version and the "AndroidV" naming in
`realme_C71-AndroidV-common-source` (V = Android 15's codename/version
letter, matching this unit's `ro.build.version.release=15`) make that
repository the more plausible match of the two candidates for *this*
firmware, more so than the second repository's `ums9230_b_16.0` branch
(which names Android 16, not yet reflected in this unit's shipped
`AP3A.240905.015.A2`/`2026-04-01` build). This is a plausibility
assessment from version-string evidence, **not** a confirmed source
match — the repository has not been cloned/checked out in this
environment to verify buildability, defconfig presence, or toolchain
requirements (the "What to check" table above is still open). No kernel
modification has been attempted or is planned before that verification
step, per master plan section 4.

## Relationship to the GSI path

Master plan section 31 is explicit: don't start a from-scratch device ROM
before proving a generic AOSP/GSI system can work on this hardware at
all. Kernel source availability is necessary but not sufficient for that
— see `research/treble-gsi.md` for the userspace-first feasibility
question this file's findings feed into.
