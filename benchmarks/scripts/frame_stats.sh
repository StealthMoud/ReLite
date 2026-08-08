#!/usr/bin/env bash
# Exploratory frame-timing capture around a scripted interaction.
# Usage: frame_stats.sh <package> [activity]
#
# Resets gfxinfo counters, waits for a manual or scripted interaction
# (default: a fixed pause — replace with `am start`/`input swipe` calls
# for a specific interaction you want to profile), then dumps framestats.
# This is a starting point for manual investigation, not yet wired into
# `relite benchmark` — see benchmarks/methodology.md.
set -euo pipefail

PACKAGE="${1:?usage: frame_stats.sh <package> [activity]}"
ACTIVITY="${2:-}"
INTERACTION_SECONDS="${INTERACTION_SECONDS:-5}"

if ! command -v adb >/dev/null 2>&1; then
  echo "error: adb not found on PATH" >&2
  exit 1
fi

echo "resetting gfxinfo counters for $PACKAGE" >&2
adb shell dumpsys gfxinfo "$PACKAGE" reset >/dev/null

if [ -n "$ACTIVITY" ]; then
  echo "starting $PACKAGE/$ACTIVITY" >&2
  adb shell am start -W "$PACKAGE/$ACTIVITY" >/dev/null
fi

echo "interact with the device now (${INTERACTION_SECONDS}s)..." >&2
sleep "$INTERACTION_SECONDS"

echo "== dumpsys gfxinfo $PACKAGE framestats ==" >&2
adb shell dumpsys gfxinfo "$PACKAGE" framestats
