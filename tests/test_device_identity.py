from __future__ import annotations

from pathlib import Path

from relite.device_identity import device_key, device_local_dir, migrate_legacy_layout


def test_device_key_is_stable_for_the_same_serial():
    assert device_key("RMX5303", "R3CN123ABCD") == device_key("RMX5303", "R3CN123ABCD")


def test_device_key_differs_for_different_serials_same_model():
    """Two physical units of the same model must never collide."""
    assert device_key("RMX5303", "SERIAL_A") != device_key("RMX5303", "SERIAL_B")


def test_device_key_never_contains_the_raw_serial():
    key = device_key("RMX5303", "R3CN123ABCDEFGH")
    assert "R3CN123ABCDEFGH" not in key


def test_device_key_is_filesystem_safe():
    key = device_key("realme C71 / RMX5303EEA", "abc123")
    assert "/" not in key
    assert " " not in key


def test_device_local_dir_isolates_two_devices(tmp_path: Path):
    dir_a = device_local_dir(tmp_path, "RMX5303", "SERIAL_A")
    dir_b = device_local_dir(tmp_path, "RMX5303", "SERIAL_B")
    assert dir_a != dir_b


def test_migrate_legacy_layout_moves_state_and_snapshots(tmp_path: Path):
    (tmp_path / "state.json").write_text('{"active_profile": "performance"}')
    (tmp_path / "actions.jsonl").write_text('{"package": "com.example.a"}\n')
    legacy_snapshots = tmp_path / "RMX5303" / "snapshots"
    legacy_snapshots.mkdir(parents=True)
    (legacy_snapshots / "stock.snapshot.json").write_text("{}")

    new_dir = migrate_legacy_layout(tmp_path, "RMX5303", "SERIAL_A")

    assert (new_dir / "state.json").read_text() == '{"active_profile": "performance"}'
    assert (new_dir / "actions.jsonl").exists()
    assert (new_dir / "snapshots" / "stock.snapshot.json").exists()
    assert not (tmp_path / "state.json").exists()
    assert not legacy_snapshots.exists()


def test_migrate_legacy_layout_is_a_noop_when_already_migrated(tmp_path: Path):
    new_dir = device_local_dir(tmp_path, "RMX5303", "SERIAL_A")
    new_dir.mkdir(parents=True)
    (new_dir / "state.json").write_text('{"active_profile": "safe"}')

    (tmp_path / "state.json").write_text('{"active_profile": "maximum"}')  # stale legacy file

    result = migrate_legacy_layout(tmp_path, "RMX5303", "SERIAL_A")

    assert result == new_dir
    assert (new_dir / "state.json").read_text() == '{"active_profile": "safe"}'


def test_migrate_legacy_layout_does_not_leak_a_second_devices_history(tmp_path: Path):
    """A second physical unit of the same model must not silently inherit
    the first device's state just because a legacy layout still exists."""
    (tmp_path / "state.json").write_text('{"active_profile": "performance"}')

    dir_a = migrate_legacy_layout(tmp_path, "RMX5303", "SERIAL_A")
    assert (dir_a / "state.json").exists()

    # Legacy state.json was already consumed by device A's migration —
    # device B correctly finds nothing left to migrate.
    dir_b = migrate_legacy_layout(tmp_path, "RMX5303", "SERIAL_B")
    assert not (dir_b / "state.json").exists()
    assert dir_a != dir_b


def test_migrate_legacy_layout_noop_when_nothing_to_migrate(tmp_path: Path):
    new_dir = migrate_legacy_layout(tmp_path, "RMX5303", "SERIAL_A")
    assert not new_dir.exists()
