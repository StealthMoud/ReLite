from __future__ import annotations

import pytest

from relite.package_state import PackageState, plan_transition, state_of
from relite.packages import PackageInfo
from relite.validate import ValidationError


def test_state_of_none_is_absent():
    assert state_of(None) == PackageState.ABSENT_FOR_USER


def test_state_of_disabled_package():
    assert state_of(PackageInfo(name="com.example.a", disabled=True)) == PackageState.PRESENT_DISABLED


def test_state_of_enabled_package():
    assert state_of(PackageInfo(name="com.example.a", disabled=False)) == PackageState.PRESENT_ENABLED


@pytest.mark.parametrize(
    "current,desired,expected_commands",
    [
        (PackageState.PRESENT_ENABLED, PackageState.PRESENT_ENABLED, []),
        (PackageState.PRESENT_DISABLED, PackageState.PRESENT_DISABLED, []),
        (PackageState.PRESENT_DISABLED, PackageState.PRESENT_ENABLED, ["pm enable com.example.a"]),
        (
            PackageState.PRESENT_ENABLED,
            PackageState.PRESENT_DISABLED,
            ["pm disable-user --user 0 com.example.a"],
        ),
        (
            PackageState.ABSENT_FOR_USER,
            PackageState.PRESENT_ENABLED,
            ["cmd package install-existing --user 0 com.example.a", "pm enable com.example.a"],
        ),
        (
            PackageState.ABSENT_FOR_USER,
            PackageState.PRESENT_DISABLED,
            [
                "cmd package install-existing --user 0 com.example.a",
                "pm disable-user --user 0 com.example.a",
            ],
        ),
        (PackageState.PRESENT_ENABLED, PackageState.ABSENT_FOR_USER, ["pm uninstall --user 0 com.example.a"]),
        (
            PackageState.PRESENT_DISABLED,
            PackageState.ABSENT_FOR_USER,
            ["pm uninstall --user 0 com.example.a"],
        ),
        (PackageState.ABSENT_FOR_USER, PackageState.ABSENT_FOR_USER, []),
    ],
)
def test_plan_transition_matrix(current, desired, expected_commands):
    transition = plan_transition("com.example.a", current, desired)
    assert transition.commands == expected_commands
    assert transition.is_noop == (not expected_commands)


def test_plan_transition_rejects_invalid_package_name():
    with pytest.raises(ValidationError):
        plan_transition("not a package; rm -rf", PackageState.ABSENT_FOR_USER, PackageState.PRESENT_ENABLED)
