"""Rollback: restore packages, settings, and animation scale from either the
action journal or a full stock snapshot. Restore is a first-class feature,
not an afterthought — see section 27 of the master plan.
"""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path

from relite.actions import ActionRecord, load_journal
from relite.adb import AdbClient
from relite.packages import list_packages
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
    in a stock snapshot. This is the authoritative full-restore path.

    Only touches packages whose *current* state actually differs from the
    snapshot. Real-device finding (RMX5303, 2026-08-08): an earlier
    version unconditionally re-ran install-existing + enable for every
    package in the snapshot (hundreds of ADB round-trips on a real
    device), which also surfaced spurious errors for OS-protected
    packages ReLite never touched in the first place (e.g. `pm enable`
    on `com.google.android.devicelockcontroller` raises
    `SecurityException: Cannot disable a protected package` even though
    nothing needed restoring there — it was already in its snapshot
    state). Diffing against current state avoids both problems.
    """
    restored: list[str] = []
    errors: list[str] = []

    current = {pkg.name: pkg for pkg in list_packages(client)}

    for name, pkg in snapshot.packages.items():
        if pkg.disabled:
            continue  # was already disabled in the snapshot; nothing to restore

        now = current.get(name)
        already_correct = now is not None and not now.disabled
        if already_correct:
            continue  # present and enabled for user 0, matching the snapshot — nothing to do

        if dry_run:
            restored.append(name)
            continue

        # Real-device finding (RMX5303, 2026-08-08): `pm enable` exits 0
        # unconditionally (it only flips the component-enabled flag) even
        # when the package was uninstalled for user 0 via
        # `pm uninstall --user 0` — it does NOT reinstall it, so a prior
        # version of this function that tried `pm enable` first and only
        # fell back to `install-existing` on failure never reached the
        # fallback, silently leaving uninstalled packages uninstalled
        # while reporting "restored". `install-existing` must run first,
        # unconditionally, since it's the only command that actually
        # guarantees presence for the user; it's a no-op success if the
        # package was never uninstalled.
        reinstall = client.shell(f"cmd package install-existing --user 0 {name}")
        enable = client.shell(f"pm enable {name}")
        if reinstall.ok and enable.ok:
            restored.append(name)
        else:
            errors.append(f"{name}: {reinstall.stderr.strip() or enable.stderr.strip()}")

    settings_restored: dict[str, str] = {}
    animation_values = snapshot.animation_scales()
    for key in ANIMATION_KEYS:
        value = animation_values.get(key, "1.0")
        if not dry_run:
            client.shell(f"settings put global {key} {value}")
        settings_restored[key] = value

    return RestoreResult(packages_restored=restored, settings_restored=settings_restored, errors=errors)
