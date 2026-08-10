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
be installed as-is. **Exactly one or two or three of the four present
is a hard build failure** (`GradleException`, section 32 of the v0.3.0
plan) — a partially-configured signing setup must never silently fall
back to an unsigned build that then gets mislabeled as signed.

`scripts/package-release.sh` never infers signed status from whether
credentials were configured beforehand — after building, it runs
`scripts/release_manifest.py`, which calls the real Android
`apksigner verify --print-certs` against the actual built APK and
classifies it from that report (`release` / `debug` / `unsigned`; the
last is a hard error, packaging refuses to proceed). With credentials
present it builds and packages a verified-signed `-release.apk`;
without them it falls back to the standard Android **debug**-signed
build (auto-generated per machine, well-known, never intended to prove
publisher identity) and names the artifact `ReLite-Home-vX.Y.Z-debug.apk`
— honest about what it is, confirmed by `apksigner` rather than
assumed. A debug build installs and runs identically to a release
build; what it lacks is a verifiable publisher signature (R8
minification is the `release` build type either way).

If a real ReLite release certificate is ever established, its **public**
SHA-256 digest (never the private key) can be committed to
`docs/release-signing-cert.sha256`; `release_manifest.py` then refuses
to classify any `-release.apk` as signed unless its actual certificate
matches that pin (section 33) — packaging fails loudly instead of
publishing an artifact signed with an unexpected key.

## Packaging a release

```bash
./scripts/package-release.sh 0.5.0
```

One canonical command (section 152-155 of the v0.4.1 plan) produces
everything a GitHub Release needs — not just the APK:

```text
dist/ReLite-Home-v0.5.0-debug.apk
dist/ReLite-Home-v0.5.0-debug.apk.sha256
dist/relite-0.5.0-py3-none-any.whl
dist/relite-0.5.0-py3-none-any.whl.sha256
dist/relite-0.5.0.tar.gz
dist/relite-0.5.0.tar.gz.sha256
dist/release-manifest.json
```

`release-manifest.json` (section 34) records version, git commit, APK
name/SHA-256/signed-status/certificate-DN, the CLI wheel and sdist's
name/SHA-256, and a build timestamp — no secret values. Requires the
`build` package (`pip install build`) on the machine running the script.

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
./gradlew lint (ReLite Home)
./gradlew assembleDebug assembleRelease (ReLite Home)
shellcheck scripts/*.sh benchmarks/scripts/*.sh
profile schema validation (relite.profiles.load_profiles)
device.yaml schema validation (relite.device_metadata.load_device_metadata)
generated package docs up to date (scripts/generate_package_docs.py)
relite/resources/ in sync with profiles/ and devices/ (scripts/sync_resources.py)
wheel builds and installs cleanly outside the checkout
secret / identifier scan
version consistency (pyproject/relite.__version__/Android versionName/CHANGELOG)
working tree clean
```

## Tagging

```bash
git tag -a v0.5.0 -m "ReLite v0.5.0"
git push origin v0.5.0   # only if/when you intend to publish — see below
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
- Link to `benchmarks/results/RMX5303/ab-launcher-vs-relite_home.json`
  (controlled A/B measurement from v0.4.0 — **not re-validated for v0.5.0**,
  see `benchmarks/results/RMX5303/v0.5.0-stress-pass.md` for what v0.5.0
  actually measured this pass)
- Link to `CHANGELOG.md`'s entry for this version
- Rollback instructions: `docs/recovery.md`
