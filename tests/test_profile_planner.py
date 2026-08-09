from __future__ import annotations

import pytest

from relite.classifier import ClassificationDatabase, PackageClassification, ProtectedEntry
from relite.package_state import PackageState
from relite.profile_planner import desired_state_for, plan_profile_transition


@pytest.mark.parametrize(
    "baseline,action,expected",
    [
        (PackageState.PRESENT_ENABLED, "keep", PackageState.PRESENT_ENABLED),
        (PackageState.PRESENT_DISABLED, "keep", PackageState.PRESENT_DISABLED),
        (PackageState.ABSENT_FOR_USER, "keep", PackageState.ABSENT_FOR_USER),
        (PackageState.PRESENT_ENABLED, "disable", PackageState.PRESENT_DISABLED),
        (PackageState.PRESENT_DISABLED, "disable", PackageState.PRESENT_DISABLED),
        # disable must never resurrect a package the user had already removed
        (PackageState.ABSENT_FOR_USER, "disable", PackageState.ABSENT_FOR_USER),
        (PackageState.PRESENT_ENABLED, "uninstall-user", PackageState.ABSENT_FOR_USER),
        (PackageState.PRESENT_DISABLED, "uninstall-user", PackageState.ABSENT_FOR_USER),
        (PackageState.ABSENT_FOR_USER, "uninstall-user", PackageState.ABSENT_FOR_USER),
    ],
)
def test_desired_state_for(baseline, action, expected):
    assert desired_state_for(baseline, action) == expected


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


def test_plan_profile_transition_stock_to_performance():
    db = _db()
    baseline = {"com.example.ads": PackageState.PRESENT_ENABLED, "com.example.optional": PackageState.PRESENT_ENABLED}
    current = dict(baseline)

    plan = plan_profile_transition(baseline, current, db, "performance")

    assert {d.package: d.desired_state for d in plan} == {"com.example.ads": PackageState.PRESENT_DISABLED}


def test_plan_profile_transition_maximum_to_performance_reverses_uninstall():
    """The v0.2.0 bug: a package uninstalled by `maximum` must be
    reconsidered (reinstalled+disabled) when moving to `performance`,
    not silently ignored because it's absent from `list_packages()`."""
    db = _db()
    baseline = {"com.example.ads": PackageState.PRESENT_ENABLED, "com.example.optional": PackageState.PRESENT_ENABLED}
    current_after_maximum = {"com.example.ads": PackageState.ABSENT_FOR_USER}  # uninstalled by maximum

    plan = plan_profile_transition(baseline, current_after_maximum, db, "performance")

    ads_transition = next(d for d in plan if d.package == "com.example.ads")
    assert ads_transition.desired_state == PackageState.PRESENT_DISABLED
    assert "cmd package install-existing --user 0 com.example.ads" in ads_transition.transition.commands


def test_plan_profile_transition_maximum_direct_from_performance():
    """performance -> maximum directly, no intermediate restore required."""
    db = _db()
    baseline = {"com.example.ads": PackageState.PRESENT_ENABLED, "com.example.optional": PackageState.PRESENT_ENABLED}
    current_after_performance = {
        "com.example.ads": PackageState.PRESENT_DISABLED,
        "com.example.optional": PackageState.PRESENT_ENABLED,
    }

    plan = plan_profile_transition(baseline, current_after_performance, db, "maximum")

    by_pkg = {d.package: d.desired_state for d in plan}
    assert by_pkg["com.example.ads"] == PackageState.ABSENT_FOR_USER
    assert by_pkg["com.example.optional"] == PackageState.PRESENT_DISABLED


def test_plan_profile_transition_keep_does_not_resurrect_preexisting_removal():
    """A package the user had already disabled/removed before ReLite ever
    ran must not be re-enabled just because a profile says `keep` for it."""
    db = ClassificationDatabase(
        entries={
            "com.example.optional": PackageClassification(
                package="com.example.optional", action={"safe": "keep"}
            )
        },
    )
    baseline = {"com.example.optional": PackageState.ABSENT_FOR_USER}
    current = {"com.example.optional": PackageState.ABSENT_FOR_USER}

    plan = plan_profile_transition(baseline, current, db, "safe")

    assert plan == []  # already matches desired (baseline) state — no-op


def test_plan_profile_transition_skips_protected_packages():
    db = ClassificationDatabase(
        entries={
            "com.example.core": PackageClassification(package="com.example.core", action={"safe": "disable"})
        },
        protected={"com.example.core": ProtectedEntry(package="com.example.core")},
    )
    baseline = {"com.example.core": PackageState.PRESENT_ENABLED}
    current = {"com.example.core": PackageState.PRESENT_ENABLED}

    plan = plan_profile_transition(baseline, current, db, "safe")

    assert plan == []


def test_plan_profile_transition_missing_baseline_defaults_to_present_enabled():
    db = _db()
    plan = plan_profile_transition({}, {}, db, "performance")
    ads = next(d for d in plan if d.package == "com.example.ads")
    assert ads.baseline_state == PackageState.PRESENT_ENABLED
    assert ads.desired_state == PackageState.PRESENT_DISABLED
