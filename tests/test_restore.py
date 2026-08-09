from __future__ import annotations

import json
from dataclasses import dataclass
from pathlib import Path

from relite.actions import ActionRecord
from relite.adb import AdbClient
from relite.device import DeviceProfile
from relite.packages import PackageInfo
from relite.restore import restore_from_journal, restore_from_snapshot
from relite.snapshot import Snapshot


@dataclass
class FakeCompletedProcess:
    returncode: int = 0
    stdout: str = ""
    stderr: str = ""


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


_LIST_PACKAGES_SUFFIXES = ("all", "-s", "-3", "-d", "-e")


def _set_list_packages_sequence(fake_runner, before: dict[str, list[str]], after: dict[str, list[str]]) -> None:
    """`list_packages()` issues 5 `pm list packages ...` calls. Configure the
    first round (pre-restore) and second round (post-restore verification)
    with different results, keyed by flag suffix ("all", "-s", "-3", "-d", "-e")."""
    for suffix in _LIST_PACKAGES_SUFFIXES:
        flag = "" if suffix == "all" else suffix
        args = ["adb", "-s", "EMULATOR123", "shell", f"pm list packages{' ' + flag if flag else ''} --user 0"]
        before_out = "\n".join(f"package:{n}" for n in before.get(suffix, []))
        after_out = "\n".join(f"package:{n}" for n in after.get(suffix, []))
        fake_runner.set_response_sequence(
            args,
            [
                FakeCompletedProcess(0, before_out, ""),
                FakeCompletedProcess(0, after_out, ""),
            ],
        )


def test_restore_from_snapshot_round_trips_animation_scale(fake_client, fake_runner):
    _set_list_packages_sequence(
        fake_runner,
        before={"all": [], "-e": [], "-3": [], "-s": [], "-d": []},
        after={"all": ["com.example.kept"], "-e": ["com.example.kept"], "-3": [], "-s": [], "-d": []},
    )
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
    assert result.settings_restored["global.window_animation_scale"] == "1.0"


def test_restore_from_snapshot_always_reinstalls_before_enabling(fake_client, fake_runner):
    """Real-device finding (RMX5303, 2026-08-08): `pm enable` exits 0
    unconditionally, even for a package uninstalled via
    `pm uninstall --user 0` — it does NOT reinstall it. A prior version
    of restore_from_snapshot tried `pm enable` first and only fell back
    to `install-existing` on failure, so it never actually reinstalled
    uninstalled packages while still reporting them as "restored".
    install-existing must always run, not just as a fallback."""
    _set_list_packages_sequence(
        fake_runner,
        before={"all": [], "-e": [], "-3": [], "-s": [], "-d": []},
        after={
            "all": ["com.example.uninstalled"],
            "-e": ["com.example.uninstalled"],
            "-3": [],
            "-s": [],
            "-d": [],
        },
    )
    device = DeviceProfile(serial="EMULATOR123", props={"ro.product.model": "RMX5303"})
    snapshot = Snapshot(
        schema=1,
        name="stock",
        created_at="2026-08-08T00:00:00Z",
        device=device,
        packages={
            "com.example.uninstalled": PackageInfo(name="com.example.uninstalled", enabled=True, disabled=False),
        },
        settings={},
    )

    result = restore_from_snapshot(fake_client, snapshot)

    called_commands = [" ".join(c) for c in fake_runner.calls]
    assert any("install-existing --user 0 com.example.uninstalled" in c for c in called_commands)
    assert "com.example.uninstalled" in result.packages_restored


def test_restore_from_snapshot_disabled_to_absent_reinstalls_then_disables(fake_client, fake_runner):
    """snapshot says disabled, device currently has it fully absent."""
    _set_list_packages_sequence(
        fake_runner,
        before={"all": [], "-e": [], "-3": [], "-s": [], "-d": []},
        after={"all": ["com.example.d"], "-e": [], "-3": [], "-s": [], "-d": ["com.example.d"]},
    )
    device = DeviceProfile(serial="EMULATOR123", props={"ro.product.model": "RMX5303"})
    snapshot = Snapshot(
        schema=1,
        name="stock",
        created_at="2026-08-08T00:00:00Z",
        device=device,
        packages={"com.example.d": PackageInfo(name="com.example.d", enabled=False, disabled=True)},
        settings={},
    )

    result = restore_from_snapshot(fake_client, snapshot)

    called_commands = [" ".join(c) for c in fake_runner.calls]
    assert any("install-existing --user 0 com.example.d" in c for c in called_commands)
    assert any(c.endswith("shell pm disable-user --user 0 com.example.d") for c in called_commands)
    assert "com.example.d" in result.packages_restored


