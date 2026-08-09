"""Rollback: restore packages, settings, and animation scale from either the
action journal or a full stock snapshot. Restore is a first-class feature,
not an afterthought — see section 27 of the master plan.
"""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path

from relite.actions import ActionRecord, load_journal
from relite.adb import AdbClient
from relite.package_state import PackageState, plan_transition, state_of
from relite.packages import list_packages
from relite.snapshot import MANAGED_SETTINGS, Snapshot
from relite.tuning import restore_managed_setting


@dataclass
class RestoreResult:
    packages_restored: list[str]
    settings_restored: dict[str, str]
    errors: list[str]


def restore_from_journal(client: AdbClient, journal_path: Path, *, dry_run: bool = False) -> RestoreResult:
    """Reverse every recorded action, most recent first."""
    records: list[ActionRecord] = load_journal(journal_path)
    restored: list[str] = []
    errors: list[str] = []

    for record in reversed(records):
        if record.result not in ("ok", "dry-run"):
            continue
        rollback_args = record.rollback_command.split()
        if dry_run:
            restored.append(record.package)
            continue
        result = client.raw(*rollback_args)
        if result.ok:
            restored.append(record.package)
        else:
            errors.append(f"{record.package}: {result.stderr.strip()}")

    return RestoreResult(packages_restored=restored, settings_restored={}, errors=errors)


def restore_from_snapshot(client: AdbClient, snapshot: Snapshot, *, dry_run: bool = False) -> RestoreResult:
    """Restore package state and ReLite-managed settings exactly as recorded
    in a stock snapshot. This is the authoritative full-restore path.

    Every package is resolved to an explicit `PackageState`
    (`relite/package_state.py`) and moved via the minimal verified
    transition for `current -> desired`, covering all combinations —
    including "snapshot says disabled, device currently has it fully
    absent" and "snapshot says enabled, device currently has it merely
    disabled" — not just the enabled/absent cases an earlier version
    handled. Real-device finding (RMX5303, 2026-08-08): `pm enable` exits
    0 unconditionally without reinstalling, so a transition into an
    enabled or disabled *target* state must run `install-existing` first
    whenever the package is currently absent.

    Only touches packages whose *current* state actually differs from the
    snapshot's desired state — diffing first avoids both a full re-run of
    every package on every restore (hundreds of ADB round-trips) and
    spurious errors on OS-protected packages ReLite never touched.

    After executing the transitions, the resulting live state is verified
    with a single follow-up `list_packages()` call — command exit codes
    alone are not trusted (see `relite/actions.py` for the same finding on
    the apply path).
    """
    errors: list[str] = []

    current = {pkg.name: pkg for pkg in list_packages(client)}

    transitions = []
    for name, pkg in snapshot.packages.items():
        desired = PackageState.PRESENT_DISABLED if pkg.disabled else PackageState.PRESENT_ENABLED
        transition = plan_transition(name, state_of(current.get(name)), desired)
        if not transition.is_noop:
            transitions.append(transition)

    if dry_run:
        restored = [t.package for t in transitions]
    else:
        attempted: list[str] = []
        for transition in transitions:
            ok = True
            last_error = ""
            for command in transition.commands:
                result = client.shell(command)
                if not result.ok:
                    ok = False
                    last_error = result.stderr.strip()
                    break
            if ok:
                attempted.append(transition.package)
            else:
                errors.append(f"{transition.package}: {last_error}")

        restored = []
        if attempted:
            observed_after = {pkg.name: pkg for pkg in list_packages(client)}
            by_package = {t.package: t for t in transitions}
            for name in attempted:
                transition = by_package[name]
                observed = state_of(observed_after.get(name))
                if observed == transition.desired:
                    restored.append(name)
                else:
                    errors.append(
                        f"{name}: expected {transition.desired.value} after restore, "
                        f"observed {observed.value}"
                    )

    settings_restored: dict[str, str] = {}
    for namespace, key in MANAGED_SETTINGS:
        original = snapshot.settings.get(namespace, {}).get(key)
        restore_managed_setting(client, namespace, key, original, dry_run=dry_run)
        settings_restored[f"{namespace}.{key}"] = original if original is not None else "(absent)"

    return RestoreResult(packages_restored=restored, settings_restored=settings_restored, errors=errors)
