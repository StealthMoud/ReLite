from __future__ import annotations

import re
import tomllib
from pathlib import Path

from relite import __version__

REPO_ROOT = Path(__file__).resolve().parents[1]


def test_pyproject_version_matches_package_version():
    data = tomllib.loads((REPO_ROOT / "pyproject.toml").read_text())
    assert data["project"]["version"] == __version__


def test_android_version_name_matches_package_version():
    """Section 114: keep pyproject/relite.__version__ and the Android
    versionName in lockstep — CI catches a forgotten bump before it ships."""
    gradle = (REPO_ROOT / "android" / "relite-home" / "app" / "build.gradle.kts").read_text()
    match = re.search(r'versionName\s*=\s*"([^"]+)"', gradle)
    assert match is not None, "versionName not found in build.gradle.kts"
    assert match.group(1) == __version__


def test_changelog_has_an_entry_for_the_current_version():
    changelog = (REPO_ROOT / "CHANGELOG.md").read_text()
    assert f"[{__version__}]" in changelog, f"CHANGELOG.md has no entry for {__version__}"
