#!/usr/bin/env bash
# Set up a local Python virtual environment for the relite CLI.
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."

PYTHON="${PYTHON:-python3}"

if ! command -v "$PYTHON" >/dev/null 2>&1; then
  echo "error: $PYTHON not found. Install Python 3.11+ and re-run." >&2
  exit 1
fi

version=$("$PYTHON" -c 'import sys; print("%d.%d" % sys.version_info[:2])')
major=${version%%.*}
minor=${version##*.}
if [ "$major" -lt 3 ] || { [ "$major" -eq 3 ] && [ "$minor" -lt 11 ]; }; then
  echo "error: Python 3.11+ required, found $version" >&2
  exit 1
fi

if [ ! -d .venv ]; then
  echo "Creating virtual environment in .venv..."
  "$PYTHON" -m venv .venv
fi

# shellcheck disable=SC1091
source .venv/bin/activate

echo "Installing relite in editable mode with dev dependencies..."
pip install -q --upgrade pip
pip install -q -e ".[dev]"

echo
echo "Done. Activate the environment with:"
echo "  source .venv/bin/activate"
echo
echo "Then try:"
echo "  relite doctor"
