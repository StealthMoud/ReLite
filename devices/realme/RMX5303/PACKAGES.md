# RMX5303 package reference (generated)

**Generated from `packages.yaml` / `protected-packages.yaml` by `scripts/generate_package_docs.py` — do not hand-edit.** Regenerate after changing either source file.

## Profile inheritance

```text
safe
  ↓  (+ optional OEM services, duplicate apps, diagnostics)
performance
  ↓  (+ remaining nonessential validated components)
maximum
```

Each level is a strict superset of the one above it: nothing `safe` disables is ever re-enabled by `performance` or `maximum`, and `maximum` never removes anything `safe`/`performance` leave alone without it being listed as an additional action below. See `docs/profiles.md` for what qualifies a package for each level.

## Classified packages

24 packages have a classification entry; 44 packages are hard-protected and never appear in any profile's change list regardless of classification.

| Package | Category | Confidence | safe | performance | maximum | Notes |
|---|---|---|---|---|---|---|
| `com.coloros.activation` | cloud, optional | medium | keep | disable | disable |  |
| `com.coloros.childrenspace` | optional | medium | keep | keep | disable |  |
| `com.coloros.compass2` | optional | medium | keep | keep | disable |  |
| `com.coloros.filemanager` | optional | low | keep | keep | keep |  |
| `com.coloros.gallery3d` | optional | low | keep | keep | keep |  |
| `com.coloros.lockassistant` | recommendations | medium | keep | disable | disable | ⚠ known platform limitation |
| `com.coloros.operationManual` | optional | high | keep | disable | disable |  |
| `com.coloros.video` | optional | low | keep | keep | keep |  |
| `com.coloros.weather.service` | optional | high | keep | disable | disable |  |
| `com.coloros.weather2` | optional | high | keep | disable | disable |  |
| `com.heytap.market` | ads, cloud | high | disable | disable | uninstall-user |  |
| `com.heytap.pictorial` | ads, recommendations | high | disable | disable | uninstall-user |  |
| `com.oplus.commercial` | ads | high | disable | disable | uninstall-user |  |
| `com.oplus.encryption` | optional, security | low | keep | keep | disable |  |
| `com.oplus.gamespace` | games, optional | medium | keep | keep | disable |  |
| `com.oplus.postmanservice` | diagnostics | medium | keep | disable | disable |  |
| `com.oplus.stdid` | analytics, diagnostics | medium | keep | keep | disable |  |
| `com.oppo.engineermode` | diagnostics | medium | keep | keep | disable |  |
| `com.oppo.engineermode.camera` | diagnostics | medium | keep | keep | disable |  |
| `com.oppo.engineermode.network` | diagnostics | medium | keep | keep | disable |  |
| `com.oppo.quicksearchbox` | recommendations | medium | keep | disable | uninstall-user |  |
| `com.realme.as.music` | optional | high | keep | disable | uninstall-user |  |
| `com.realme.backuprestore` | optional | medium | keep | keep | disable |  |
| `com.realme.smartdrive` | optional | medium | keep | keep | disable |  |

## Protected packages (never touched by any profile)

| Package | Reason |
|---|---|
| `android` | Android framework package itself. |
| `com.android.bluetooth` | Bluetooth stack. |
| `com.android.cameraextensions` | Camera extensions proxy service. |
| `com.android.carrierconfig` | Carrier configuration required for correct radio/APN behavior. |
| `com.android.externalstorage` | External storage provider. |
| `com.android.keychain` | System keychain/keystore UI. |
| `com.android.launcher3` | Confirmed via `cmd package resolve-activity` as the actual current default launcher on this device (SearchLauncherQuickStep variant, package com.android.launcher3). |
| `com.android.location.fused` | Fused location provider backend. |
| `com.android.mtp` | USB file transfer provider. |
| `com.android.phone` | Telephony stack |
| `com.android.providers.contacts` | Contacts provider |
| `com.android.providers.downloads` | Downloads provider. |
| `com.android.providers.downloads.ui` | Downloads provider UI companion. |
| `com.android.providers.media` | Media provider |
| `com.android.providers.telephony` | SMS/MMS/APN content provider. |
| `com.android.server.telecom` | Telecom/call routing framework. |
| `com.android.settings` | Settings app. |
| `com.android.settings.intelligence` | Settings search/suggestions backend used by the Settings app itself. |
| `com.android.stk` | SIM toolkit. |
| `com.android.storagemanager` | Storage manager (free-up-space UI, storage settings backend). |
| `com.android.systemui` | SystemUI |
| `com.android.wifi.resources.uni_marlin3` | Device/platform-specific Wi-Fi resource overlay observed on this unit. |
| `com.android.wifi.resources.uni_marlin3.mainline` | Mainline variant of the above Wi-Fi resource overlay. |
| `com.coloros.phonemanager` | "Phone Manager" — confirmed PRIVILEGED with signature|privileged permissions incl. |
| `com.google.android.captiveportallogin` | Captive portal detection required for Wi-Fi network validation (mainline module). |
| `com.google.android.documentsui` | DocumentsUI file picker/storage access framework UI (this device does not have com.android.documentsui as a standalone package). |
| `com.google.android.networkstack` | Core network connectivity stack (mainline module; com.android.networkstack is not installed as a standalone package on this device). |
| `com.google.android.networkstack.tethering` | Tethering component of the network stack mainline module. |
| `com.google.android.packageinstaller` | Package installation. |
| `com.google.android.permissioncontroller` | Runtime permission grant UI and enforcement. |
| `com.google.android.webview` | System WebView provider (this device does not have com.android.webview as a fallback package). |
| `com.google.android.wifi.resources` | Wi-Fi stack resources (mainline module; this device does not have com.android.wifi.resources as a standalone package). |
| `com.oplus.aod` | "Always-On Display" |
| `com.oplus.audio.effectcenter` | "AudioEffectCenter" |
| `com.oplus.batterywarning` | "Battery" |
| `com.oplus.camera` | "Camera" — confirmed via on-device Settings app-info label. |
| `com.oplus.cota` | "Carrier settings update" — confirmed via on-device Settings app-info label. |
| `com.oplus.gesture` | "Gestures & motions" — confirmed PERSISTENT flag via dumpsys. |
| `com.oplus.mediacontroller` | "AospMediaController" |
| `com.oplus.safecenter` | "Safe Center" — OEM security/permission-management center. |
| `com.oplus.systemui.plugins` | SystemUI plugin framework |
| `com.oplus.uiengine` | OEM UI/animation engine used by SystemUI and the launcher; disabling risks breaking system animations. |
| `com.oppo.ota` | OTA update client — confirmed installed on this device. |
| `com.sprd.cameracalibration` | UNISOC/Spreadtrum camera calibration component |

