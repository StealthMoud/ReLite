#!/usr/bin/env bash
# Build ReLite Home's debug APK and run its unit tests.
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/../android/relite-home"

if [ ! -f local.properties ]; then
  if [ -n "${ANDROID_HOME:-}" ]; then
    echo "sdk.dir=$ANDROID_HOME" > local.properties
  elif [ -n "${ANDROID_SDK_ROOT:-}" ]; then
    echo "sdk.dir=$ANDROID_SDK_ROOT" > local.properties
  else
    echo "error: set ANDROID_HOME (or ANDROID_SDK_ROOT), or create local.properties yourself" >&2
    exit 1
  fi
fi

echo "Running unit tests..."
./gradlew testDebugUnitTest --console=plain

echo "Building debug APK..."
./gradlew assembleDebug --console=plain

apk=$(find app/build/outputs/apk/debug -name "*.apk" | head -1)
echo
echo "Built: $apk"
echo "Install with: adb install -r \"$apk\""
