# Contributing to ReLite

Thanks for wanting to help make Android lighter without replacing the hardware layer.

## Ground rules

1. **Reversibility first.** Any change ReLite applies to a device must have a
   documented, tested rollback path. If you can't roll it back, it doesn't
   ship in a default profile.
2. **Evidence over folklore.** Every performance claim needs either a
   benchmark (see `benchmarks/methodology.md`) or a clear architectural
   reason. "I heard this makes Android faster" is not sufficient — see
   `docs/safety.md` for the list of optimizations ReLite explicitly refuses
   to do.
3. **No telemetry, no ads, no accounts.** Ever. Not in the CLI, not in
   ReLite Home, not in a future ReLite OS.
4. **Privacy by default.** Nothing that could identify a specific device or
   person (IMEI, serial, Android ID, MAC/BSSID, SSIDs, phone numbers,
   account emails, tokens) may be committed. Run `relite sanitize` /
   `python -m relite.sanitize` on any fixture before adding it. CI scans for
   this too, but don't rely on CI to catch what you can catch yourself.
5. **Protected packages stay protected.** Do not lower confidence
   thresholds or move packages out of `protected-packages.yaml` without a
   clear, documented reason and — where possible — device evidence.

## Development setup

```bash
git clone https://github.com/StealthMoud/ReLite.git
cd ReLite
./scripts/bootstrap.sh
relite doctor
```

Python 3.11+ is required for the CLI. The Android launcher
(`android/relite-home`) is a standard Gradle/Kotlin project — open it in
Android Studio or build with `./gradlew assembleDebug` from within that
directory.

## Commit style

Small, intentional, conventional commits:

```text
feat: add adb device discovery
fix: correct rollback command for uninstalled system packages
docs: document GSI feasibility findings
test: cover unknown-package classification default
```

Don't bundle unrelated changes into one commit.

## Adding a new device

Device knowledge belongs under `devices/<oem>/<model>/`, never hard-coded
into `relite/`. Copy the RMX5303 layout as a starting template:
`device.yaml`, `packages.yaml`, `protected-packages.yaml`, `tuning.yaml`,
`README.md`, `findings.md`. Unknown packages must default to `keep` — see
`docs/safety.md`.

## Tests

```bash
pip install -e ".[dev]"
pytest
```

CI must pass without a connected Android device — real-device testing is a
local, manual step (`docs/development.md`).
