# Development

## Repository layout

```text
relite/            Python CLI engine (device-agnostic)
devices/            Per-device classification data, protected packages, tuning
profiles/            safe / performance / maximum profile definitions
android/relite-home/ ReLite Home launcher (Kotlin, standard Views)
benchmarks/          Methodology, scripts, per-device results
research/            Read-only platform research (bootloader, GSI, kernel)
scripts/             bootstrap.sh, doctor.sh, build-launcher.sh
tests/               relite/ unit tests, fixtures
docs/                This directory
```

## Python CLI

Requires Python 3.11+.

```bash
git clone https://github.com/StealthMoud/ReLite.git
cd ReLite
./scripts/bootstrap.sh
source .venv/bin/activate
relite doctor
```

### Module map (`relite/`)

| Module | Responsibility |
|---|---|
| `adb.py` | Testable wrapper around the `adb` binary; device state (online/offline/unauthorized/multiple/timeout) |
| `device.py` | Read-only device reconnaissance (build props, Treble/AVB/partition state) |
| `packages.py` | Package inventory via `pm`/`dumpsys` |
| `snapshot.py` | Full point-in-time device snapshot, schema-versioned |
| `classifier.py` | Package classification database + protected-package policy (the safety authority) |
| `actions.py` | Reversible plan-and-apply engine, journals every change |
| `restore.py` | Rollback from journal or snapshot |
| `tuning.py` | Animation scale, RAM Expansion probing, Private DNS |
| `benchmark.py` | App start / boot / memory timing with median/min/max/p95 |
| `report.py` | Markdown/JSON/CSV comparison reports |
| `sanitize.py` | Strips identifying data before anything is written to disk |
| `cli.py` | Wires everything into the `relite` command |

### Running tests

```bash
pip install -e ".[dev]"
pytest
ruff check relite tests
mypy relite
```

All tests run against `tests/conftest.py`'s `FakeAdbRunner` — no real
`adb` binary or connected device required. Real-device testing is a
manual, local step: run the same CLI commands against an actual phone
and compare against `devices/realme/RMX5303/findings.md`.

## ReLite Home (Android)

Requires a standard Android SDK (compileSdk 34) and JDK 17+.

```bash
cd android/relite-home
echo "sdk.dir=$ANDROID_HOME" > local.properties   # Android Studio does this for you
./gradlew assembleDebug
./gradlew testDebugUnitTest
```

See `android/relite-home/README.md` for the module layout. Unit tests
under `app/src/test/` cover framework-independent logic (`AppSearch`,
`WorkspaceRepository`) without needing an emulator.

## Adding a new profile behavior

Profile-level policy (`profiles/*.yaml`) describes intent; the actual
per-package decision always comes from
`devices/<model>/packages.yaml`'s `action.<profile>` field, filtered
through `protected-packages.yaml`. Don't add profile-specific logic to
`relite/classifier.py` itself — profiles are data, not code branches,
so a new profile only ever requires a new `profiles/<name>.yaml` plus
`action.<name>` entries in each device's `packages.yaml`.

## Style

- Small, intentional commits — see `CONTRIBUTING.md` for the expected
  commit message style.
- No comments explaining *what* code does — only *why*, when the reason
  isn't obvious from the code itself.
- Ruff + mypy must pass; CI enforces this (`.github/workflows/ci.yml`).
