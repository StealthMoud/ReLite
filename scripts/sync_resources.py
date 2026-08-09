#!/usr/bin/env python3
"""Sync the hand-maintained repo-root `profiles/` and `devices/` data into
`relite/resources/`, the copy that actually gets packaged into a wheel
(section 27-28 of the v0.3.0 plan).

This is the *only* place `relite/resources/` is written. Never edit files
under `relite/resources/` directly — edit `profiles/`/`devices/` and
re-run this script. CI's "generated resources are in sync" step (see
.github/workflows/ci.yml) fails if they drift, the same pattern already
used for `scripts/generate_package_docs.py`.
"""

from __future__ import annotations

import shutil
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
SOURCE_DIRS = ("profiles", "devices")
RESOURCES_ROOT = REPO_ROOT / "relite" / "resources"


def sync() -> None:
    for name in SOURCE_DIRS:
        source = REPO_ROOT / name
        dest = RESOURCES_ROOT / name
        if dest.exists():
            shutil.rmtree(dest)
        shutil.copytree(source, dest)


def main() -> int:
    sync()
    print(f"Synced {', '.join(SOURCE_DIRS)} into {RESOURCES_ROOT}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
