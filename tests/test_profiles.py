from __future__ import annotations

from pathlib import Path

import pytest
import yaml

PROFILES_DIR = Path(__file__).resolve().parents[1] / "profiles"
REQUIRED_KEYS = {"name", "description", "animation_scale", "package_policy"}


@pytest.mark.parametrize("filename", ["safe.yaml", "performance.yaml", "maximum.yaml"])
def test_profile_files_are_valid_and_complete(filename: str):
    data = yaml.safe_load((PROFILES_DIR / filename).read_text())
    assert REQUIRED_KEYS.issubset(data.keys())
    assert data["name"] == filename.removesuffix(".yaml")
    assert data["animation_scale"] in {"0", "0.5", "1.0"}


def test_maximum_profile_disables_animations_entirely():
    data = yaml.safe_load((PROFILES_DIR / "maximum.yaml").read_text())
    assert data["animation_scale"] == "0"


def test_malformed_profile_yaml_fails_validation(tmp_path: Path):
    bad = tmp_path / "broken.yaml"
    bad.write_text("name: broken\n  bad_indent: [1, 2\n")
    with pytest.raises(yaml.YAMLError):
        yaml.safe_load(bad.read_text())


def test_profile_missing_required_key_is_detected(tmp_path: Path):
    incomplete = tmp_path / "incomplete.yaml"
    incomplete.write_text("name: incomplete\n")
    data = yaml.safe_load(incomplete.read_text())
    assert not REQUIRED_KEYS.issubset(data.keys())
