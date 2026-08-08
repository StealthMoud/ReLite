#!/usr/bin/env bash
# Package the ReLite Home APK and CLI source distribution into dist/,
# with SHA-256 checksums, for a GitHub Release. Does not sign with a
# production key — see docs/releasing.md for why (no release-signing
# infrastructure exists in this repo, and a throwaway keystore committed
# here would be misleading, not reassuring).
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."

VERSION="${1:?usage: package-release.sh <version, e.g. 0.1.0>}"
DIST_DIR="dist"
APK_SRC="android/relite-home/app/build/outputs/apk/debug/app-debug.apk"

mkdir -p "$DIST_DIR"

if [ ! -f "$APK_SRC" ]; then
  echo "Building ReLite Home debug APK..." >&2
  (cd android/relite-home && ./gradlew assembleDebug --console=plain)
fi

apk_dest="$DIST_DIR/ReLite-Home-v${VERSION}-debug.apk"
cp "$APK_SRC" "$apk_dest"
shasum -a 256 "$apk_dest" | awk '{print $1}' > "${apk_dest}.sha256"

echo "Built and packaged:" >&2
echo "  $apk_dest" >&2
echo "  ${apk_dest}.sha256 ($(cat "${apk_dest}.sha256"))" >&2
echo >&2
echo "Install with: adb install -r $apk_dest" >&2
