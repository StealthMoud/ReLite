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
2. `./profiles` / `./devices` in the current working directory, but ONLY
   when the current directory is actually a ReLite source checkout (see
   `_looks_like_relite_checkout`) — section 10 of the v0.4.0 plan. An
   installed `relite` run from an arbitrary directory that happens to
   contain unrelated `profiles/`/`devices/` folders must never silently
   treat them as ReLite policy data; before this check, cwd alone decided.
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


def _looks_like_relite_checkout(cwd: Path) -> bool:
    """A conservative, ReLite-specific fingerprint for "cwd is this
    project's own source checkout" — not just "cwd happens to contain a
    profiles/ and a devices/ directory", which is true of any directory
    an unrelated tool might have created those names in.

    Checks for `relite/__init__.py` declaring `__version__` (the actual
    Python package this data belongs to) alongside `pyproject.toml`
    naming this project — both must be present, not just one, since
    either alone is a plausible coincidence for a generic Python repo.
    """
    init_py = cwd / "relite" / "__init__.py"
    pyproject = cwd / "pyproject.toml"
    if not (init_py.is_file() and pyproject.is_file()):
        return False
    try:
        return "__version__" in init_py.read_text() and 'name = "relite"' in pyproject.read_text()
    except OSError:
        return False


def _resolve(dirname: str) -> Path:
    override = os.environ.get("RELITE_DATA_DIR")
    if override:
        return Path(override) / dirname

    cwd = Path.cwd()
    cwd_candidate = cwd / dirname
    if cwd_candidate.is_dir() and _looks_like_relite_checkout(cwd):
        return cwd_candidate

    packaged = resources.files("relite.resources") / dirname
    return Path(str(packaged))


def profiles_root() -> Path:
    return _resolve("profiles")


def devices_root() -> Path:
    return _resolve("devices")
