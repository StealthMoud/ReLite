"""Executes a profile transition plan and journals every change.

Default action is `pm disable-user`. A more aggressive profile can use
`pm uninstall --user 0`, which is reversed with
`cmd package install-existing --user 0`. ReLite never deletes APKs from
/system, /product, /vendor, or /system_ext, and never remounts system
partitions.

v0.3.0 (sections 8-11 of the master plan) replaced the old
build_plan()/apply_plan() pair — which independently derived a single
command per package from an `Action` string — with execution driven
directly by `relite.profile_planner.DesiredTransition`, the same
baseline-aware planner `relite plan` renders for preview. There is no
longer a second, independent way to decide what command a package
needs: plan and apply share one source of truth.
"""

from __future__ import annotations

import json
import uuid
from dataclasses import dataclass, field
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

from relite.adb import AdbClient
from relite.package_state import PackageState, state_of
from relite.packages import list_packages
from relite.profile_planner import DesiredTransition

JOURNAL_SCHEMA_VERSION = 3

# Command substring `pm`/`cmd package` prints on genuine success — used
# only as a *diagnostic* signal recorded alongside the result, never to
# decide pass/fail on its own (section 9: exit code and stdout text are
# diagnostic; live post-command state is authoritative). Real-device
# finding (RMX5303, 2026-08-08): `pm disable-user` can exit 0 while the
# platform silently refuses the change, printing "new state: default"
# instead of "new state: disabled-user".
_EXPECTED_OUTPUT_SUBSTRING: dict[PackageState, str] = {
    PackageState.PRESENT_DISABLED: "disabled-user",
}


def new_apply_id() -> str:
    return uuid.uuid4().hex


@dataclass
class ActionRecord:
    timestamp: str
    package: str
    previous_state: str
    action: str
    result: str
    profile: str
    # v3 fields (section 10 of the v0.3.0 plan): a transaction id shared
    # by every record from one `relite apply`, the pre-ReLite baseline
    # state (what `relite undo`'s state-engine rollback target requires
    # to distinguish from "just undo to previous_state"), and the full
    # (possibly multi-step) command list `package_state.plan_transition`
    # produced.
    apply_id: str = ""
    baseline_state: str = ""
    commands: list[str] = field(default_factory=list)
    requested_state: str = ""
    observed_state: str = ""
    verified: bool | None = None
    schema: int = 1
    # v1/v2 fields, kept only so old journal lines still decode via
    # ActionRecord(**data) and so v1/v2 records without enough explicit
    # state information still have *something* rollback can fall back to.
    command: str = ""
    rollback_command: str = ""

    def to_dict(self) -> dict[str, Any]:
        return {
            "timestamp": self.timestamp,
            "package": self.package,
            "previous_state": self.previous_state,
            "action": self.action,
            "result": self.result,
            "profile": self.profile,
            "apply_id": self.apply_id,
            "baseline_state": self.baseline_state,
            "commands": self.commands,
            "requested_state": self.requested_state,
            "observed_state": self.observed_state,
            "verified": self.verified,
            "schema": self.schema,
            "command": self.command,
            "rollback_command": self.rollback_command,
        }


def apply_transitions(
    client: AdbClient,
    apply_id: str,
    profile: str,
    transitions: list[DesiredTransition],
    journal_path: Path,
    *,
    dry_run: bool = False,
) -> list[ActionRecord]:
    """Execute every transition in a profile plan, appending one
    ActionRecord per package to the journal.

    Live post-command package state is the authoritative check (one
    follow-up `list_packages()` query for the whole plan, not one per
    package); command exit code and stdout text are recorded but only
    used to add diagnostic detail to an already-failed result.
    """
    executed: list[tuple[DesiredTransition, str]] = []

    for item in transitions:
        if dry_run:
            result = "dry-run"
        else:
            result = "ok"
            for command in item.transition.commands:
                cmd_result = client.shell(command)
                if not cmd_result.ok:
                    result = f"error: {cmd_result.stderr.strip()}"
                    break
                expected_substring = _EXPECTED_OUTPUT_SUBSTRING.get(item.desired_state)
                if expected_substring and expected_substring not in cmd_result.stdout:
                    # Diagnostic only — final pass/fail still comes from the
                    # live-state re-query below, not this text match alone.
                    result = f"diagnostic: unexpected output for {command!r}: {cmd_result.stdout.strip()!r}"
        executed.append((item, result))

    live_state: dict[str, PackageState] = {}
    if not dry_run and any(not result.startswith("error:") for _, result in executed):
        live_packages = {pkg.name: pkg for pkg in list_packages(client)}
        live_state = {item.package: state_of(live_packages.get(item.package)) for item, _ in executed}

    records: list[ActionRecord] = []
    journal_path.parent.mkdir(parents=True, exist_ok=True)
    with journal_path.open("a") as journal:
        for item, result in executed:
            observed_state = live_state.get(item.package)
            verified: bool | None = None

            if not result.startswith("error:") and observed_state is not None:
                verified = observed_state == item.desired_state
                if not verified:
                    result = (
                        f"error: live state after apply is {observed_state.value}, "
                        f"expected {item.desired_state.value}"
                    )
                elif result.startswith("diagnostic:"):
                    result = "ok"  # live state confirms success despite the unexpected stdout text

            record = ActionRecord(
                timestamp=datetime.now(UTC).isoformat(),
                package=item.package,
                previous_state=item.current_state.value,
                action=item.action,
                result=result,
                profile=profile,
                apply_id=apply_id,
                baseline_state=item.baseline_state.value,
                commands=item.transition.commands,
                requested_state=item.desired_state.value,
                observed_state=observed_state.value if observed_state else "",
                verified=verified,
                schema=JOURNAL_SCHEMA_VERSION,
            )
            journal.write(json.dumps(record.to_dict()) + "\n")
            records.append(record)
    return records


def load_journal(journal_path: Path) -> list[ActionRecord]:
    if not journal_path.exists():
        return []
    records = []
    for line in journal_path.read_text().splitlines():
        if not line.strip():
            continue
        data = json.loads(line)
        records.append(ActionRecord(**data))
    return records


def records_for_apply(journal_path: Path, apply_id: str) -> list[ActionRecord]:
    return [r for r in load_journal(journal_path) if r.apply_id == apply_id]


def latest_apply_id(journal_path: Path) -> str | None:
    records = load_journal(journal_path)
    for record in reversed(records):
        if record.apply_id:
            return record.apply_id
    return None
