"""Reversible package actions: build a plan, apply it, journal every change.

Default action is `pm disable-user`. A more aggressive profile can use
`pm uninstall --user 0`, which is reversed with
`cmd package install-existing --user 0`. ReLite never deletes APKs from
/system, /product, /vendor, or /system_ext, and never remounts system
partitions.
"""

from __future__ import annotations

import json
from dataclasses import dataclass
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

from relite.adb import AdbClient
from relite.classifier import Action, ClassificationDatabase, Profile
from relite.package_state import PackageState, state_of
from relite.packages import PackageInfo, list_packages

JOURNAL_SCHEMA_VERSION = 2


@dataclass
class PlannedAction:
    package: str
    action: Action
    previous_state: str  # "enabled" | "disabled" | "uninstalled-user" | "unknown"
    reason: str
    profile: Profile

    def command(self) -> list[str] | None:
        if self.action == "keep":
            return None
        if self.action == "disable":
            return ["shell", "pm", "disable-user", "--user", "0", self.package]
        if self.action == "uninstall-user":
            return ["shell", "pm", "uninstall", "--user", "0", self.package]
        raise ValueError(f"unknown action {self.action}")

    def rollback_command(self) -> list[str] | None:
        if self.action == "keep":
            return None
        if self.action == "disable":
            return ["shell", "pm", "enable", self.package]
        if self.action == "uninstall-user":
            return ["shell", "cmd", "package", "install-existing", "--user", "0", self.package]
        raise ValueError(f"unknown action {self.action}")

    def expected_output_substring(self) -> str | None:
        """Substring `pm`/`cmd package` must print on genuine success.

        Found via real-device testing (RMX5303, 2026-08-08): `pm
        disable-user` can exit 0 while the platform silently refuses the
        change, printing "new state: default" instead of "new state:
        disabled-user" — some OEM builds protect specific packages from
        user-level disable independently of ReLite's own protected-package
        policy. Exit code alone is not sufficient evidence of success.
        """
        if self.action == "disable":
            return "disabled-user"
        if self.action == "uninstall-user":
            return "Success"
        return None

    def expected_state(self) -> PackageState | None:
        """The live `PackageState` this action should produce — the
        authoritative check `apply_plan` verifies against, independent of
        whatever text `pm`/`cmd package` happened to print."""
        if self.action == "disable":
            return PackageState.PRESENT_DISABLED
        if self.action == "uninstall-user":
            return PackageState.ABSENT_FOR_USER
        return None


@dataclass
class ActionRecord:
    timestamp: str
    package: str
    previous_state: str
    action: str
    command: str
    result: str
    rollback_command: str
    profile: str
    # v2 fields (section 34 of the v0.2.0 plan). Defaulted so a v0.1
    # journal loads unchanged — old records implicitly get schema=1 and
    # unknown/unverified state, which is exactly what's true of them.
    schema: int = 1
    requested_state: str = ""
    observed_state: str = ""
    verified: bool | None = None

    def to_dict(self) -> dict[str, Any]:
        return {
            "timestamp": self.timestamp,
            "package": self.package,
            "previous_state": self.previous_state,
            "action": self.action,
            "command": self.command,
            "result": self.result,
            "rollback_command": self.rollback_command,
            "profile": self.profile,
            "schema": self.schema,
            "requested_state": self.requested_state,
            "observed_state": self.observed_state,
            "verified": self.verified,
        }


def build_plan(
    installed: list[PackageInfo],
    db: ClassificationDatabase,
    profile: Profile,
) -> list[PlannedAction]:
    """Decide an action for every installed package under a given profile."""
    plan: list[PlannedAction] = []
    for pkg in installed:
        action = db.decide(pkg.name, profile)
        if action == "keep":
            continue
        if pkg.disabled and action == "disable":
            continue  # already disabled, nothing to do
        classification = db.classify(pkg.name)
        previous_state = "disabled" if pkg.disabled else "enabled"
        plan.append(
            PlannedAction(
                package=pkg.name,
                action=action,
                previous_state=previous_state,
                reason=classification.reason or "no reason recorded",
                profile=profile,
            )
        )
    return plan


def apply_plan(
    client: AdbClient,
    plan: list[PlannedAction],
    journal_path: Path,
    *,
    dry_run: bool = False,
) -> list[ActionRecord]:
    """Execute a plan, appending one ActionRecord per package to the journal.

    Command exit code and stdout text are only the *first* signal — see
    `PlannedAction.expected_output_substring()`'s docstring for why an OEM
    build can print success while silently refusing the change. The
    authoritative check is a single follow-up `list_packages()` query
    after every command has run, comparing each package's actual live
    `PackageState` against what the action should have produced. This is
    one extra ADB round-trip for the whole plan, not one per package.
    """
    executed: list[tuple[PlannedAction, list[str], list[str], str]] = []

    for item in plan:
        command = item.command()
        rollback = item.rollback_command()
        if command is None or rollback is None:
            continue
        if dry_run:
            result = "dry-run"
        else:
            cmd_result = client.raw(*command)
            expected = item.expected_output_substring()
            if not cmd_result.ok:
                result = f"error: {cmd_result.stderr.strip()}"
            elif expected and expected not in cmd_result.stdout:
                result = f"error: platform refused change: {cmd_result.stdout.strip()}"
            else:
                result = "ok"
        executed.append((item, command, rollback, result))

    live_state: dict[str, PackageState] = {}
    if not dry_run and any(result == "ok" for _, _, _, result in executed):
        live_packages = {pkg.name: pkg for pkg in list_packages(client)}
        live_state = {item.package: state_of(live_packages.get(item.package)) for item, *_ in executed}

    records: list[ActionRecord] = []
    journal_path.parent.mkdir(parents=True, exist_ok=True)
    with journal_path.open("a") as journal:
        for item, command, rollback, result in executed:
            expected_state = item.expected_state()
            observed_state: PackageState | None = live_state.get(item.package)
            verified: bool | None = None

            if result == "ok" and expected_state is not None and observed_state is not None:
                verified = observed_state == expected_state
                if not verified:
                    result = (
                        f"error: live state after apply is {observed_state.value}, "
                        f"expected {expected_state.value}"
                    )
            elif result == "dry-run":
                verified = None

            record = ActionRecord(
                timestamp=datetime.now(UTC).isoformat(),
                package=item.package,
                previous_state=item.previous_state,
                action=item.action,
                command=" ".join(command),
                result=result,
                rollback_command=" ".join(rollback),
                profile=item.profile,
                schema=JOURNAL_SCHEMA_VERSION,
                requested_state=expected_state.value if expected_state else "",
                observed_state=observed_state.value if observed_state else "",
                verified=verified,
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
