"""Explicit per-user package state model, and the minimal command sequence
needed to move a package from one state to another.

Section 2 of the v0.2.0 plan: restore correctness bugs came from inferring
state from loosely related booleans scattered across the codebase. This
module makes the three states a package can actually be in, for user 0,
first-class — and makes every transition between them explicit instead of
re-derived ad hoc at each call site.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from enum import StrEnum

from relite.packages import PackageInfo
from relite.validate import validate_package_name


class PackageState(StrEnum):
    PRESENT_ENABLED = "present_enabled"
    PRESENT_DISABLED = "present_disabled"
    ABSENT_FOR_USER = "absent_for_user"


def state_of(pkg: PackageInfo | None) -> PackageState:
    """The state a package is in, given its `PackageInfo` (or `None` if it
    didn't appear at all in a `--user 0`-scoped `list_packages()` result).
    """
    if pkg is None:
        return PackageState.ABSENT_FOR_USER
    if pkg.disabled:
        return PackageState.PRESENT_DISABLED
    return PackageState.PRESENT_ENABLED


@dataclass
class StateTransition:
    package: str
    current: PackageState
    desired: PackageState
    commands: list[str] = field(default_factory=list)

    @property
    def is_noop(self) -> bool:
        return not self.commands


def plan_transition(package: str, current: PackageState, desired: PackageState) -> StateTransition:
    """The minimal shell command sequence to move `package` from `current`
    to `desired`, verified against the real-device transition matrix:

        enabled  -> enabled   = no-op
        disabled -> disabled  = no-op
        disabled -> enabled   = pm enable
        enabled  -> disabled  = pm disable-user --user 0
        absent   -> enabled   = install-existing --user 0, then enable
        absent   -> disabled  = install-existing --user 0, then disable-user
        *        -> absent    = pm uninstall --user 0
    """
    validate_package_name(package)

    if current == desired:
        return StateTransition(package, current, desired, [])

    commands: list[str] = []

    if desired == PackageState.ABSENT_FOR_USER:
        commands.append(f"pm uninstall --user 0 {package}")
        return StateTransition(package, current, desired, commands)

    if current == PackageState.ABSENT_FOR_USER:
        commands.append(f"cmd package install-existing --user 0 {package}")

    if desired == PackageState.PRESENT_DISABLED:
        commands.append(f"pm disable-user --user 0 {package}")
    else:  # PRESENT_ENABLED
        commands.append(f"pm enable {package}")

    return StateTransition(package, current, desired, commands)
