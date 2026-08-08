# RMX5303 human functionality validation checklist

Everything ReLite can verify over ADB (SystemUI/Settings/Camera launch,
package state, boot completion, logcat crash scanning) was checked
automatically for the `safe`, `performance`, and `maximum` profiles —
see `devices/realme/RMX5303/findings.md` for the results. The items below
require a human physically operating the device and are marked
**PENDING HUMAN VALIDATION** — implementation and automated testing did
not stop to wait for these, per master plan section 17.

Run through this list after applying a profile (or after installing
ReLite Home as default launcher) to confirm nothing that ADB can't see
was affected.

## Telephony

- [ ] PENDING HUMAN VALIDATION — Outgoing call connects and has audio both ways
- [ ] PENDING HUMAN VALIDATION — Incoming call rings and can be answered
- [ ] PENDING HUMAN VALIDATION — SMS send
- [ ] PENDING HUMAN VALIDATION — SMS receive
- [ ] PENDING HUMAN VALIDATION — Mobile data connects and browses normally

## Wireless

- [ ] PENDING HUMAN VALIDATION — Wi-Fi connects to a known network
- [ ] PENDING HUMAN VALIDATION — Bluetooth pairing with a new device
- [ ] PENDING HUMAN VALIDATION — Bluetooth audio (headphones/speaker) playback

## Camera / media

- [ ] PENDING HUMAN VALIDATION — Camera photo capture
- [ ] PENDING HUMAN VALIDATION — Camera video capture
- [ ] PENDING HUMAN VALIDATION — Flashlight toggles on/off
- [ ] PENDING HUMAN VALIDATION — Microphone records audio correctly
- [ ] PENDING HUMAN VALIDATION — Speaker playback (media + call audio)
- [ ] PENDING HUMAN VALIDATION — Wired/USB audio output, if used

## Location / biometrics / power

- [ ] PENDING HUMAN VALIDATION — GPS acquires a fix in a mapping app
- [ ] PENDING HUMAN VALIDATION — Fingerprint unlock enrolls and works
- [ ] PENDING HUMAN VALIDATION — Charging (wired) shows correct rate/indicator
- [ ] PENDING HUMAN VALIDATION — Charging (wireless), if supported/used

## System

- [ ] PENDING HUMAN VALIDATION — Alarm fires at the set time with sound
- [ ] PENDING HUMAN VALIDATION — Notifications arrive for a third-party messaging app
- [ ] PENDING HUMAN VALIDATION — Play Store installs/updates an app successfully
- [ ] PENDING HUMAN VALIDATION — Banking/payment app(s) launch and function normally, if used — some apps refuse to run if they detect specific disabled system components; explicitly worth checking after `performance`/`maximum`

## Post-reboot

- [ ] PENDING HUMAN VALIDATION — After a reboot, unlock with your normal
      PIN/pattern/biometric, then confirm ReLite Home (if set as default)
      opens without error and any workspace layout you configured is
      still there. Automated testing found that a reboot always leaves
      the device at a real lock-screen credential prompt
      (Android's file-based-encryption/Direct-Boot behavior, not a
      ReLite issue — see `devices/realme/RMX5303/findings.md`
      2026-08-08 entry) and correctly did not attempt to unlock it.

## What was already automatically verified (no action needed)

- SystemUI, Settings, package installer, permission controller, camera
  app launch, Bluetooth/telephony services present, no `FATAL EXCEPTION`
  / ANR in logcat — checked after each of `safe`, `performance`, and
  `maximum`.
- Boot completes after each profile change (`sys.boot_completed=1`).
- Disabled/uninstalled-for-user-0 packages remain reversible
  (`relite restore` / `docs/recovery.md`).
