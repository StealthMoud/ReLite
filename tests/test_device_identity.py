from __future__ import annotations

from pathlib import Path

from relite.device_identity import (
    _legacy_v02_device_key,
    device_key,
    device_local_dir,
    migrate_legacy_layout,
)


def test_device_key_is_stable_for_the_same_serial(tmp_path: Path):
    assert device_key(tmp_path, "RMX5303", "R3CN123ABCD") == device_key(tmp_path, "RMX5303", "R3CN123ABCD")


def test_device_key_differs_for_different_serials_same_model(tmp_path: Path):
    """Two physical units of the same model must never collide."""
    assert device_key(tmp_path, "RMX5303", "SERIAL_A") != device_key(tmp_path, "RMX5303", "SERIAL_B")


def test_device_key_never_contains_the_raw_serial(tmp_path: Path):
    key = device_key(tmp_path, "RMX5303", "R3CN123ABCDEFGH")
    assert "R3CN123ABCDEFGH" not in key


def test_device_key_is_filesystem_safe(tmp_path: Path):
    key = device_key(tmp_path, "realme C71 / RMX5303EEA", "abc123")
    assert "/" not in key
    assert " " not in key


def test_device_key_differs_across_roots_with_different_salts(tmp_path: Path):
    """The salt is per-root (per ReLite install) — the same serial
    produces a different key under a different salt, so the key alone
    (without the salt file) can't be used to recompute or correlate
    which physical device a directory belongs to across installs."""
    root_a = tmp_path / "install-a"
    root_b = tmp_path / "install-b"
    assert device_key(root_a, "RMX5303", "SAME_SERIAL") != device_key(root_b, "RMX5303", "SAME_SERIAL")


def test_device_key_is_pseudonymous_not_a_bare_unsalted_hash(tmp_path: Path):
    """A bare sha256(serial)[:8] is guessable/brute-forceable offline
    given how short and structured real ADB serials tend to be — the key
    must depend on a salt that isn't derivable from the key itself."""
    salted = device_key(tmp_path, "RMX5303", "SERIAL_A")
    unsalted_legacy = _legacy_v02_device_key("RMX5303", "SERIAL_A")
    assert salted != unsalted_legacy


def test_device_local_dir_isolates_two_devices(tmp_path: Path):
    dir_a = device_local_dir(tmp_path, "RMX5303", "SERIAL_A")
    dir_b = device_local_dir(tmp_path, "RMX5303", "SERIAL_B")
    assert dir_a != dir_b


def test_migrate_legacy_layout_relocates_v02_directory_for_connected_device(tmp_path: Path):
    """Section 19: a v0.2.0 directory CAN be safely identified for the
    device currently connected, since its old key is deterministically
    recomputable right now — it should be moved, not quarantined."""
    old_key = _legacy_v02_device_key("RMX5303", "SERIAL_A")
    old_dir = tmp_path / old_key
    old_dir.mkdir(parents=True)
    (old_dir / "state.json").write_text('{"active_profile": "performance"}')

    new_dir = migrate_legacy_layout(tmp_path, "RMX5303", "SERIAL_A")

    assert new_dir != old_dir
    assert (new_dir / "state.json").read_text() == '{"active_profile": "performance"}'
    assert not old_dir.exists()


def test_migrate_legacy_layout_quarantines_ambiguous_pre_v02_state(tmp_path: Path):
    """A pre-v0.2.0 flat layout is ambiguous — it could belong to this
    device or a different unit of the same model. It must never be
    silently claimed by whichever device connects first; it goes to
    legacy-unassigned/ instead."""
    (tmp_path / "state.json").write_text('{"active_profile": "performance"}')
    (tmp_path / "actions.jsonl").write_text('{"package": "com.example.a"}\n')
    legacy_snapshots = tmp_path / "RMX5303" / "snapshots"
    legacy_snapshots.mkdir(parents=True)
    (legacy_snapshots / "stock.snapshot.json").write_text("{}")

    new_dir = migrate_legacy_layout(tmp_path, "RMX5303", "SERIAL_A")

    assert not (new_dir / "state.json").exists()
    assert not (tmp_path / "state.json").exists()
    assert not legacy_snapshots.exists()
    unassigned = tmp_path / "legacy-unassigned"
    assert (unassigned / "state.json").read_text() == '{"active_profile": "performance"}'
    assert (unassigned / "actions.jsonl").exists()
    assert (unassigned / "RMX5303-snapshots" / "stock.snapshot.json").exists()


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
    the first device's state just because ambiguous legacy data exists."""
    (tmp_path / "state.json").write_text('{"active_profile": "performance"}')

    dir_a = migrate_legacy_layout(tmp_path, "RMX5303", "SERIAL_A")
    assert not (dir_a / "state.json").exists()  # quarantined, not claimed

    dir_b = migrate_legacy_layout(tmp_path, "RMX5303", "SERIAL_B")
    assert not (dir_b / "state.json").exists()
    assert dir_a != dir_b


def test_migrate_legacy_layout_noop_when_nothing_to_migrate(tmp_path: Path):
    new_dir = migrate_legacy_layout(tmp_path, "RMX5303", "SERIAL_A")
    assert not new_dir.exists()
