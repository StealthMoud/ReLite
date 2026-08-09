from __future__ import annotations

from pathlib import Path

from relite.actions import apply_transitions, latest_apply_id, load_journal, new_apply_id, records_for_apply
from relite.classifier import ClassificationDatabase, PackageClassification, ProtectedEntry
from relite.package_state import PackageState
from relite.profile_planner import plan_profile_transition


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


def test_new_apply_id_is_unique():
    assert new_apply_id() != new_apply_id()


def test_apply_transitions_writes_journal_and_returns_records(tmp_path: Path, fake_client, fake_runner):
    db = _db()
    baseline = {"com.example.ads": PackageState.PRESENT_ENABLED}
    current = dict(baseline)
    plan = plan_profile_transition(baseline, current, db, "safe")
    journal_path = tmp_path / "actions.jsonl"

    fake_runner.set_response(
        ["adb", "-s", "EMULATOR123", "shell", "pm disable-user --user 0 com.example.ads"],
        stdout="Package com.example.ads new state: disabled-user",
    )
    fake_runner.set_response(
        ["adb", "-s", "EMULATOR123", "shell", "pm list packages --user 0"],
        stdout="package:com.example.ads",
    )
    fake_runner.set_response(
        ["adb", "-s", "EMULATOR123", "shell", "pm list packages -d --user 0"],
        stdout="package:com.example.ads",
    )

    apply_id = new_apply_id()
    records = apply_transitions(fake_client, apply_id, "safe", plan, journal_path)

    assert len(records) == 1
    assert records[0].result == "ok"
    assert records[0].verified is True
    assert records[0].apply_id == apply_id
    assert records[0].baseline_state == "present_enabled"
    assert records[0].requested_state == "present_disabled"

    loaded = load_journal(journal_path)
    assert len(loaded) == 1
    assert loaded[0].package == "com.example.ads"
    assert loaded[0].schema == 3


def test_apply_transitions_dry_run_does_not_execute(tmp_path: Path, fake_client, fake_runner):
    db = _db()
    baseline = {"com.example.ads": PackageState.PRESENT_ENABLED}
    plan = plan_profile_transition(baseline, baseline, db, "safe")
    journal_path = tmp_path / "actions.jsonl"

    records = apply_transitions(fake_client, new_apply_id(), "safe", plan, journal_path, dry_run=True)

    assert records[0].result == "dry-run"
    assert fake_runner.calls == []


def test_apply_transitions_detects_platform_silently_refusing_disable(tmp_path: Path, fake_client, fake_runner):
    """Real-device finding (RMX5303, 2026-08-08): `pm disable-user` can exit 0
    while the platform refuses the change. Live state, not stdout text, is
    authoritative."""
    db = _db()
    baseline = {"com.example.ads": PackageState.PRESENT_ENABLED}
    plan = plan_profile_transition(baseline, baseline, db, "safe")
    journal_path = tmp_path / "actions.jsonl"

    fake_runner.set_response(
        ["adb", "-s", "EMULATOR123", "shell", "pm disable-user --user 0 com.example.ads"],
        stdout="Package com.example.ads new state: default",
        returncode=0,
    )
    fake_runner.set_response(
        ["adb", "-s", "EMULATOR123", "shell", "pm list packages --user 0"],
        stdout="package:com.example.ads",
    )
    fake_runner.set_response(
        ["adb", "-s", "EMULATOR123", "shell", "pm list packages -e --user 0"],
        stdout="package:com.example.ads",  # still enabled live
    )

    records = apply_transitions(fake_client, new_apply_id(), "safe", plan, journal_path)

    assert records[0].result.startswith("error:")
    assert records[0].verified is False


def test_apply_transitions_treats_unexpected_stdout_as_diagnostic_only(tmp_path: Path, fake_client, fake_runner):
    """If live state confirms the change, unexpected stdout text alone
    must not fail the action (section 9: stdout is diagnostic, not
    authoritative)."""
    db = _db()
    baseline = {"com.example.ads": PackageState.PRESENT_ENABLED}
    plan = plan_profile_transition(baseline, baseline, db, "safe")
    journal_path = tmp_path / "actions.jsonl"

    fake_runner.set_response(
        ["adb", "-s", "EMULATOR123", "shell", "pm disable-user --user 0 com.example.ads"],
        stdout="some unexpected wording this OEM build uses",
        returncode=0,
    )
    fake_runner.set_response(
        ["adb", "-s", "EMULATOR123", "shell", "pm list packages --user 0"],
        stdout="package:com.example.ads",
    )
    fake_runner.set_response(
        ["adb", "-s", "EMULATOR123", "shell", "pm list packages -d --user 0"],
        stdout="package:com.example.ads",  # live state confirms it IS disabled
    )

    records = apply_transitions(fake_client, new_apply_id(), "safe", plan, journal_path)

    assert records[0].result == "ok"
    assert records[0].verified is True


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
    assert loaded[0].apply_id == ""


def test_records_for_apply_filters_by_transaction(tmp_path: Path, fake_client, fake_runner):
    db = _db()
    baseline = {"com.example.ads": PackageState.PRESENT_ENABLED}
    plan = plan_profile_transition(baseline, baseline, db, "safe")
    journal_path = tmp_path / "actions.jsonl"

    id_a = new_apply_id()
    apply_transitions(fake_client, id_a, "safe", plan, journal_path, dry_run=True)
    id_b = new_apply_id()
    apply_transitions(fake_client, id_b, "safe", plan, journal_path, dry_run=True)

    assert len(records_for_apply(journal_path, id_a)) == 1
    assert len(records_for_apply(journal_path, id_b)) == 1
    assert latest_apply_id(journal_path) == id_b
