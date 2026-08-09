from __future__ import annotations

from pathlib import Path

import pytest
import yaml

from relite.device_metadata import load_device_metadata


def _write(path: Path, data: dict) -> Path:
    path.write_text(yaml.safe_dump(data))
    return path


def test_load_device_metadata_parses_valid_file(tmp_path: Path):
    path = _write(
        tmp_path / "device.yaml",
        {
            "model": "RMX5303",
            "support_status": "validated-against-real-device",
            "benchmark_targets": [{"label": "settings", "package": "com.android.settings", "activity": ".Settings"}],
            "pss_targets": [{"label": "systemui", "package": "com.android.systemui"}],
        },
    )
    metadata = load_device_metadata(path)
    assert metadata.model == "RMX5303"
    assert metadata.benchmark_targets[0].activity == ".Settings"
    assert metadata.pss_targets[0].activity is None


def test_load_device_metadata_missing_file_raises(tmp_path: Path):
    with pytest.raises(FileNotFoundError):
        load_device_metadata(tmp_path / "does-not-exist.yaml")


def test_load_device_metadata_requires_model(tmp_path: Path):
    path = _write(tmp_path / "device.yaml", {"support_status": "unvalidated"})
    with pytest.raises(ValueError, match="model"):
        load_device_metadata(path)


def test_load_device_metadata_rejects_invalid_support_status(tmp_path: Path):
    path = _write(tmp_path / "device.yaml", {"model": "X", "support_status": "definitely-works-trust-me"})
    with pytest.raises(ValueError, match="support_status"):
        load_device_metadata(path)


def test_load_device_metadata_benchmark_target_requires_activity(tmp_path: Path):
    path = _write(
        tmp_path / "device.yaml",
        {
            "model": "X",
            "support_status": "unvalidated",
            "benchmark_targets": [{"label": "settings", "package": "com.android.settings"}],
        },
    )
    with pytest.raises(ValueError, match="activity"):
        load_device_metadata(path)


def test_load_device_metadata_pss_target_activity_optional(tmp_path: Path):
    path = _write(
        tmp_path / "device.yaml",
        {"model": "X", "support_status": "unvalidated", "pss_targets": [{"label": "sysui", "package": "com.android.systemui"}]},
    )
    metadata = load_device_metadata(path)
    assert metadata.pss_targets[0].activity is None


def test_load_device_metadata_rejects_malformed_target_package(tmp_path: Path):
    path = _write(
        tmp_path / "device.yaml",
        {
            "model": "X",
            "support_status": "unvalidated",
            "benchmark_targets": [
                {"label": "evil", "package": "com.example; rm -rf /", "activity": ".Main"}
            ],
        },
    )
    with pytest.raises(ValueError):
        load_device_metadata(path)


def test_real_rmx5303_device_yaml_loads_and_validates():
    device_dir = Path(__file__).resolve().parents[1] / "devices" / "realme" / "RMX5303"
    metadata = load_device_metadata(device_dir / "device.yaml")
    assert metadata.model == "RMX5303"
    assert len(metadata.benchmark_targets) == 2
    assert len(metadata.pss_targets) == 3
