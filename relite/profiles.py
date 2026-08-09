"""Profile metadata: single source of truth for each profile's display
label, description, and animation scale (section 9 of the v0.2.0 plan).

Before this module existed, `profiles/*.yaml` already documented each
profile's intent and animation scale, but nothing actually read them —
`relite/cli.py` hardcoded its own label strings and `relite/tuning.py`
hardcoded its own animation-scale mapping, duplicating (and risking
drifting from) what the YAML already said. Per-package decisions still
live in each device's packages.yaml, never here.
"""

from __future__ import annotations

import re
from dataclasses import dataclass
from functools import lru_cache
from pathlib import Path
from typing import Any

import yaml

from relite.data_paths import profiles_root

VALID_PROFILE_NAMES = {"safe", "performance", "maximum"}

_ANIMATION_SCALE_RE = re.compile(r"^\d+(\.\d+)?$")


@dataclass(frozen=True)
class ProfileMeta:
    name: str
    label: str
    description: str
    animation_scale: str


def _validate(raw: dict[str, Any], expected_name: str) -> None:
    name = raw.get("name")
    if name != expected_name:
        raise ValueError(f"{expected_name}.yaml: 'name' is {name!r}, expected {expected_name!r}")
    if name not in VALID_PROFILE_NAMES:
        raise ValueError(f"{expected_name}.yaml: invalid profile name {name!r}")
    if not raw.get("label") or not isinstance(raw["label"], str):
        raise ValueError(f"{expected_name}.yaml: missing or invalid 'label'")
    scale = raw.get("animation_scale")
    if not isinstance(scale, str) or not _ANIMATION_SCALE_RE.match(scale):
        raise ValueError(f"{expected_name}.yaml: invalid 'animation_scale' {scale!r} (must be numeric)")


def _load_uncached(root: Path) -> dict[str, ProfileMeta]:
    profiles: dict[str, ProfileMeta] = {}
    for name in sorted(VALID_PROFILE_NAMES):
        path = root / f"{name}.yaml"
        if not path.exists():
            raise ValueError(f"missing required profile file: {path}")
        raw = yaml.safe_load(path.read_text()) or {}
        _validate(raw, name)
        profiles[name] = ProfileMeta(
            name=name,
            label=raw["label"],
            description=(raw.get("description") or "").strip(),
            animation_scale=raw["animation_scale"],
        )
    return profiles


@lru_cache(maxsize=8)
def _load_cached(root_str: str) -> dict[str, ProfileMeta]:
    return _load_uncached(Path(root_str))


def load_profiles(root: Path | None = None) -> dict[str, ProfileMeta]:
    """Load and validate `profiles/{safe,performance,maximum}.yaml` (or a
    custom root directory, mainly for tests). Cached per root since this
    is read on every CLI invocation that touches profile metadata."""
    return _load_cached(str(root or profiles_root()))
