"""Rollback: restore packages, settings, and animation scale from either the
action journal or a full stock snapshot. Restore is a first-class feature,
not an afterthought — see section 27 of the master plan.
"""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path

from relite.actions import ActionRecord, load_journal
from relite.adb import AdbClient
from relite.package_state import PackageState, StateTransition, plan_transition, state_of
from relite.packages import list_packages
from relite.snapshot import MANAGED_SETTINGS, Snapshot
from relite.tuning import restore_managed_setting

# v1/v2 journal records used bare "enabled"/"disabled"/"uninstalled-user"
# strings for previous_state, predating the explicit PackageState model.
_LEGACY_STATE_ALIASES: dict[str, PackageState] = {
    "enabled": PackageState.PRESENT_ENABLED,
    "disabled": PackageState.PRESENT_DISABLED,
    "uninstalled-user": PackageState.ABSENT_FOR_USER,
}


def _parse_record_state(value: str) -> PackageState | None:
    if not value:
        return None
    try:
        return PackageState(value)
    except ValueError:
        return _LEGACY_STATE_ALIASES.get(value)


@dataclass
class RestoreResult:
    packages_restored: list[str]
    settings_restored: dict[str, str]
    errors: list[str]


def restore_from_journal(
    client: AdbClient, journal_path: Path, *, apply_id: str | None = None, dry_run: bool = False
) -> RestoreResult:
    """Reverse recorded actions using the package-state engine, not the
    stored raw command strings (section 11 of the v0.3.0 plan).

    With `apply_id` set, only that transaction's records are reversed —
    the `relite undo` semantics (section 12): undo the most recent
    `relite apply`, not a device's entire ReLite history. Without it,
    every "ok" record is reversed, most recent first — the whole-journal
    behavior `relite restore` (no --snapshot) has always had.

    A v3 record's `previous_state` is an explicit `PackageState` value,
    so rollback becomes `plan_transition(current_live_state,
    previous_state)` — the same verified engine `restore_from_snapshot`
    uses, not a replay of whatever command string happened to be
    generated at apply time. A v1/v2 record only has
    "enabled"/"disabled"/"uninstalled-user" strings, mapped to the
    equivalent `PackageState`. A record with neither a parseable state
    nor a stored `rollback_command` is reported as an error rather than
    silently skipped or guessed at.
    """
    records = load_journal(journal_path)
    if apply_id is not None:
        records = [r for r in records if r.apply_id == apply_id]

    errors: list[str] = []
    engine_steps: list[tuple[ActionRecord, StateTransition]] = []
    legacy_steps: list[ActionRecord] = []
    current = {pkg.name: pkg for pkg in list_packages(client)}

    for record in reversed(records):
        # Section 5 (v0.4.0): do NOT skip records whose apply attempt
        # wasn't a clean "ok" — a multi-command transition can partially
        # modify the device (e.g. `install-existing` succeeded but the
        # follow-up `disable-user` failed), leaving the package neither at
        # its pre-transaction state nor its requested one. Live current
        # state (queried fresh above, not the journal's own diagnostic
        # text) drives the transition either way; `plan_transition`
        # naturally no-ops when current already equals `previous_state`,
        # so including every record here is safe and required for
        # transactional correctness rather than optional extra coverage.
        target = _parse_record_state(record.previous_state)
        if target is not None:
            transition = plan_transition(record.package, state_of(current.get(record.package)), target)
            if not transition.is_noop:
                engine_steps.append((record, transition))
        elif record.rollback_command:
            legacy_steps.append(record)
        else:
            errors.append(f"{record.package}: no explicit state or rollback command to reverse from")

    if dry_run:
        dry_run_restored = [r.package for r, _ in engine_steps] + [r.package for r in legacy_steps]
        return RestoreResult(packages_restored=dry_run_restored, settings_restored={}, errors=errors)

    attempted: list[str] = []
    for record, transition in engine_steps:
        ok = True
        last_error = ""
        for command in transition.commands:
            result = client.shell(command)
            if not result.ok:
                ok = False
                last_error = result.stderr.strip()
                break
        if ok:
            attempted.append(record.package)
        else:
            errors.append(f"{record.package}: {last_error}")

    restored: list[str] = []
    if attempted:
        observed_after = {pkg.name: pkg for pkg in list_packages(client)}
        by_package = {record.package: transition for record, transition in engine_steps}
        for name in attempted:
            transition = by_package[name]
            observed = state_of(observed_after.get(name))
            if observed == transition.desired:
                restored.append(name)
            else:
                errors.append(
                    f"{name}: expected {transition.desired.value} after undo, observed {observed.value}"
                )

    for record in legacy_steps:
        rollback_args = record.rollback_command.split()
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
        setting_result = restore_managed_setting(client, namespace, key, original, dry_run=dry_run)
        if setting_result.verified:
            settings_restored[f"{namespace}.{key}"] = original if original is not None else "(absent)"
        else:
            errors.append(
                f"setting {namespace}.{key}: restore not verified "
                f"(requested {setting_result.requested!r}, observed {setting_result.observed!r})"
            )

    return RestoreResult(packages_restored=restored, settings_restored=settings_restored, errors=errors)
