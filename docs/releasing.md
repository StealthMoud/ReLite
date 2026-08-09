# Releasing

## Release signing

`android/relite-home/app/build.gradle.kts` supports an optional
`release` signing config, sourced from (in order of precedence within
each field) `android/relite-home/keystore.properties` — gitignored,
never committed — or these environment variables, meant for CI secrets:

```text
RELITE_RELEASE_STORE_FILE
RELITE_RELEASE_STORE_PASSWORD
RELITE_RELEASE_KEY_ALIAS
RELITE_RELEASE_KEY_PASSWORD
```

`keystore.properties` format (all four keys required for the signing
config to activate):

```properties
storeFile=/absolute/or/relative/path/to/release.jks
storePassword=...
keyAlias=...
keyPassword=...
```

**No keystore is ever committed to this repository.** A real release
key must be generated and held privately by whoever is actually
publishing signed builds — a committed "release" keystore is not a
release key at all, since anyone with repo access could resign
malicious builds as authentic.

If none of the four credentials are present, no `release` signingConfig
is registered at all; `./gradlew assembleRelease` still succeeds
(minified, R8-optimized) but produces an **unsigned** APK that cannot
be installed as-is. `scripts/package-release.sh` handles this
automatically: with credentials present it builds and packages a
signed `-release.apk`; without them it falls back to the standard
Android **debug**-signed build (auto-generated per machine, well-known,
never intended to prove publisher identity) and names the artifact
`ReLite-Home-vX.Y.Z-debug.apk` — honest about what it is. A debug build
installs and runs identically to a release build; what it lacks is a
verifiable publisher signature (R8 minification is the `release` build
type either way).

## Packaging a release

```bash
./scripts/package-release.sh 0.1.0
```

Produces:

```text
dist/ReLite-Home-v0.1.0-debug.apk
dist/ReLite-Home-v0.1.0-debug.apk.sha256
```

`dist/` is gitignored — these are release *artifacts*, attached to a
GitHub Release, not committed to the repository.

## Quality gate before tagging

All of the following must pass — see `docs/development.md` for how to
run each locally; CI (`.github/workflows/ci.yml`) runs the Python/
shellcheck/secret-scan/package-doc-freshness set on every push:

```text
pytest (relite/)
ruff check
mypy
./gradlew testDebugUnitTest (ReLite Home)
./gradlew assembleDebug (ReLite Home)
shellcheck scripts/*.sh benchmarks/scripts/*.sh
profile schema validation
generated package docs up to date (scripts/generate_package_docs.py)
secret / identifier scan
working tree clean
```

## Tagging

```bash
git tag -a v0.1.0 -m "ReLite v0.1.0"
git push origin v0.1.0   # only if/when you intend to publish — see below
```

Pushing a tag (and any `gh release create`) publishes to a remote and is
explicitly **not** done automatically by an agent working in this
repository — it requires the repository owner's deliberate action, same
as any other push. See the top-level agent instructions this project was
developed under: destructive/externally-visible actions (pushing,
creating GitHub releases, force operations) always require a human in
the loop.

## What a GitHub Release for this version should contain

- `ReLite-Home-vX.Y.Z-debug.apk` + its `.sha256` file
  (`scripts/package-release.sh`)
- CLI installation instructions (link to the README "Installation"
  section — the CLI itself is installed from source via
  `./scripts/bootstrap.sh`, not a separate package, until a PyPI release
  is set up; see `docs/development.md`)
- Link to `benchmarks/results/RMX5303/v0.1.0.md`
- Link to `CHANGELOG.md`'s entry for this version
- Rollback instructions: `docs/recovery.md`
