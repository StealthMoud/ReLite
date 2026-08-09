"""Locates ReLite's machine-readable data (profiles/, devices/) — section
27-29 of the v0.3.0 plan.

Before this module existed, `relite/profiles.py` and `relite/cli.py` each
hardcoded a repository-relative `Path("profiles")` / `Path("devices")`,
which only works from inside a source checkout. A wheel installs only the
`relite` Python package; running `relite` from an arbitrary directory
after `pip install` had no built-in data to fall back to at all — a
release blocker fixed here.

Resolution order, first match wins:
1. `RELITE_DATA_DIR` environment variable (or `--data-dir`, which sets it)
   — an explicit override, e.g. for contributors testing local edits.
2. `./profiles` / `./devices` in the current working directory — a
   source checkout being run from its own root, the common dev case.
3. `relite/resources/{profiles,devices}` bundled inside the installed
   package (`importlib.resources`) — what a `pip install`'d wheel has.

There is exactly one *hand-maintained* copy of this data (repository-root
`profiles/`, `devices/`); `relite/resources/` is a synced build artifact
(`scripts/sync_resources.py`), never edited directly — see that script's
docstring and the CI freshness check that enforces it.
"""

from __future__ import annotations

import os
from importlib import resources
from pathlib import Path


def _resolve(dirname: str) -> Path:
    override = os.environ.get("RELITE_DATA_DIR")
    if override:
        return Path(override) / dirname

    cwd_candidate = Path(dirname)
    if cwd_candidate.is_dir():
        return cwd_candidate

    packaged = resources.files("relite.resources") / dirname
    return Path(str(packaged))


def profiles_root() -> Path:
    return _resolve("profiles")


def devices_root() -> Path:
    return _resolve("devices")
