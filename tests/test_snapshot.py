from __future__ import annotations

from pathlib import Path

import pytest

from relite.classifier import ClassificationDatabase, PackageClassification
from relite.device import DeviceProfile
from relite.package_state import PackageState
from relite.packages import PackageInfo
from relite.snapshot import Snapshot


def _device(fingerprint: str = "fp-1") -> DeviceProfile:
    return DeviceProfile(
        serial="EMULATOR123",
        props={"ro.product.model": "RMX5303", "ro.build.fingerprint": fingerprint},
    )


def _db() -> ClassificationDatabase:
    return ClassificationDatabase(
        entries={
            "com.example.ads": PackageClassification(package="com.example.ads"),
            "com.example.optional": PackageClassification(package="com.example.optional"),
        }
    )


def test_schema_v2_round_trips_managed_package_states(tmp_path: Path):
    snap = Snapshot(
        schema=2,
        name="stock",
        created_at="2026-08-10T00:00:00Z",
        device=_device(),
        packages={},
        settings={},
        managed_package_states={
            "com.example.ads": "present_enabled",
            "com.example.optional": "absent_for_user",
        },
    )
    path = tmp_path / "stock.snapshot.json"
    snap.save(path)

    loaded = Snapshot.load(path)
    assert loaded.schema == 2
    assert loaded.managed_package_states["com.example.ads"] == "present_enabled"


def test_baseline_states_prefers_recorded_managed_states():
    snap = Snapshot(
        schema=2,
        name="stock",
        created_at="2026-08-10T00:00:00Z",
        device=_device(),
        packages={"com.example.ads": PackageInfo(name="com.example.ads", disabled=True)},
        settings={},
        managed_package_states={"com.example.ads": "present_enabled"},
    )
    # managed_package_states says enabled even though `packages` (the raw
    # inventory) says disabled — recorded state wins for packages present there.
    states = snap.baseline_states(_db())
    assert states["com.example.ads"] == PackageState.PRESENT_ENABLED


def test_baseline_states_falls_back_to_inventory_for_v1_snapshot():
    """A schema-1 snapshot has no managed_package_states at all — every
    classified package must still get a usable baseline, derived from
    the full package inventory (the in-memory v1->v2 migration)."""
    snap = Snapshot(
        schema=1,
        name="stock",
        created_at="2026-08-10T00:00:00Z",
        device=_device(),
        packages={
            "com.example.ads": PackageInfo(name="com.example.ads", disabled=False),
            "com.example.optional": PackageInfo(name="com.example.optional", disabled=True),
        },
        settings={},
    )
    states = snap.baseline_states(_db())
    assert states["com.example.ads"] == PackageState.PRESENT_ENABLED
    assert states["com.example.optional"] == PackageState.PRESENT_DISABLED


def test_baseline_states_absent_package_falls_back_to_absent_for_user():
    snap = Snapshot(
        schema=1, name="stock", created_at="2026-08-10T00:00:00Z", device=_device(), packages={}, settings={},
    )
    states = snap.baseline_states(_db())
    assert states["com.example.ads"] == PackageState.ABSENT_FOR_USER


def test_baseline_states_uses_inventory_for_package_missing_from_managed_states():
    """A device's classification database can grow between when a
    snapshot was taken and when it's used — a schema-2 snapshot missing
    an entry for a newly-classified package should still fall back to
    the inventory rather than defaulting to a fixed guess."""
    snap = Snapshot(
        schema=2,
        name="stock",
        created_at="2026-08-10T00:00:00Z",
        device=_device(),
        packages={"com.example.optional": PackageInfo(name="com.example.optional", disabled=True)},
        settings={},
        managed_package_states={},  # taken before com.example.optional was classified
    )
    states = snap.baseline_states(_db())
    assert states["com.example.optional"] == PackageState.PRESENT_DISABLED


def test_load_rejects_unsupported_schema(tmp_path: Path):
    path = tmp_path / "bad.snapshot.json"
    path.write_text(
        '{"schema": 999, "name": "x", "created_at": "2026-08-10T00:00:00Z", '
        '"device": {"serial": "S", "props": {}}}'
    )
    with pytest.raises(ValueError, match="unsupported snapshot schema"):
        Snapshot.load(path)


def test_v1_snapshot_file_still_loads(tmp_path: Path):
    path = tmp_path / "v1.snapshot.json"
    path.write_text(
        '{"schema": 1, "name": "stock", "created_at": "2026-08-08T00:00:00Z", '
        '"device": {"serial": "EMULATOR123", "props": {"ro.product.model": "RMX5303"}}, '
        '"packages": {"com.example.ads": {"name": "com.example.ads", "disabled": false}}, '
        '"settings": {}}'
    )
    loaded = Snapshot.load(path)
    assert loaded.schema == 1
    assert loaded.managed_package_states == {}
    assert loaded.packages["com.example.ads"].name == "com.example.ads"
