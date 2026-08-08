"""Tracks which profile is currently active on a device, and can verify
that the device's live package state actually matches what that profile
should have produced — `relite status`'s "profile integrity" check.

This is deliberately separate from snapshot/journal state: a snapshot is
a point-in-time backup, the journal is a change log, but *this* is "what
does ReLite believe is the current, intended configuration" — the thing
`relite status` reports against.
"""

from __future__ import annotations

import json
from dataclasses import dataclass, field
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

from relite.classifier import ClassificationDatabase, Profile
from relite.packages import PackageInfo


@dataclass
class DeviceState:
    active_profile: str | None = None
    applied_at: str | None = None
    last_snapshot: str | None = None

    def to_dict(self) -> dict[str, Any]:
        return {
            "active_profile": self.active_profile,
            "applied_at": self.applied_at,
            "last_snapshot": self.last_snapshot,
        }

    @classmethod
    def from_dict(cls, data: dict[str, Any]) -> DeviceState:
        return cls(
            active_profile=data.get("active_profile"),
            applied_at=data.get("applied_at"),
            last_snapshot=data.get("last_snapshot"),
        )


def load_state(path: Path) -> DeviceState:
    if not path.exists():
        return DeviceState()
    try:
        return DeviceState.from_dict(json.loads(path.read_text()))
    except (json.JSONDecodeError, KeyError):
        return DeviceState()


def save_state(path: Path, state: DeviceState) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(state.to_dict(), indent=2, sort_keys=True) + "\n")


def record_profile_applied(path: Path, profile: str) -> DeviceState:
    state = DeviceState(active_profile=profile, applied_at=datetime.now(UTC).isoformat())
    save_state(path, state)
    return state


def record_snapshot_restored(path: Path, snapshot_name: str) -> DeviceState:
    state = DeviceState(active_profile=None, applied_at=None, last_snapshot=snapshot_name)
    save_state(path, state)
    return state


@dataclass
class PackageMismatch:
    package: str
    expected: str  # "present" | "disabled" | "absent"
    observed: str  # "present" | "disabled" | "absent"


@dataclass
class KnownLimitation:
    package: str
    expected: str
    observed: str
    reason: str


@dataclass
class IntegrityReport:
    profile: str
    total_checked: int
    disabled_count: int
    mismatches: list[PackageMismatch] = field(default_factory=list)
    known_limitations: list[KnownLimitation] = field(default_factory=list)

    @property
    def status(self) -> str:
        return "PASS" if not self.mismatches else "FAIL"


def check_profile_integrity(
    installed: list[PackageInfo],
    db: ClassificationDatabase,
    profile: Profile,
) -> IntegrityReport:
    """Compare live package state against what `profile` should produce.

    `installed` must come from `list_packages()` scoped to `--user 0`
    (see relite/packages.py) so packages uninstalled-for-user-0 are
    correctly absent from it, not merely marked disabled.
    """
    present = {pkg.name: pkg for pkg in installed}
    mismatches: list[PackageMismatch] = []
    known_limitations: list[KnownLimitation] = []
    disabled_count = 0

    # Only check packages the classification database actually has an
    # opinion about — packages db.classify() would call "unknown" are
    # always "keep" by policy and can't be out of compliance.
    for name in db.entries:
        if db.is_protected(name):
            continue
        expected_action = db.decide(name, profile)
        pkg = present.get(name)

        if expected_action == "keep":
            expected = "present"
        elif expected_action == "disable":
            expected = "disabled"
        else:  # uninstall-user
            expected = "absent"

        if pkg is None:
            observed = "absent"
        elif pkg.disabled:
            observed = "disabled"
        else:
            observed = "present"

        if observed == "disabled":
            disabled_count += 1

        # "disabled" satisfies an "absent" expectation too — uninstall-user
        # is a stronger action than disable, and a package can end up
        # merely disabled instead of uninstalled (e.g. platform refused
        # the uninstall, or a less aggressive profile was applied
        # previously and not every package was re-touched) without that
        # being a real compliance problem for reporting purposes.
        satisfied = observed == expected or (expected == "absent" and observed == "disabled")
        if not satisfied:
            limitation = db.entries[name].platform_limitation
            if limitation:
                known_limitations.append(
                    KnownLimitation(package=name, expected=expected, observed=observed, reason=limitation)
                )
            else:
                mismatches.append(PackageMismatch(package=name, expected=expected, observed=observed))

    return IntegrityReport(
        profile=profile,
        total_checked=len(db.entries),
        disabled_count=disabled_count,
        mismatches=mismatches,
        known_limitations=known_limitations,
    )
