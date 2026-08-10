#!/usr/bin/env bash
# Section 57-59 (v0.4.0): build ReLite Home, install it on whatever
# device/emulator adb currently sees, and run the androidTest
# instrumentation suite against it. Does not start an emulator itself —
# start one first (or connect a physical device) with USB/wireless
# debugging enabled, then run this script.
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/../android/relite-home"

if ! command -v adb >/dev/null 2>&1; then
  echo "error: adb not found on PATH" >&2
  exit 1
fi

device_count=$(adb devices | tail -n +2 | grep -c "device$" || true)
if [ "$device_count" -eq 0 ]; then
  echo "error: no device/emulator attached (adb devices -l shows none)." >&2
  echo "Start an emulator (e.g. \`emulator -avd <name>\`) or connect a device with USB debugging enabled, then re-run this script." >&2
  exit 1
fi

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

echo "Building debug + androidTest APKs..."
./gradlew assembleDebug assembleDebugAndroidTest --console=plain

echo "Running instrumentation tests on:"
adb devices -l | tail -n +2

./gradlew connectedDebugAndroidTest --console=plain
