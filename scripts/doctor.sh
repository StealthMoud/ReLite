#!/usr/bin/env bash
# Environment sanity check beyond what `relite doctor` covers: confirms
# the toolchains needed to develop ReLite itself (not just run it) are
# present. Read-only — makes no changes.
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."

ok=0
warn=0

check() {
  local label="$1"
  shift
  if command -v "$1" >/dev/null 2>&1; then
    echo "  [ok]   $label ($(command -v "$1"))"
    ok=$((ok + 1))
  else
    echo "  [warn] $label not found on PATH"
    warn=$((warn + 1))
  fi
}

# Like check(), but missing the tool is informational only — doesn't
# affect the exit code. Used for optional, CI-only tooling.
check_optional() {
  local label="$1"
  shift
  if command -v "$1" >/dev/null 2>&1; then
    echo "  [ok]   $label ($(command -v "$1"))"
  else
    echo "  [info] $label not found on PATH (optional locally)"
  fi
}

echo "== ReLite contributor environment =="
echo
echo "Python CLI:"
check "python3 (3.11+)" python3
check "adb" adb
if [ -x .venv/bin/relite ]; then
  echo "  [ok]   relite installed in .venv (run ./scripts/bootstrap.sh if missing)"
  ok=$((ok + 1))
else
  echo "  [warn] relite not yet installed — run ./scripts/bootstrap.sh"
  warn=$((warn + 1))
fi

echo
echo "ReLite Home (Android):"
check "java" java
check "adb" adb
if [ -f android/relite-home/local.properties ]; then
  echo "  [ok]   android/relite-home/local.properties present"
  ok=$((ok + 1))
else
  echo "  [warn] android/relite-home/local.properties missing — set sdk.dir or open in Android Studio"
  warn=$((warn + 1))
fi

echo
echo "Repo tooling:"
check "git" git
check_optional "shellcheck (used by CI)" shellcheck

echo
echo "$ok check(s) ok, $warn warning(s)."
if [ "$warn" -gt 0 ]; then
  exit 1
fi
