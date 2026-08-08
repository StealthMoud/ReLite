#!/usr/bin/env bash
# Host-observed boot-completion timing. See benchmarks/methodology.md —
# this is intentionally not millisecond-accurate and does not touch
# bootloader/fastboot state.
set -euo pipefail

RUNS="${1:-3}"
POLL_INTERVAL="${POLL_INTERVAL:-1}"
TIMEOUT="${TIMEOUT:-180}"

if ! command -v adb >/dev/null 2>&1; then
  echo "error: adb not found on PATH" >&2
  exit 1
fi

samples=()

for ((i = 1; i <= RUNS; i++)); do
  echo "== boot run $i/$RUNS ==" >&2

  echo "rebooting device..." >&2
  adb reboot

  echo "waiting for adb..." >&2
  adb wait-for-device

  start_epoch=$(date +%s)
  elapsed=0
  booted=""

  while [ "$elapsed" -lt "$TIMEOUT" ]; do
    if [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; then
      booted=1
      break
    fi
    sleep "$POLL_INTERVAL"
    elapsed=$(( $(date +%s) - start_epoch ))
  done

  if [ -z "$booted" ]; then
    echo "error: device did not report sys.boot_completed within ${TIMEOUT}s" >&2
    exit 1
  fi

  echo "boot completed in ${elapsed}s (host-observed)" >&2
  samples+=("$elapsed")
done

# Print a simple median/min/max summary (integer seconds).
python3 - "${samples[@]}" <<'EOF'
import statistics
import sys

samples = [int(x) for x in sys.argv[1:]]
print("samples_s:", samples)
print("median_s:", statistics.median(samples))
print("min_s:", min(samples))
print("max_s:", max(samples))
EOF
