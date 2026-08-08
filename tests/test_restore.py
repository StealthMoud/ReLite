from __future__ import annotations

import json
from pathlib import Path

from relite.actions import ActionRecord
from relite.adb import AdbClient
from relite.device import DeviceProfile
from relite.packages import PackageInfo
from relite.restore import restore_from_journal, restore_from_snapshot
from relite.snapshot import Snapshot


def _write_journal(path: Path, records: list[ActionRecord]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w") as f:
        for r in records:
            f.write(json.dumps(r.to_dict()) + "\n")


def test_restore_from_journal_reverses_every_supported_action(tmp_path: Path, fake_client: AdbClient, fake_runner):
    journal_path = tmp_path / "actions.jsonl"
    records = [
        ActionRecord(
            timestamp="2026-08-08T00:00:00Z",
            package="com.example.ads",
            previous_state="enabled",
            action="disable",
            command="shell pm disable-user --user 0 com.example.ads",
            result="ok",
            rollback_command="shell pm enable com.example.ads",
            profile="safe",
        ),
        ActionRecord(
            timestamp="2026-08-08T00:00:01Z",
            package="com.example.dead",
            previous_state="enabled",
            action="uninstall-user",
            command="shell pm uninstall --user 0 com.example.dead",
            result="ok",
            rollback_command="shell cmd package install-existing --user 0 com.example.dead",
            profile="maximum",
        ),
    ]
    _write_journal(journal_path, records)

    for record in records:
        fake_runner.set_response(
            ["adb", "-s", "EMULATOR123"] + record.rollback_command.split(), stdout="Success"
        )

    result = restore_from_journal(fake_client, journal_path)

    assert set(result.packages_restored) == {"com.example.ads", "com.example.dead"}
    assert result.errors == []
    # journal should be reversed most-recent-first
    called_packages = [c[-1] for c in fake_runner.calls]
    assert called_packages == ["com.example.dead", "com.example.ads"]


def test_restore_from_journal_skips_failed_or_dry_run_original_actions(tmp_path: Path, fake_client, fake_runner):
    journal_path = tmp_path / "actions.jsonl"
    record = ActionRecord(
        timestamp="2026-08-08T00:00:00Z",
        package="com.example.failed",
        previous_state="enabled",
        action="disable",
        command="shell pm disable-user --user 0 com.example.failed",
        result="error: permission denied",
        rollback_command="shell pm enable com.example.failed",
        profile="safe",
    )
    _write_journal(journal_path, [record])

    result = restore_from_journal(fake_client, journal_path)
    assert result.packages_restored == []
    assert fake_runner.calls == []


def test_restore_from_snapshot_round_trips_animation_scale(fake_client, fake_runner):
    device = DeviceProfile(serial="EMULATOR123", props={"ro.product.model": "RMX5303"})
    snapshot = Snapshot(
        schema=1,
        name="stock",
        created_at="2026-08-08T00:00:00Z",
        device=device,
        packages={
            "com.example.kept": PackageInfo(name="com.example.kept", enabled=True, disabled=False),
        },
        settings={"global": {"window_animation_scale": "1.0", "transition_animation_scale": "1.0", "animator_duration_scale": "1.0"}},
    )

    result = restore_from_snapshot(fake_client, snapshot)

    assert "com.example.kept" in result.packages_restored
    assert result.settings_restored["window_animation_scale"] == "1.0"


def test_snapshot_round_trip_preserves_state(tmp_path: Path):
    device = DeviceProfile(serial="EMULATOR123", props={"ro.product.model": "RMX5303"})
    snapshot = Snapshot(
        schema=1,
        name="stock",
        created_at="2026-08-08T00:00:00Z",
        device=device,
        packages={"com.example.foo": PackageInfo(name="com.example.foo", system=True, enabled=True)},
        settings={"global": {"window_animation_scale": "1.0"}},
    )
    path = tmp_path / "stock.snapshot.json"
    snapshot.save(path)

    loaded = Snapshot.load(path)
    assert loaded.name == "stock"
    assert loaded.device.model == "RMX5303"
    assert loaded.packages["com.example.foo"].system is True
    assert loaded.settings["global"]["window_animation_scale"] == "1.0"
