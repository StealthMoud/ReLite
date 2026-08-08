# RMX5303 findings log

Running, dated log of what has actually been observed by running ReLite's
read-only reconnaissance (`relite doctor`, `relite device`, `relite scan`,
`relite tune ram-expansion probe`, `research/bootloader.md` procedure,
`research/treble-gsi.md` procedure) against a real RMX5303 unit. Anything
not listed here as **observed** is inferred from public specifications or
from the classifier's conservative defaults, and must not be treated as
confirmed device behavior.

All entries here must already be sanitized (see `relite/sanitize.py`) — no
IMEI, serial, Android ID, MAC address, SSID, or account identifier.

## 2026-08-08 — initial scaffolding, no device attached

- ReLite CLI, classification engine, protected-package policy, profiles,
  benchmark harness, restore engine, and ReLite Home launcher skeleton were
  implemented and unit-tested against fixtures (`tests/fixtures/`), without
  a physical RMX5303 connected to the development environment.
- `packages.yaml` entries in this directory are seeded from names publicly
  documented across multiple realme/ColorOS-family debloat references
  (secondary signal only, per master plan section 10) and are capped at
  `confidence: medium` pending on-device validation.
- `protected-packages.yaml` is seeded from well-known AOSP framework
  package names (`com.android.systemui`, `com.android.phone`, etc.) that
  are stable across virtually all Android OEM skins including realme's.
  The exact realme-branded launcher package name
  (`protected-packages.yaml` currently guesses `com.oppo.launcher` as a
  placeholder) is **unconfirmed** and must be corrected from a real
  `relite scan` dump before `relite apply` is run against a live device.
- RAM Expansion: no on-device setting key has been observed yet. See
  `relite/tuning.py::RAM_EXPANSION_CANDIDATE_KEYS` for the candidate keys
  `relite tune ram-expansion probe` will search for once a device is
  connected. If no key is found, the manual toggle path (Settings app UI)
  must be documented in `docs/manual-actions.md` instead.
- Bootloader / Treble / GSI feasibility: not yet investigated on real
  hardware. See `research/bootloader.md` and `research/treble-gsi.md` for
  the exact read-only procedure that must be run and the verdict grid to
  fill in once a device is available.
- Android version, security patch level, and build fingerprint: **not
  assumed**. Per the master plan, these must be read live via
  `relite device` from an actual unit rather than taken from any online
  listing. This log intentionally leaves them blank until that happens.

## 2026-08-08 — full real-device validation pass

First real RMX5303 unit connected and validated end-to-end.

**Firmware observed:**

```text
realme/RMX5303EEA/RE60B8:15/AP3A.240905.015.A2/V.R4T2.1776089958:user/release-keys
```

- Android release: 15, SDK 35, security patch 2026-04-01
- Display ID: `RMX5303GDPR_15_C.55`; region EEA/Germany
  (`persist.sys.oppo.region=DE`)
- Kernel: `5.15.178-android13-8-00006-g0c6055fd2d8b-ab13363910` — see
  `research/kernel.md`
- Product identifiers: `ro.product.model=RMX5303`,
  `ro.product.device=RE60B8`, `ro.product.name=RMX5303EEA`,
  `ro.product.brand=realme`

**Hardware / platform:**

- CPU ABI: `arm64-v8a` (`abilist: arm64-v8a,armeabi-v7a,armeabi`)
- Treble: enabled, VNDK 33
- Dynamic partitions: yes, `super` partition confirmed via fastbootd
- Virtual A/B: enabled, current slot `b`
- **Physical RAM: corrected to ~8 GB**, not 6 GB as originally briefed —
  `MemTotal` reads 7,968,356 kB. RAM Expansion was confirmed OFF at
  measurement time, so this isn't a RAM-expansion artifact. See
  `devices/realme/RMX5303/device.yaml` for the full note.
- zRAM: `zram_enabled=1`, ~4.7 GB of zram-backed swap active
  (`dumpsys meminfo` "ZRAM" line), independent of RAM Expansion.

**RAM Expansion ("6+16 GB" marketing claim) — fully resolved:**

