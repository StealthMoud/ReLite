from __future__ import annotations

from pathlib import Path

from relite.classifier import ClassificationDatabase, PackageClassification, ProtectedEntry
from relite.packages import PackageInfo
from relite.state import (
    DeviceState,
    check_profile_integrity,
    load_state,
    record_baseline_snapshot,
    record_profile_applied,
    record_snapshot_restored,
    save_state,
)


def _db() -> ClassificationDatabase:
    return ClassificationDatabase(
        entries={
            "com.example.ads": PackageClassification(
                package="com.example.ads",
                action={"safe": "disable", "performance": "disable", "maximum": "uninstall-user"},
            ),
            "com.example.optional": PackageClassification(
                package="com.example.optional",
                action={"safe": "keep", "performance": "keep", "maximum": "disable"},
            ),
        },
        protected={"com.example.core": ProtectedEntry(package="com.example.core")},
    )


def test_state_round_trips_through_file(tmp_path: Path):
    path = tmp_path / "state.json"
    record_profile_applied(path, "performance")
    state = load_state(path)
    assert state.active_profile == "performance"
    assert state.applied_at is not None


def test_record_snapshot_restored_clears_active_profile(tmp_path: Path):
    path = tmp_path / "state.json"
    record_profile_applied(path, "maximum")
    record_snapshot_restored(path, "stock")
    state = load_state(path)
    assert state.active_profile is None
    assert state.last_snapshot == "stock"


def test_record_profile_applied_marks_active_when_clean(tmp_path: Path):
    path = tmp_path / "state.json"
    state = record_profile_applied(path, "performance", "PASS")
    assert state.active_profile == "performance"
    assert state.last_apply_status == "PASS"


def test_record_profile_applied_marks_active_for_pass_with_limitations(tmp_path: Path):
    path = tmp_path / "state.json"
    state = record_profile_applied(path, "performance", "PASS_WITH_LIMITATIONS")
    assert state.active_profile == "performance"


def test_record_profile_applied_marks_active_for_degraded(tmp_path: Path):
    path = tmp_path / "state.json"
    state = record_profile_applied(path, "maximum", "DEGRADED")
    assert state.active_profile == "maximum"


def test_record_profile_applied_does_not_mark_active_on_fail(tmp_path: Path):
    """Section 6: unexpected failures must not leave the device looking
    like it cleanly reached the intended profile."""
    path = tmp_path / "state.json"
    state = record_profile_applied(path, "performance", "FAIL", unexpected_failures=3)
    assert state.active_profile is None
    assert state.last_apply_profile == "performance"
    assert state.last_apply_status == "FAIL"
    assert state.last_apply_unexpected_failures == 3

    reloaded = load_state(path)
    assert reloaded.active_profile is None
    assert reloaded.last_apply_profile == "performance"


def test_record_profile_applied_preserves_baseline_and_last_snapshot(tmp_path: Path):
    path = tmp_path / "state.json"
    record_baseline_snapshot(path, "auto-pre-relite")
    record_snapshot_restored(path, "auto-pre-relite")

    record_profile_applied(path, "performance", "PASS")

    reloaded = load_state(path)
    assert reloaded.active_profile == "performance"
    assert reloaded.baseline_snapshot == "auto-pre-relite"
    assert reloaded.last_snapshot == "auto-pre-relite"


def test_record_snapshot_restored_preserves_baseline_and_clears_active_profile(tmp_path: Path):
    path = tmp_path / "state.json"
    record_baseline_snapshot(path, "auto-pre-relite")
    record_profile_applied(path, "maximum", "PASS")

    record_snapshot_restored(path, "auto-pre-relite")

    reloaded = load_state(path)
    assert reloaded.active_profile is None
    assert reloaded.baseline_snapshot == "auto-pre-relite"
    assert reloaded.last_snapshot == "auto-pre-relite"


def test_record_baseline_snapshot_round_trips(tmp_path: Path):
    path = tmp_path / "state.json"
    record_baseline_snapshot(path, "stock")
    assert load_state(path).baseline_snapshot == "stock"


