from __future__ import annotations

from pathlib import Path

from relite.actions import apply_plan, build_plan, load_journal
from relite.classifier import ClassificationDatabase, PackageClassification, ProtectedEntry
from relite.packages import PackageInfo


def _db():
    return ClassificationDatabase(
        entries={
            "com.example.ads": PackageClassification(
                package="com.example.ads",
                category=["ads"],
                confidence="high",
                action={"safe": "disable", "performance": "disable", "maximum": "uninstall-user"},
            ),
            "com.example.protected_but_classified": PackageClassification(
                package="com.example.protected_but_classified",
                category=["ads"],
                confidence="high",
                action={"safe": "disable", "performance": "disable", "maximum": "uninstall-user"},
            ),
        },
        protected={
            "com.example.protected_but_classified": ProtectedEntry(package="com.example.protected_but_classified")
        },
    )


def test_build_plan_skips_protected_and_keep_packages():
    db = _db()
    installed = [
        PackageInfo(name="com.example.ads", system=True, enabled=True),
        PackageInfo(name="com.example.protected_but_classified", system=True, enabled=True),
        PackageInfo(name="com.example.unknown", system=True, enabled=True),
    ]
    plan = build_plan(installed, db, "safe")
    packages_in_plan = {item.package for item in plan}
    assert packages_in_plan == {"com.example.ads"}


def test_build_plan_skips_already_disabled_packages_for_disable_action():
    db = _db()
    installed = [PackageInfo(name="com.example.ads", system=True, enabled=False, disabled=True)]
    plan = build_plan(installed, db, "safe")
    assert plan == []


def test_every_action_has_a_generated_rollback():
    db = _db()
    installed = [PackageInfo(name="com.example.ads", system=True, enabled=True)]
    for profile in ("safe", "performance", "maximum"):
        plan = build_plan(installed, db, profile)
        for item in plan:
            assert item.command() is not None
            assert item.rollback_command() is not None
            assert item.command() != item.rollback_command()


def test_apply_plan_writes_journal_and_returns_records(tmp_path: Path, fake_client, fake_runner):
    db = _db()
    installed = [PackageInfo(name="com.example.ads", system=True, enabled=True)]
    plan = build_plan(installed, db, "safe")
    journal_path = tmp_path / "actions.jsonl"

    fake_runner.set_response(
        ["adb", "-s", "EMULATOR123", "shell", "pm", "disable-user", "--user", "0", "com.example.ads"],
        stdout="Package com.example.ads new state: disabled-user",
    )
    # Verification re-queries live state after the command runs; report the
    # package as actually disabled so the action is confirmed, not just
    # trusted from stdout text.
    fake_runner.set_response(
        ["adb", "-s", "EMULATOR123", "shell", "pm list packages --user 0"],
        stdout="package:com.example.ads",
    )
    fake_runner.set_response(
        ["adb", "-s", "EMULATOR123", "shell", "pm list packages -d --user 0"],
        stdout="package:com.example.ads",
    )

    records = apply_plan(fake_client, plan, journal_path)
    assert len(records) == 1
    assert records[0].result == "ok"
    assert records[0].verified is True
    assert records[0].requested_state == "present_disabled"
    assert records[0].observed_state == "present_disabled"

    loaded = load_journal(journal_path)
    assert len(loaded) == 1
    assert loaded[0].package == "com.example.ads"
    assert loaded[0].rollback_command == "shell pm enable com.example.ads"
    assert loaded[0].schema == 2


def test_apply_plan_dry_run_does_not_execute(tmp_path: Path, fake_client, fake_runner):
    db = _db()
    installed = [PackageInfo(name="com.example.ads", system=True, enabled=True)]
    plan = build_plan(installed, db, "safe")
    journal_path = tmp_path / "actions.jsonl"

    records = apply_plan(fake_client, plan, journal_path, dry_run=True)
    assert records[0].result == "dry-run"
    assert fake_runner.calls == []


def test_apply_plan_detects_platform_silently_refusing_disable(tmp_path: Path, fake_client, fake_runner):
    """Real-device finding (RMX5303, 2026-08-08): `pm disable-user` can exit 0
    while the platform refuses the change, printing "new state: default"
    instead of "new state: disabled-user". Exit code alone must not be
    treated as success."""
    db = _db()
    installed = [PackageInfo(name="com.example.ads", system=True, enabled=True)]
    plan = build_plan(installed, db, "safe")
    journal_path = tmp_path / "actions.jsonl"

    fake_runner.set_response(
        ["adb", "-s", "EMULATOR123", "shell", "pm", "disable-user", "--user", "0", "com.example.ads"],
        stdout="Package com.example.ads new state: default",
        returncode=0,
    )

    records = apply_plan(fake_client, plan, journal_path)
    assert records[0].result.startswith("error:")
    assert "platform refused" in records[0].result


def test_apply_plan_catches_success_stdout_with_unchanged_live_state(tmp_path: Path, fake_client, fake_runner):
    """Even if `pm` prints the exact expected success substring, the live
    state re-query is authoritative: if the package is still enabled
    afterward, the action must be reported as failed, not "ok"."""
    db = _db()
    installed = [PackageInfo(name="com.example.ads", system=True, enabled=True)]
    plan = build_plan(installed, db, "safe")
    journal_path = tmp_path / "actions.jsonl"

    fake_runner.set_response(
        ["adb", "-s", "EMULATOR123", "shell", "pm", "disable-user", "--user", "0", "com.example.ads"],
        stdout="Package com.example.ads new state: disabled-user",
    )
    # Text claims success, but the package is still enabled live.
    fake_runner.set_response(
        ["adb", "-s", "EMULATOR123", "shell", "pm list packages --user 0"],
        stdout="package:com.example.ads",
    )
    fake_runner.set_response(
        ["adb", "-s", "EMULATOR123", "shell", "pm list packages -e --user 0"],
        stdout="package:com.example.ads",
    )

    records = apply_plan(fake_client, plan, journal_path)
    assert records[0].result.startswith("error:")
    assert records[0].verified is False


def test_load_journal_reads_v1_records_without_new_fields(tmp_path: Path):
    journal_path = tmp_path / "actions.jsonl"
    journal_path.write_text(
        '{"timestamp": "2026-08-08T00:00:00Z", "package": "com.example.ads", '
        '"previous_state": "enabled", "action": "disable", '
        '"command": "shell pm disable-user --user 0 com.example.ads", "result": "ok", '
        '"rollback_command": "shell pm enable com.example.ads", "profile": "safe"}\n'
    )
    loaded = load_journal(journal_path)
    assert len(loaded) == 1
    assert loaded[0].schema == 1
    assert loaded[0].verified is None
    assert loaded[0].requested_state == ""
