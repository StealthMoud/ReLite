#!/usr/bin/env bash
# Print the path to the newest installed `apksigner`, or exit 1 if none is
# found. Shared by scripts/package-release.sh.
set -euo pipefail

if command -v apksigner >/dev/null 2>&1; then
  command -v apksigner
  exit 0
fi

sdk_root="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
build_tools_dir="$sdk_root/build-tools"

if [ ! -d "$build_tools_dir" ]; then
  echo "apksigner not found (checked PATH and $build_tools_dir)" >&2
  exit 1
fi

# Newest version directory (lexical sort works for X.Y.Z build-tools names).
newest=$(find "$build_tools_dir" -maxdepth 1 -mindepth 1 -type d | sort -V | tail -n 1)
candidate="$newest/apksigner"
if [ -x "$candidate" ]; then
  echo "$candidate"
  exit 0
fi

echo "apksigner not found under $build_tools_dir" >&2
exit 1