- The feature exists (`com.android.settings/.Settings$RamExpansionActivity`,
  reachable via `am start -a oplus.intent.action.settings.RAM_EXPANSION_SETTINGS`).
- On-device UI dump confirms three selectable amounts: **Off / 6GB /
  10GB / 16GB** — "16GB" is the maximum *offered*, not a fixed amount.
- **Confirmed OFF by default** on this unit (`checked="false"` on the
  toggle switch, read via `uiautomator dump`).
- No `settings put`-able key was found in global/secure/system
  namespaces (`relite tune ram-expansion probe` found nothing) — the
  feature is controlled by the Settings app internally, not exposed as a
  plain setting. Left as a documented manual toggle
  (`docs/manual-actions.md`), consistent with master plan section 14.
- Since it's off by default and no reliable programmatic control exists,
  ReLite does not attempt to toggle it; profiles leave it untouched.

**Package inventory:** 402 total packages (301 system, 101 third-party),
2 disabled by OEM default (`com.google.android.gms.supervision`,
`com.sprd.powersavemodelauncher`).

**Package classification — validated, not just seeded:** every entry now
in `packages.yaml`/`protected-packages.yaml` was cross-checked against
this device's actual `pm list packages -f` output, `dumpsys package`
flags/permissions, and on-device Settings "App info" labels (captured
via `uiautomator dump`, not guessed). See those two files' file-level
comments for the full evidence trail. Highlights:

- Confirmed ad/promotional (high confidence): `com.heytap.market` ("App
  Market"), `com.heytap.pictorial` ("Lock Screen Magazine" — confirmed
  tied to a third-party content partner via a Haokan user-agreement
  string referenced in `com.coloros.bootreg`), `com.oplus.commercial`
  ("CommercialMidGround" — holds `DISABLE_KEYGUARD`, a known lock-screen-ad
  permission pattern).
- `com.oplus.postmanservice` package name suggested a push/ads service
  pre-validation; actually labeled "Diagnostic Tool" with broad
  privacy-sensitive permissions (location, fingerprint-adjacent,
  cross-user) — reclassified to `diagnostics`, not `ads`.
- Real launcher package confirmed via `cmd package resolve-activity`:
  `com.android.launcher3` (SearchLauncherQuickStep variant). The
  placeholder `com.oppo.launcher` guess from the pre-device-validation
  seed was wrong for this device and has been removed.
- `protected-packages.yaml` corrected: several placeholder AOSP names
  (`com.android.packageinstaller`, `com.android.permissioncontroller`,
  `com.android.webview`, `com.android.biometrics`, `com.nearme.romupdate`,
  `com.android.networkstack`, `com.android.captiveportallogin`,
  `com.android.documentsui`, `com.android.wifi.resources`,
  `com.android.camera2`) do not exist on this build — replaced with the
  Google-mainline-module / device-specific names this build actually
  uses (`com.google.android.packageinstaller`,
  `com.google.android.networkstack`, `com.oppo.ota`, `com.oplus.camera`,
  etc.).

**Real-device bug found and fixed in `relite/actions.py`:**
`pm disable-user` for `com.coloros.lockassistant` exits 0 but the
platform silently refuses the change (`Package ... new state: default`
instead of `new state: disabled-user`) — this OEM build appears to
protect it from user-level disable independently of ReLite's own policy.
`apply_plan` previously trusted exit code alone and would have recorded
this as a false "ok". Fixed to check the actual reported new-state
string; covered by a new regression test
(`tests/test_actions.py::test_apply_plan_detects_platform_silently_refusing_disable`).

**Real-device bug found and fixed in `relite report`:** benchmark result
files were sorted alphabetically, putting "maximum" before "stock" as
the baseline column — backwards from the intended stock→safe→
performance→maximum progression. Fixed with an explicit canonical order.

**Profiles applied and verified, in order, on this unit:**

