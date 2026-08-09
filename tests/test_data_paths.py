from __future__ import annotations

import os
from pathlib import Path

import pytest

from relite.data_paths import devices_root, profiles_root


@pytest.fixture(autouse=True)
def _clean_env(monkeypatch):
    monkeypatch.delenv("RELITE_DATA_DIR", raising=False)


def test_profiles_root_prefers_env_override(tmp_path: Path, monkeypatch):
    (tmp_path / "profiles").mkdir()
    monkeypatch.setenv("RELITE_DATA_DIR", str(tmp_path))
    assert profiles_root() == tmp_path / "profiles"


def test_devices_root_prefers_env_override(tmp_path: Path, monkeypatch):
    (tmp_path / "devices").mkdir()
    monkeypatch.setenv("RELITE_DATA_DIR", str(tmp_path))
    assert devices_root() == tmp_path / "devices"


def _make_fake_checkout(tmp_path: Path) -> None:
    """Section 10 (v0.4.0): cwd/profiles is only trusted when cwd actually
    looks like the ReLite source checkout — a bare `profiles/` directory
    is no longer enough, since an installed `relite` run from an
    unrelated directory that happens to contain one must not silently
    treat it as policy data."""
    (tmp_path / "profiles").mkdir()
    (tmp_path / "relite").mkdir()
    (tmp_path / "relite" / "__init__.py").write_text('__version__ = "0.0.0"\n')
    (tmp_path / "pyproject.toml").write_text('[project]\nname = "relite"\nversion = "0.0.0"\n')


def test_profiles_root_prefers_cwd_checkout_over_packaged(tmp_path: Path, monkeypatch):
    _make_fake_checkout(tmp_path)
    monkeypatch.chdir(tmp_path)
    assert profiles_root().resolve() == (tmp_path / "profiles").resolve()


def test_profiles_root_ignores_cwd_profiles_dir_without_checkout_fingerprint(tmp_path: Path, monkeypatch):
    """A directory that merely happens to contain profiles/+devices/ (no
    relite/__init__.py + pyproject.toml naming relite) must fall back to
    the packaged resources, not be silently trusted as ReLite's own data."""
    (tmp_path / "profiles").mkdir()
    monkeypatch.chdir(tmp_path)
    root = profiles_root()
    assert "resources" in root.parts


def test_profiles_root_falls_back_to_packaged_resources_outside_checkout(tmp_path: Path, monkeypatch):
    monkeypatch.chdir(tmp_path)  # no ./profiles here
    root = profiles_root()
    assert root.name == "profiles"
    assert "resources" in root.parts
    # the packaged fallback must contain the real, importable data
    assert (root / "safe.yaml").exists()


def test_devices_root_falls_back_to_packaged_resources_outside_checkout(tmp_path: Path, monkeypatch):
    monkeypatch.chdir(tmp_path)
    root = devices_root()
    assert root.name == "devices"
    assert "resources" in root.parts
    assert os.path.isdir(root)


def test_data_dir_env_var_missing_directory_still_returns_the_path(tmp_path: Path, monkeypatch):
    """Resolution doesn't validate existence itself — callers (e.g.
    load_profiles) surface a clear error when the resolved path has no
    usable data, rather than this module silently falling through."""
    monkeypatch.setenv("RELITE_DATA_DIR", str(tmp_path / "does-not-exist"))
    assert profiles_root() == tmp_path / "does-not-exist" / "profiles"
