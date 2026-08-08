# Releasing

## Why the packaged APK is debug-signed, not release-signed

`android/relite-home/app/build.gradle.kts` defines a `release` build
type (minified, ProGuard/R8-optimized) but deliberately has **no
`signingConfig`** attached to it — there is no release-signing keystore
in this repository, and there shouldn't be: a real release key must be
generated and held privately by whoever is actually publishing signed
builds, never committed to source control (a committed "release"
keystore is not a release key at all — anyone with repo access could
resign malicious builds as authentic).

Until that infrastructure exists (a maintainer-held keystore, ideally
wired through CI secrets rather than a local file), ReLite Home release
artifacts are packaged using the standard Android **debug** signing
key (auto-generated per machine, well-known, never intended to prove
publisher identity). This is why the artifact is named
`ReLite-Home-vX.Y.Z-debug.apk` rather than `-release.apk` — the name is
honest about what it is. It installs and runs identically to a release
build; what it doesn't provide is a verifiable publisher signature or
R8 shrinking/obfuscation.

If/when real release-signing infrastructure is set up, update this file
and `scripts/package-release.sh` together — don't just change the output
filename without actually changing what produced it.

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
