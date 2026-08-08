from __future__ import annotations

import subprocess
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[1]


def test_rmx5303_package_docs_are_up_to_date():
    """PACKAGES.md is generated, not hand-maintained — this catches the
    case where packages.yaml/protected-packages.yaml changed but nobody
    regenerated it (CI enforces the same check across all devices)."""
    device_dir = REPO_ROOT / "devices" / "realme" / "RMX5303"
    generated_path = device_dir / "PACKAGES.md"

    result = subprocess.run(
        [sys.executable, str(REPO_ROOT / "scripts" / "generate_package_docs.py"), str(device_dir)],
        capture_output=True,
        text=True,
        check=True,
        cwd=REPO_ROOT,
    )

    assert result.stdout == generated_path.read_text(), (
        "devices/realme/RMX5303/PACKAGES.md is stale — regenerate with:\n"
        "  python scripts/generate_package_docs.py devices/realme/RMX5303 "
        "> devices/realme/RMX5303/PACKAGES.md"
    )
