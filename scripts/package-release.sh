#!/usr/bin/env bash
# Package the ReLite Home APK into dist/, with a SHA-256 checksum, for a
# GitHub Release. Signs with a real release key only if credentials are
# actually present (android/relite-home/keystore.properties, gitignored,
# or RELITE_RELEASE_STORE_FILE/STORE_PASSWORD/KEY_ALIAS/KEY_PASSWORD env
# vars — see android/relite-home/app/build.gradle.kts and
# docs/releasing.md). Otherwise falls back to the debug-signed build, and
# the output filename says so honestly — never call a debug-signed APK a
# release APK.
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."

VERSION="${1:?usage: package-release.sh <version, e.g. 0.2.0>}"
DIST_DIR="dist"
ANDROID_DIR="android/relite-home"

mkdir -p "$DIST_DIR"

if [ -f "$ANDROID_DIR/keystore.properties" ] || [ -n "${RELITE_RELEASE_STORE_FILE:-}" ]; then
  echo "Release-signing credentials found — building a signed release APK." >&2
  (cd "$ANDROID_DIR" && ./gradlew assembleRelease --console=plain)
  apk_src="$ANDROID_DIR/app/build/outputs/apk/release/app-release.apk"
  signed_status="release"
else
  echo "No release-signing credentials found — packaging the debug-signed build." >&2
  echo "See docs/releasing.md to set up real release signing." >&2
  (cd "$ANDROID_DIR" && ./gradlew assembleDebug --console=plain)
  apk_src="$ANDROID_DIR/app/build/outputs/apk/debug/app-debug.apk"
  signed_status="debug"
fi

apk_dest="$DIST_DIR/ReLite-Home-v${VERSION}-${signed_status}.apk"
cp "$apk_src" "$apk_dest"
shasum -a 256 "$apk_dest" | awk '{print $1}' > "${apk_dest}.sha256"

echo "Built and packaged:" >&2
echo "  $apk_dest" >&2
echo "  ${apk_dest}.sha256 ($(cat "${apk_dest}.sha256"))" >&2
echo >&2
echo "Install with: adb install -r $apk_dest" >&2