def test_load_state_missing_file_returns_default(tmp_path: Path):
    state = load_state(tmp_path / "does-not-exist.json")
    assert state.active_profile is None


def test_load_state_corrupt_file_fails_safe(tmp_path: Path):
    path = tmp_path / "state.json"
    path.write_text("{not valid json")
    state = load_state(path)
    assert state.active_profile is None


def test_save_state_creates_parent_dirs(tmp_path: Path):
    path = tmp_path / "nested" / "dir" / "state.json"
    save_state(path, DeviceState(active_profile="safe"))
    assert path.exists()


def test_integrity_passes_when_device_matches_profile():
    db = _db()
    installed = [
        PackageInfo(name="com.example.ads", disabled=True),
        PackageInfo(name="com.example.optional", disabled=False),
    ]
    report = check_profile_integrity(installed, db, "safe")
    assert report.status == "PASS"
    assert report.mismatches == []


def test_integrity_fails_when_ad_package_not_disabled():
    db = _db()
    installed = [
        PackageInfo(name="com.example.ads", disabled=False),
        PackageInfo(name="com.example.optional", disabled=False),
    ]
    report = check_profile_integrity(installed, db, "safe")
    assert report.status == "FAIL"
    assert len(report.mismatches) == 1
    assert report.mismatches[0].package == "com.example.ads"
    assert report.mismatches[0].expected == "disabled"
    assert report.mismatches[0].observed == "present"


def test_integrity_treats_disabled_as_satisfying_absent_expectation():
    """A package the profile wants uninstalled, but which only ended up
    disabled (e.g. the platform refused the uninstall, or a less
    aggressive profile ran previously), should not be reported as a
    compliance *failure* — disable is a strictly weaker but still-applied
    action, not a missed one. But it's also not literally what was
    requested, so it's surfaced as DEGRADED rather than folded silently
    into a flat PASS (section 33 of the v0.2.0 plan)."""
    db = _db()
    installed = [
        PackageInfo(name="com.example.ads", disabled=True),
        PackageInfo(name="com.example.optional", disabled=True),  # maximum expects "disable" too
    ]
    report = check_profile_integrity(installed, db, "maximum")
    assert report.status == "DEGRADED"
    assert report.mismatches == []
    assert [d.package for d in report.degraded] == ["com.example.ads"]


def test_integrity_ignores_protected_packages():
    db = ClassificationDatabase(
        entries={
            "com.example.core": PackageClassification(
                package="com.example.core", action={"safe": "disable"}
            )
        },
        protected={"com.example.core": ProtectedEntry(package="com.example.core")},
    )
    installed = [PackageInfo(name="com.example.core", disabled=False)]
    report = check_profile_integrity(installed, db, "safe")
    assert report.status == "PASS"


def test_integrity_absent_package_satisfies_uninstall_expectation():
    db = _db()
    installed: list[PackageInfo] = []  # neither package present
    report = check_profile_integrity(installed, db, "maximum")
    # com.example.ads -> uninstall-user -> "absent" expected, observed absent: satisfied.
    # com.example.optional -> disable -> "disabled" expected, observed absent: NOT satisfied.
    assert report.status == "FAIL"
    assert [m.package for m in report.mismatches] == ["com.example.optional"]


def test_integrity_treats_documented_platform_limitation_as_non_failure():
    """Real-device finding (RMX5303, 2026-08-08): com.coloros.lockassistant
    can never actually be disabled on this OEM build (the platform
    silently refuses pm disable-user). A package with a documented
    platform_limitation should not flip `relite status` to FAIL forever
    for something no ReLite action can fix."""
    db = ClassificationDatabase(
        entries={
            "com.example.stuck": PackageClassification(
                package="com.example.stuck",
                action={"safe": "disable"},
                platform_limitation="platform refuses to disable this package",
            )
        }
    )
    installed = [PackageInfo(name="com.example.stuck", disabled=False)]
    report = check_profile_integrity(installed, db, "safe")
    assert report.status == "PASS_WITH_LIMITATIONS"
    assert report.mismatches == []
    assert len(report.known_limitations) == 1
    assert report.known_limitations[0].package == "com.example.stuck"