def test_restore_from_snapshot_enabled_to_disabled_calls_pm_enable(fake_client, fake_runner):
    """snapshot says enabled, device currently has it merely disabled."""
    _set_list_packages_sequence(
        fake_runner,
        before={"all": ["com.example.x"], "-e": [], "-3": [], "-s": [], "-d": ["com.example.x"]},
        after={"all": ["com.example.x"], "-e": ["com.example.x"], "-3": [], "-s": [], "-d": []},
    )
    device = DeviceProfile(serial="EMULATOR123", props={"ro.product.model": "RMX5303"})
    snapshot = Snapshot(
        schema=1,
        name="stock",
        created_at="2026-08-08T00:00:00Z",
        device=device,
        packages={"com.example.x": PackageInfo(name="com.example.x", enabled=True, disabled=False)},
        settings={},
    )

    result = restore_from_snapshot(fake_client, snapshot)

    called_commands = [" ".join(c) for c in fake_runner.calls]
    assert any(c.endswith("shell pm enable com.example.x") for c in called_commands)
    assert not any("install-existing" in c for c in called_commands)
    assert "com.example.x" in result.packages_restored


def test_restore_from_snapshot_flags_unverified_transition_as_error(fake_client, fake_runner):
    """If the post-restore live state doesn't actually match what the
    snapshot wanted, restore must report it as an error, not silently
    claim success (exit code alone is not trusted)."""
    _set_list_packages_sequence(
        fake_runner,
        before={"all": [], "-e": [], "-3": [], "-s": [], "-d": []},
        after={"all": [], "-e": [], "-3": [], "-s": [], "-d": []},  # still absent after "restoring"
    )
    device = DeviceProfile(serial="EMULATOR123", props={"ro.product.model": "RMX5303"})
    snapshot = Snapshot(
        schema=1,
        name="stock",
        created_at="2026-08-08T00:00:00Z",
        device=device,
        packages={"com.example.stubborn": PackageInfo(name="com.example.stubborn", enabled=True, disabled=False)},
        settings={},
    )

    result = restore_from_snapshot(fake_client, snapshot)

    assert "com.example.stubborn" not in result.packages_restored
    assert any("com.example.stubborn" in e for e in result.errors)


def test_restore_from_snapshot_restores_absent_private_dns_key_via_delete(fake_client, fake_runner):
    _set_list_packages_sequence(
        fake_runner,
        before={"all": [], "-e": [], "-3": [], "-s": [], "-d": []},
        after={"all": [], "-e": [], "-3": [], "-s": [], "-d": []},
    )
    device = DeviceProfile(serial="EMULATOR123", props={"ro.product.model": "RMX5303"})
    snapshot = Snapshot(
        schema=1,
        name="stock",
        created_at="2026-08-08T00:00:00Z",
        device=device,
        packages={},
        settings={"global": {}},  # private_dns_mode was never set before ReLite touched it
    )

    result = restore_from_snapshot(fake_client, snapshot)

    called_commands = [" ".join(c) for c in fake_runner.calls]
    assert any("settings delete global private_dns_mode" in c for c in called_commands)
    assert result.settings_restored["global.private_dns_mode"] == "(absent)"


def test_restore_from_snapshot_restores_original_private_dns_hostname(fake_client, fake_runner):
    _set_list_packages_sequence(
        fake_runner,
        before={"all": [], "-e": [], "-3": [], "-s": [], "-d": []},
        after={"all": [], "-e": [], "-3": [], "-s": [], "-d": []},
    )
    device = DeviceProfile(serial="EMULATOR123", props={"ro.product.model": "RMX5303"})
    snapshot = Snapshot(
        schema=1,
        name="stock",
        created_at="2026-08-08T00:00:00Z",
        device=device,
        packages={},
        settings={
            "global": {
                "private_dns_mode": "hostname",
                "private_dns_specifier": "dns.example.com",
            }
        },
    )

    restore_from_snapshot(fake_client, snapshot)

    called_commands = [" ".join(c) for c in fake_runner.calls]
    assert any("settings put global private_dns_mode hostname" in c for c in called_commands)
    assert any("settings put global private_dns_specifier dns.example.com" in c for c in called_commands)


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
