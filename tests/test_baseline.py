from __future__ import annotations

from pathlib import Path

from relite.baseline import OwnershipStatus, check_ownership, validate_baseline
from relite.device import DeviceProfile
from relite.snapshot import Snapshot


def _device(serial: str = "EMULATOR123", model: str = "RMX5303", fingerprint: str = "fp-1") -> DeviceProfile:
    return DeviceProfile(
        serial=serial, props={"ro.product.model": model, "ro.build.fingerprint": fingerprint}
    )


def _snapshot(device: DeviceProfile) -> Snapshot:
    return Snapshot(schema=2, name="stock", created_at="2026-08-10T00:00:00Z", device=device, packages={}, settings={})


def test_check_ownership_match():
    device = _device()
    snap = _snapshot(device)
    assert check_ownership(snap, device) == OwnershipStatus.MATCH


def test_check_ownership_device_mismatch_different_serial():
    snap = _snapshot(_device(serial="SERIAL_A"))
    current = _device(serial="SERIAL_B")
    assert check_ownership(snap, current) == OwnershipStatus.DEVICE_MISMATCH


def test_check_ownership_device_mismatch_different_model():
    snap = _snapshot(_device(model="RMX5303"))
    current = _device(model="OtherModel")
    assert check_ownership(snap, current) == OwnershipStatus.DEVICE_MISMATCH


def test_check_ownership_firmware_different():
    snap = _snapshot(_device(fingerprint="fp-old"))
    current = _device(fingerprint="fp-new")
    assert check_ownership(snap, current) == OwnershipStatus.FIRMWARE_DIFFERENT


def test_check_ownership_prefers_device_key_over_raw_serial(tmp_path: Path):
    """A sanitized snapshot has a redacted device.serial but an intact
    device_key — ownership must still resolve correctly using it."""
    device = _device()
    snap = Snapshot(
        schema=2, name="stock", created_at="2026-08-10T00:00:00Z",
        device=DeviceProfile(serial="[REDACTED]", props=device.props),
        packages={}, settings={}, device_key="RMX5303-abc123",
    )
    assert check_ownership(snap, device, current_device_key="RMX5303-abc123") == OwnershipStatus.MATCH


def test_check_ownership_device_key_mismatch_even_with_matching_serial(tmp_path: Path):
    device = _device()
    snap = Snapshot(
        schema=2, name="stock", created_at="2026-08-10T00:00:00Z",
        device=device, packages={}, settings={}, device_key="RMX5303-old-salt-digest",
    )
    result = check_ownership(snap, device, current_device_key="RMX5303-new-salt-digest")
    assert result == OwnershipStatus.DEVICE_MISMATCH


def test_check_ownership_falls_back_to_serial_when_no_device_key(tmp_path: Path):
    """A snapshot predating device_key (or a caller not supplying
    current_device_key) still gets a best-effort raw-serial comparison."""
    device = _device()
    snap = _snapshot(device)  # no device_key set
    assert check_ownership(snap, device) == OwnershipStatus.MATCH


def test_validate_baseline_missing_file(tmp_path: Path):
    result = validate_baseline(tmp_path / "does-not-exist.json", _device())
    assert result.status == "missing"
    assert not result.usable


def test_validate_baseline_corrupt_file(tmp_path: Path):
    path = tmp_path / "bad.json"
    path.write_text("{not valid json")
    result = validate_baseline(path, _device())
    assert result.status == "corrupt"
    assert not result.usable


def test_validate_baseline_unsupported_schema(tmp_path: Path):
    path = tmp_path / "bad.json"
    path.write_text(
        '{"schema": 999, "name": "x", "created_at": "2026-08-10T00:00:00Z", '
        '"device": {"serial": "EMULATOR123", "props": {}}}'
    )
    result = validate_baseline(path, _device())
    assert result.status == "unsupported_schema"
    assert not result.usable


def test_validate_baseline_valid(tmp_path: Path):
    device = _device()
    snap = _snapshot(device)
    path = tmp_path / "stock.snapshot.json"
    snap.save(path)

    result = validate_baseline(path, device)

    assert result.status == "valid"
    assert result.usable
    assert result.ownership == OwnershipStatus.MATCH


def test_validate_baseline_device_mismatch_not_usable(tmp_path: Path):
    snap = _snapshot(_device(serial="SERIAL_A"))
    path = tmp_path / "stock.snapshot.json"
    snap.save(path)

    result = validate_baseline(path, _device(serial="SERIAL_B"))

    assert result.status == "ownership_error"
    assert result.ownership == OwnershipStatus.DEVICE_MISMATCH
    assert not result.usable


def test_validate_baseline_firmware_drift_not_usable_but_snapshot_returned(tmp_path: Path):
    snap = _snapshot(_device(fingerprint="fp-old"))
    path = tmp_path / "stock.snapshot.json"
    snap.save(path)

    result = validate_baseline(path, _device(fingerprint="fp-new"))

    assert result.status == "ownership_error"
    assert result.ownership == OwnershipStatus.FIRMWARE_DIFFERENT
    assert not result.usable
    assert result.snapshot is not None  # still returned for reporting, just not "usable"
