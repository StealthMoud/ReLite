from __future__ import annotations

from pathlib import Path

import pytest
import yaml

from relite.profiles import VALID_PROFILE_NAMES, load_profiles

PROFILES_DIR = Path(__file__).resolve().parents[1] / "profiles"
REQUIRED_KEYS = {"name", "label", "description", "animation_scale", "package_policy"}


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


# --- relite.profiles loader (section 9 of the v0.2.0 plan) ---


def test_load_profiles_returns_all_three_profiles():
    profiles = load_profiles(PROFILES_DIR)
    assert set(profiles.keys()) == VALID_PROFILE_NAMES


def test_load_profiles_labels_are_not_value_judgements():
    profiles = load_profiles(PROFILES_DIR)
    assert profiles["safe"].label == "conservative"
    assert profiles["performance"].label == "recommended"
    assert profiles["maximum"].label == "aggressive / experimental"


def test_load_profiles_animation_scale_matches_yaml():
    profiles = load_profiles(PROFILES_DIR)
    assert profiles["safe"].animation_scale == "0.5"
    assert profiles["performance"].animation_scale == "0.5"
    assert profiles["maximum"].animation_scale == "0"


def test_load_profiles_is_cached_by_root(tmp_path: Path):
    # Two calls with the same root return equal (and, since it's cached,
    # identical) results without re-reading the files each time.
    assert load_profiles(PROFILES_DIR) is load_profiles(PROFILES_DIR)


def test_load_profiles_rejects_mismatched_name(tmp_path: Path):
    (tmp_path / "safe.yaml").write_text("name: not-safe\nlabel: x\nanimation_scale: '0.5'\n")
    (tmp_path / "performance.yaml").write_text("name: performance\nlabel: x\nanimation_scale: '0.5'\n")
    (tmp_path / "maximum.yaml").write_text("name: maximum\nlabel: x\nanimation_scale: '0'\n")
    with pytest.raises(ValueError, match="expected 'safe'"):
        load_profiles(tmp_path)


def test_load_profiles_rejects_invalid_animation_scale(tmp_path: Path):
    (tmp_path / "safe.yaml").write_text("name: safe\nlabel: conservative\nanimation_scale: 'fast'\n")
    (tmp_path / "performance.yaml").write_text("name: performance\nlabel: x\nanimation_scale: '0.5'\n")
    (tmp_path / "maximum.yaml").write_text("name: maximum\nlabel: x\nanimation_scale: '0'\n")
    with pytest.raises(ValueError, match="invalid 'animation_scale'"):
        load_profiles(tmp_path)


def test_load_profiles_rejects_missing_label(tmp_path: Path):
    (tmp_path / "safe.yaml").write_text("name: safe\nanimation_scale: '0.5'\n")
    (tmp_path / "performance.yaml").write_text("name: performance\nlabel: x\nanimation_scale: '0.5'\n")
    (tmp_path / "maximum.yaml").write_text("name: maximum\nlabel: x\nanimation_scale: '0'\n")
    with pytest.raises(ValueError, match="missing or invalid 'label'"):
        load_profiles(tmp_path)


def test_load_profiles_rejects_missing_file(tmp_path: Path):
    (tmp_path / "safe.yaml").write_text("name: safe\nlabel: conservative\nanimation_scale: '0.5'\n")
    with pytest.raises(ValueError, match="missing required profile file"):
        load_profiles(tmp_path)