| Profile | Packages changed | Result |
|---|---:|---|
| safe | 3 disabled | Verified: boot completes, SystemUI/Settings/Camera/Bluetooth/telephony intact, no crashes in logcat |
| performance | +8 disabled (1 of 9 attempted refused by platform, see bug above) | Verified: same regression checklist, no crashes |
| maximum | +16 changed (15 succeeded, 1 pre-existing platform refusal), 3 of those upgraded from disable to uninstall-for-user-0 | Verified: same regression checklist, no crashes; confirmed APKs remain on `/system`/`/data` (reversible via `cmd package install-existing --user 0`) |

See `benchmarks/results/RMX5303/latest.md` for the full stock vs. safe vs.
performance vs. maximum comparison table (real numbers, not fabricated).
Headline results: enabled packages 400→385, camera cold start 724ms→596ms
median, settings cold start 1177ms→583ms median. MemAvailable and PSS
deltas across profiles were within normal single-pass measurement noise
(background cache/launcher-rescan activity) and are reported as observed,
not spun into a stronger claim than the data supports.

**ReLite Home installed and tested on real hardware:**

- Builds, installs, and launches cleanly (`assembleDebug`,
  `adb install -r`, cold start ~1.1-1.5s, no crashes in logcat).
- App drawer, local search, and folder-adjacent flows all confirmed
  working via `uiautomator`/screenshot capture against this device's
  actual installed app set.
- Survives `am kill`/force-stop + relaunch with no crash.
- Stays locked in portrait on rotation (by design,
  `android:screenOrientation="portrait"`), no crash.
- **Real regression found and fixed**: `IconCache` originally cached the
  raw, full-resolution `Drawable` from `LauncherActivityInfo.getIcon()`
  bounded only by *entry count* (200), not memory. On this device that
  pushed ReLite Home's Native Heap to ~136 MB and its settled PSS above
  the stock launcher's. Fixed to render each icon once into a bitmap
  sized to the actual display size and bound the cache by real byte size
  (~6 MB budget). After the fix, ReLite Home's settled PSS (~143 MB) is
  **~29% lower** than the stock launcher's settled PSS (~202 MB),
  measured back-to-back on the same device after both processes had
  several seconds to settle. See
  `android/relite-home/app/src/main/java/io/relite/home/util/IconCache.kt`.
- **Not yet validated**: full app state survival across a device
  *reboot*. `adb reboot` was performed as part of profile-persistence
  testing; the device came back up with a real lock-screen credential
  active (FBE/Direct Boot — `mDreamingLockscreen=true`,
  `screencap` returning blank over the secure keyguard). This is
  standard Android behavior for any non-direct-boot-aware app (not a
  ReLite-specific issue) and requires the device owner to unlock with
  their own PIN/pattern/biometric once after reboot — correctly not
  attempted or guessed here. Debloat state (disabled/uninstalled
  packages) was confirmed to persist correctly across the reboot via
  `pm list packages -d`, which does not require unlocking to query.

**Bootloader — LOCKED (see `research/bootloader.md` for full evidence).**
Round-tripped through `adb reboot bootloader` → `fastboot getvar` queries
→ `fastboot reboot` cleanly; `adb` reconnected in ~16s both times this
was tested. No unlock command was run.

**Treble/GSI — POSSIBLY SUPPORTED, BLOCKED BY BOOTLOADER** (see
`research/treble-gsi.md`). All userspace prerequisites (Treble, VNDK 33,
dynamic partitions, Virtual A/B, working fastbootd) are confirmed
present; blocked purely by the locked bootloader and the absence of an
in-Android DSU service on this build.

**Kernel** — running version `5.15.178` plausibly matches
`realme_C71-AndroidV-common-source` more closely than the
`ums9230_b_16.0`-branch repository (Android-16 naming doesn't match this
Android-15 unit) — see `research/kernel.md`. Not yet checked out/verified
for buildability.

**Firmware recovery** — see `research/firmware-recovery.md`; not yet
verified against a legitimately-obtained stock firmware image for this
exact model/region.

## Template for future entries

```markdown
## YYYY-MM-DD — <what was done>

- Firmware observed: <ro.build.fingerprint, sanitized>
- Android release / SDK / security patch: <values>
- Findings: <what was confirmed, corrected, or ruled out>
- Action taken in packages.yaml / protected-packages.yaml / tuning.yaml: <diff summary>
```
