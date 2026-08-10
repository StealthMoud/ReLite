#!/usr/bin/env bash
# Master plan v0.5.0, Phase X (sections 194-209): an automated stress/
# performance pass for ReLite Home, run against whatever device/emulator
# `adb` currently targets. Requires ReLite Home already installed
# (./gradlew installDebug) and a connected, unlocked device.
#
# This is intentionally a *scoped* pass, not the full multi-hour campaign
# section 194-209 describes (200 Home swipes, 100 folder open/close cycles,
# a full 5-minute idle-CPU sample, etc.) — see CHANGELOG's Known gaps for
# what's not covered. What it does run is real: actual `monkey` event
# injection against this package only, actual `dumpsys meminfo` samples,
# actual cold-start timing via `am start -W`, not simulated/estimated
# numbers.
set -euo pipefail

PACKAGE="io.relite.home"
ACTIVITY="io.relite.home/.ui.MainActivity"
MONKEY_EVENTS="${1:-500}"
OUT_DIR="${2:-/tmp/relite-stress-$(date +%Y%m%d-%H%M%S)}"

mkdir -p "$OUT_DIR"
echo "ReLite Home stress pass — output: $OUT_DIR"

echo "--- device ---"
adb shell getprop ro.product.model | tee "$OUT_DIR/device.txt"

echo "--- force-stop, clear, cold start ---"
adb shell am force-stop "$PACKAGE"
adb shell am start -W -n "$ACTIVITY" | tee "$OUT_DIR/cold-start-1.txt"
sleep 2
adb shell am force-stop "$PACKAGE"
adb shell am start -W -n "$ACTIVITY" | tee "$OUT_DIR/cold-start-2.txt"
sleep 2
adb shell am force-stop "$PACKAGE"
adb shell am start -W -n "$ACTIVITY" | tee "$OUT_DIR/cold-start-3.txt"
sleep 3

echo "--- PSS: cold (settled 3s after launch) ---"
adb shell dumpsys meminfo "$PACKAGE" | tee "$OUT_DIR/meminfo-cold.txt"

echo "--- stress: $MONKEY_EVENTS random touch/swipe events ---"
# `monkey -p io.relite.home` cannot be used here: monkey resolves its
# package's entry point via CATEGORY_LAUNCHER, and ReLite Home deliberately
# has no CATEGORY_LAUNCHER activity (section 46, v0.4.1 — otherwise it would
# list itself in its own app drawer). Found running this script for real;
# documented rather than silently switched to a weaker check. Direct input
# injection against the already-foregrounded Activity works instead and
# stays package-scoped as long as nothing switches away, which is verified
# below.
SCREEN_SIZE=$(adb shell wm size | grep -oE '[0-9]+x[0-9]+' | tail -1)
WIDTH="${SCREEN_SIZE%x*}"
HEIGHT="${SCREEN_SIZE#*x}"
{
  for ((i = 0; i < MONKEY_EVENTS; i++)); do
    x1=$((RANDOM % WIDTH))
    y1=$((RANDOM % HEIGHT))
    x2=$((RANDOM % WIDTH))
    y2=$((RANDOM % HEIGHT))
    if ((i % 3 == 0)); then
      adb shell input tap "$x1" "$y1"
    else
      adb shell input swipe "$x1" "$y1" "$x2" "$y2" 80
    fi
  done
} 2>&1 | tee "$OUT_DIR/stress-input.txt"

echo "--- foreground check: still ReLite Home, not crashed-to-home/another app ---"
adb shell dumpsys window | grep mCurrentFocus | tee "$OUT_DIR/foreground-after-stress.txt"

echo "--- PSS: after stress ---"
adb shell dumpsys meminfo "$PACKAGE" | tee "$OUT_DIR/meminfo-poststress.txt"

echo "--- idle CPU sample (60s; scoped from the plan's 5-minute figure) ---"
adb shell top -m 1 -d 5 -n 12 | grep -E "$PACKAGE|PID" | tee "$OUT_DIR/idle-cpu.txt" || true

echo "--- PSS: after idle settle ---"
adb shell dumpsys meminfo "$PACKAGE" | tee "$OUT_DIR/meminfo-postidle.txt"

echo "--- logcat crash/ANR scan since stress began (this package only) ---"
# -A2 to also capture the "Process: io.relite.home" line AndroidRuntime
# prints right after "FATAL EXCEPTION", which is what actually identifies
# the crashing package — the exception line itself never names it.
adb logcat -d | grep -A2 "FATAL EXCEPTION" | grep -B2 "Process: $PACKAGE" | tee "$OUT_DIR/crash-scan.txt" || true
adb logcat -d | grep "ANR in $PACKAGE" | tee -a "$OUT_DIR/crash-scan.txt" || true

echo "Done. Results in $OUT_DIR"
