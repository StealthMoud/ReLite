"""Rollback: restore packages, settings, and animation scale from either the
action journal or a full stock snapshot. Restore is a first-class feature,
not an afterthought — see section 27 of the master plan.
"""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path

from relite.actions import ActionRecord, load_journal
from relite.adb import AdbClient
from relite.snapshot import ANIMATION_KEYS, Snapshot


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
    """Restore package enabled/disabled state and settings exactly as recorded
    in a stock snapshot. This is the authoritative full-restore path."""
    restored: list[str] = []
    errors: list[str] = []

    for name, pkg in snapshot.packages.items():
        if pkg.disabled:
            continue  # was already disabled in the snapshot; nothing to restore
        if dry_run:
            restored.append(name)
            continue
        result = client.shell(f"pm enable {name}")
        if result.ok:
            restored.append(name)
        else:
            # package may have been uninstalled for user 0; try reinstalling
            reinstall = client.shell(f"cmd package install-existing --user 0 {name}")
            if reinstall.ok:
                restored.append(name)
            else:
                errors.append(f"{name}: {result.stderr.strip() or reinstall.stderr.strip()}")

    settings_restored: dict[str, str] = {}
    animation_values = snapshot.animation_scales()
    for key in ANIMATION_KEYS:
        value = animation_values.get(key, "1.0")
        if not dry_run:
            client.shell(f"settings put global {key} {value}")
        settings_restored[key] = value

    return RestoreResult(packages_restored=restored, settings_restored=settings_restored, errors=errors)
