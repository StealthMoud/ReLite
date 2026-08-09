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

#: Integrity states under which the intended profile is still trustworthy
#: enough to record as "active" (section 6/33 of the v0.2.0 plan). FAIL
#: means at least one *unexpected, undocumented* package didn't end up in
#: its intended state, so apply must not claim a clean profile.
_CLEAN_ENOUGH_TO_RECORD = {"PASS", "PASS_WITH_LIMITATIONS", "DEGRADED"}


@dataclass
class DeviceState:
    active_profile: str | None = None
    applied_at: str | None = None
    last_snapshot: str | None = None
    # Section 6/7 (v0.3.0): the ONE snapshot the profile planner treats as
    # "what this device looked like before ReLite ever touched it", and
    # what `relite restore --all` restores to. Explicitly recorded, never
    # inferred from a filename like "stock" — see relite/baseline.py.
    baseline_snapshot: str | None = None
    # Section 6: the outcome of the *last* apply attempt, independent of
    # whether it was clean enough to become `active_profile`. Lets
    # `relite status` show "you tried to apply performance and it partially
    # failed" instead of silently reverting to "no profile recorded".
    last_apply_profile: str | None = None
    last_apply_status: str | None = None
    last_apply_unexpected_failures: int = 0

    def to_dict(self) -> dict[str, Any]:
        return {
            "active_profile": self.active_profile,
            "applied_at": self.applied_at,
            "last_snapshot": self.last_snapshot,
            "baseline_snapshot": self.baseline_snapshot,
            "last_apply_profile": self.last_apply_profile,
            "last_apply_status": self.last_apply_status,
            "last_apply_unexpected_failures": self.last_apply_unexpected_failures,
        }

    @classmethod
    def from_dict(cls, data: dict[str, Any]) -> DeviceState:
        return cls(
            active_profile=data.get("active_profile"),
            applied_at=data.get("applied_at"),
            last_snapshot=data.get("last_snapshot"),
            baseline_snapshot=data.get("baseline_snapshot"),
            last_apply_profile=data.get("last_apply_profile"),
            last_apply_status=data.get("last_apply_status"),
            last_apply_unexpected_failures=data.get("last_apply_unexpected_failures", 0),
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


def record_profile_applied(
    path: Path,
    profile: str,
    integrity_status: str = "PASS",
    *,
    unexpected_failures: int = 0,
) -> DeviceState:
    """Record the outcome of applying `profile`.

    `active_profile` is only set when `integrity_status` is clean enough to
    trust (section 6): an apply with unexpected, undocumented failures
    (FAIL) must not leave the device looking like it's cleanly on a
    profile it only partially reached. The attempt itself is always
    recorded via `last_apply_*` so `relite status` can report what
    actually happened instead of just reverting to "no profile".

    Loads and updates the existing state rather than constructing a
    fresh one — an earlier version of this function silently discarded
    `last_snapshot`/`baseline_snapshot` on every apply, and
    `record_snapshot_restored()` correspondingly discarded `last_apply_*`
    on every restore. State fields track independent facts and must
    accumulate, not overwrite each other.
    """
    is_clean_enough = integrity_status in _CLEAN_ENOUGH_TO_RECORD
    state = load_state(path)
    state.active_profile = profile if is_clean_enough else None
    state.applied_at = datetime.now(UTC).isoformat()
    state.last_apply_profile = profile
    state.last_apply_status = integrity_status
    state.last_apply_unexpected_failures = unexpected_failures
    save_state(path, state)
    return state


def record_snapshot_restored(path: Path, snapshot_name: str) -> DeviceState:
    """Section 13 (v0.3.0): after a full baseline restore, the device is
    no longer cleanly "on" whatever profile was last applied — restoring
    can only be assumed to return the device to its pre-ReLite baseline,
    not to any specific profile's exact state (the baseline predates any
    profile). `active_profile` is explicitly cleared, not left stale.
    """
    state = load_state(path)
    state.active_profile = None
    state.applied_at = None
    state.last_snapshot = snapshot_name
    save_state(path, state)
    return state


def record_baseline_snapshot(path: Path, snapshot_name: str) -> DeviceState:
    """Section 6: explicitly record which snapshot is the baseline —
    never inferred from a snapshot happening to be named "stock"."""
    state = load_state(path)
    state.baseline_snapshot = snapshot_name
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
class Downgrade:
    """A package that satisfies its profile's intent but not literally —
    e.g. the profile asked for `uninstall-user` (absent) and the package
    ended up merely `disable`d instead (still functionally acceptable,
    since disable is a strictly weaker but still-applied action, but not
    the literal requested state)."""

    package: str
    expected: str
    observed: str


@dataclass
class IntegrityReport:
    profile: str
    total_checked: int
    disabled_count: int
    mismatches: list[PackageMismatch] = field(default_factory=list)
    known_limitations: list[KnownLimitation] = field(default_factory=list)
    degraded: list[Downgrade] = field(default_factory=list)

    @property
    def status(self) -> str:
        """Section 33 of the v0.2.0 plan: distinguish four states rather
        than a flat PASS/FAIL.

        - FAIL: at least one unexpected, undocumented mismatch.
        - PASS_WITH_LIMITATIONS: every mismatch is a documented, unfixable
          OEM platform limitation.
        - DEGRADED: every package satisfies its profile's intent, but at
          least one only via a weaker action than literally requested
          (uninstall-user requested, disable observed) — functionally
          fine, reported honestly rather than folded into a silent PASS.
        - PASS: live state matches every profile decision exactly.
        """
        if self.mismatches:
            return "FAIL"
        if self.known_limitations:
            return "PASS_WITH_LIMITATIONS"
        if self.degraded:
            return "DEGRADED"
        return "PASS"


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
    degraded: list[Downgrade] = []
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
        # previously and not every package was re-touched). That's not a
        # compliance failure, but it's also not literally what was
        # requested — recorded as a "degraded" satisfaction, not folded
        # silently into a flat PASS (section 33).
        is_downgrade = expected == "absent" and observed == "disabled"
        satisfied = observed == expected or is_downgrade
        if is_downgrade:
            degraded.append(Downgrade(package=name, expected=expected, observed=observed))
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
        degraded=degraded,
    )
