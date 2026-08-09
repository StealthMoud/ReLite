#!/usr/bin/env bash
# Package the ReLite Home APK into dist/, with a SHA-256 checksum and a
# release manifest, for a GitHub Release. Signing status is never
# inferred from whether credentials were configured at build time —
# scripts/release_manifest.py runs `apksigner verify` against the actual
# built APK and classifies it from that (sections 31-34).
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."

VERSION="${1:?usage: package-release.sh <version, e.g. 0.3.0>}"
DIST_DIR="dist"
ANDROID_DIR="android/relite-home"

mkdir -p "$DIST_DIR"

if [ -f "$ANDROID_DIR/keystore.properties" ] || [ -n "${RELITE_RELEASE_STORE_FILE:-}" ]; then
  echo "Release-signing credentials found — building assembleRelease." >&2
  (cd "$ANDROID_DIR" && ./gradlew assembleRelease --console=plain)
  built_apk="$ANDROID_DIR/app/build/outputs/apk/release/app-release.apk"
else
  echo "No release-signing credentials found — building assembleDebug." >&2
  echo "See docs/releasing.md to set up real release signing." >&2
  (cd "$ANDROID_DIR" && ./gradlew assembleDebug --console=plain)
  built_apk="$ANDROID_DIR/app/build/outputs/apk/debug/app-debug.apk"
fi

# Classify the built APK by actually verifying its signature, not by
# whether credentials happened to be configured beforehand.
manifest_tmp=$(mktemp)
python3 scripts/release_manifest.py "$VERSION" "$built_apk" --out "$manifest_tmp"
signed_status=$(python3 -c "import json,sys; print(json.load(open(sys.argv[1]))['apk']['signed_status'])" "$manifest_tmp")

case "$signed_status" in
  release) suffix="release" ;;
  debug) suffix="debug" ;;
  *)
    echo "error: built APK did not verify as signed at all (apksigner status: $signed_status)" >&2
    rm -f "$manifest_tmp"
    exit 1
    ;;
esac

apk_dest="$DIST_DIR/ReLite-Home-v${VERSION}-${suffix}.apk"
cp "$built_apk" "$apk_dest"
shasum -a 256 "$apk_dest" | awk '{print $1}' > "${apk_dest}.sha256"

# Regenerate the manifest against the final, renamed artifact path.
python3 scripts/release_manifest.py "$VERSION" "$apk_dest" --out "$DIST_DIR/release-manifest.json"
rm -f "$manifest_tmp"

echo "Built and packaged:" >&2
echo "  $apk_dest" >&2
echo "  ${apk_dest}.sha256 ($(cat "${apk_dest}.sha256"))" >&2
echo "  $DIST_DIR/release-manifest.json" >&2
echo >&2
echo "Install with: adb install -r $apk_dest" >&2
