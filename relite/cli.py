"""ReLite command-line interface.

    relite doctor
    relite device
    relite snapshot --name stock
    relite scan
    relite analyze
    relite plan --profile safe
    relite apply --profile safe
    relite status
    relite restore --snapshot stock
    relite tune ram-expansion off
    relite benchmark --label stock
    relite report
    relite network-adblock --hostname <dns-host>
"""

from __future__ import annotations

import os
import shutil
import sys
from pathlib import Path

import click
from rich.console import Console
from rich.table import Table

from relite import __version__
from relite.actions import apply_transitions, latest_apply_id, new_apply_id
from relite.adb import AdbClient, AdbUnavailableError
from relite.baseline import OwnershipStatus, validate_baseline
from relite.classifier import ClassificationDatabase, load_database
from relite.data_paths import devices_root
from relite.device import DeviceProfile, probe_device
from relite.device_identity import default_local_root, device_key, device_local_dir, migrate_legacy_layout
from relite.package_state import PackageState, state_of
from relite.packages import list_packages
from relite.profile_planner import DesiredTransition, plan_profile_transition
from relite.profiles import load_profiles
from relite.report import write_report
from relite.restore import restore_from_journal, restore_from_snapshot
from relite.snapshot import Snapshot, default_snapshot_dir, take_snapshot
from relite.state import (
    check_profile_integrity,
    clear_active_profile,
    load_state,
    record_baseline_snapshot,
    record_profile_applied,
    record_snapshot_restored,
    record_undo_result,
)
from relite.tuning import (
    ANIMATION_SCALE_KEYS,
    apply_animation_profile,
    load_tuning_change,
    probe_ram_expansion,
    read_animation_scale,
    record_tuning_change,
    restore_managed_setting,
    set_private_dns,
)
from relite.validate import ValidationError, validate_local_name

console = Console()

# Section 27-29: resolved once at import time (source checkout / packaged
# wheel / RELITE_DATA_DIR, in that order — see relite/data_paths.py), and
# re-resolved in main() if --data-dir overrides it for this invocation.
DEVICES_ROOT = devices_root()


def _profile_label(profile_name: str) -> str:
    """Never "safe"/"aggressive" as a value judgement on the others, just
    what it is relative to them — see relite/profiles.yaml."""
    meta = load_profiles().get(profile_name)
    return meta.label if meta else profile_name


def _find_device_dir(model: str) -> Path | None:
    for oem_dir in DEVICES_ROOT.glob("*"):
        candidate = oem_dir / model
        if candidate.is_dir():
            return candidate
    return None


def _local_dir(device_profile: DeviceProfile) -> Path:
    """The per-physical-device local state root, migrating any pre-v0.2.0
    model-keyed layout on first use (see relite/device_identity.py)."""
    root = default_local_root()
    migrate_legacy_layout(root, device_profile.model, device_profile.serial)
    return device_local_dir(root, device_profile.model, device_profile.serial)


def _device_key(device_profile: DeviceProfile) -> str:
    return device_key(default_local_root(), device_profile.model, device_profile.serial)


def _render_header(device_profile: DeviceProfile, profile_name: str, local_dir: Path) -> None:
    label = _profile_label(profile_name)
    snapshot_exists = default_snapshot_dir(local_dir).exists()
    console.print(f"[bold]ReLite — {device_profile.model}[/bold]")
    console.print()
    console.print("Device:")
    console.print(f"  {device_profile.model}  (Android {device_profile.android_version})")
    console.print()
    console.print("Profile:")
    console.print(f"  {profile_name}  ({label})")
    console.print()
    console.print(f"Rollback available: {'yes' if snapshot_exists else 'no snapshot taken yet'}")
    console.print(f"Snapshot on disk:   {'yes' if snapshot_exists else 'no'}")
    console.print()


def _render_changes(transitions: list[DesiredTransition]) -> None:
    by_desired: dict[PackageState, list[DesiredTransition]] = {}
    for item in transitions:
        by_desired.setdefault(item.desired_state, []).append(item)

    console.print("Changes:")
    disabling = by_desired.get(PackageState.PRESENT_DISABLED, [])
    removing = by_desired.get(PackageState.ABSENT_FOR_USER, [])
    restoring = by_desired.get(PackageState.PRESENT_ENABLED, [])
    if disabling:
        console.print(f"  Disable ({len(disabling)}):")
        for item in disabling:
            console.print(f"    - {item.package}")
    if removing:
        console.print(f"  Uninstall for user 0, reversible ({len(removing)}):")
        for item in removing:
            console.print(f"    - {item.package}")
    if restoring:
        console.print(f"  Restore to baseline (present, enabled) ({len(restoring)}):")
        for item in restoring:
            console.print(f"    - {item.package}")
    if not transitions:
        console.print("  (none — device already matches this profile)")
    console.print()


def _current_states(client: AdbClient, db: ClassificationDatabase) -> dict[str, PackageState]:
    installed = {pkg.name: pkg for pkg in list_packages(client)}
    return {name: state_of(installed.get(name)) for name in db.entries}


def _baseline_snapshot_path(local_dir: Path, name: str) -> Path:
    return default_snapshot_dir(local_dir) / f"{name}.snapshot.json"


def _resolve_baseline(
    client: AdbClient,
    serial: str,
    local_dir: Path,
    device_profile: DeviceProfile,
    db: ClassificationDatabase,
    *,
    allow_create: bool,
    allow_fallback_to_current: bool,
) -> tuple[dict[str, PackageState] | None, str | None]:
    """Section 7-8: resolve the baseline_states a profile transition
    should be computed against. Returns (states, warning_message).

    - A recorded, valid, matching-device baseline is used directly.
    - A recorded baseline with a firmware mismatch blocks live use
      entirely (section 5) unless `allow_fallback_to_current` — `plan`
      previews still show something useful with a clear caveat rather
      than refusing to render at all; `apply` does not set this.
    - No recorded baseline: create one now (`allow_create`, `apply`
      only) or fall back to treating current live state as the baseline
      (`allow_fallback_to_current`, `plan` only, since it never writes
      anything and a `keep` action is a no-op either way in that case).
    """
    state = load_state(local_dir / "state.json")
    if state.baseline_snapshot:
        path = _baseline_snapshot_path(local_dir, state.baseline_snapshot)
        validation = validate_baseline(path, device_profile, _device_key(device_profile))
        if validation.status == "valid" and validation.snapshot is not None:
            return validation.snapshot.baseline_states(db), None
        if validation.ownership == OwnershipStatus.FIRMWARE_DIFFERENT:
            warning = (
                f"Baseline firmware differs from current firmware ({validation.detail})."
            )
            fallback_note = " Showing a preview against current live state instead."
            if allow_fallback_to_current:
                return _current_states(client, db), warning + fallback_note
            reconcile_note = " Refusing to apply — run 'relite snapshot --name <n> --set-baseline'."
            return None, warning + reconcile_note
        if validation.ownership == OwnershipStatus.DEVICE_MISMATCH:
            warning = "Recorded baseline snapshot belongs to a different physical device."
            if allow_fallback_to_current:
                fallback_note = " Showing a preview against current live state instead."
                return _current_states(client, db), warning + fallback_note
            return None, warning
        if validation.ownership == OwnershipStatus.LEGACY_OWNERSHIP_UNKNOWN:
            # Section 11: never silently bind a pre-v0.3.0, ownership-
            # ambiguous snapshot to whichever device happens to be
            # connected — that's exactly the auto-assignment bug this
            # status exists to prevent, just at baseline-resolution time
            # instead of directory-migration time.
            warning = validation.detail
            if allow_fallback_to_current:
                fallback_note = " Showing a preview against current live state instead."
                return _current_states(client, db), warning + fallback_note
            return None, warning
        # missing/corrupt/unsupported_schema: fall through to (re)create below.

    if allow_create:
        dk = _device_key(device_profile)
        auto_snap = take_snapshot(client, serial, "auto-pre-relite", db, device_key=dk)
        auto_path = _baseline_snapshot_path(local_dir, "auto-pre-relite")
        auto_snap.save(auto_path)
        record_baseline_snapshot(local_dir / "state.json", "auto-pre-relite")
        console.print(f"[green]Safety snapshot created: {auto_path}[/green]")
        return auto_snap.baseline_states(db), None

    if allow_fallback_to_current:
        note = "No baseline snapshot recorded yet — showing a preview against current live state."
        return _current_states(client, db), note

    return None, "No usable baseline snapshot."


@click.group()
@click.version_option(version=__version__, prog_name="relite")
@click.option(
    "--serial", default=None, help="Target a specific ADB serial when multiple devices are attached."
)
@click.option(
    "--data-dir", default=None,
    help="Override where profiles/ and devices/ data is loaded from (same as RELITE_DATA_DIR).",
)
@click.pass_context
def main(ctx: click.Context, serial: str | None, data_dir: str | None) -> None:
    """ReLite — make Android lighter without replacing the hardware layer."""
    ctx.ensure_object(dict)
    ctx.obj["serial"] = serial
    if data_dir:
        os.environ["RELITE_DATA_DIR"] = data_dir
        global DEVICES_ROOT
        DEVICES_ROOT = devices_root()


def _client(ctx: click.Context) -> AdbClient:
    return AdbClient(serial=ctx.obj.get("serial"))


def _connected_client(ctx: click.Context) -> tuple[AdbClient, str]:
    client = _client(ctx)
    try:
        serial = client.require_single_device()
    except AdbUnavailableError as exc:
        console.print(f"[red]No usable device:[/red] {exc}")
        sys.exit(1)
    return AdbClient(serial=serial), serial


@main.command()
def doctor() -> None:
    """Check the local environment: adb availability and device state."""
    adb_path = shutil.which("adb")
    table = Table(title="ReLite doctor")
    table.add_column("Check")
    table.add_column("Result")

    table.add_row("adb on PATH", f"[green]yes[/green] ({adb_path})" if adb_path else "[red]no[/red]")

    if adb_path:
        client = AdbClient()
        try:
            devices = client.list_devices()
        except Exception as exc:  # noqa: BLE001
            table.add_row("adb devices", f"[red]error: {exc}[/red]")
        else:
            if not devices:
                table.add_row("adb devices", "[yellow]none connected[/yellow]")
            for serial, state in devices.items():
                color = "green" if state.value == "device" else "yellow"
                table.add_row(f"device {serial}", f"[{color}]{state.value}[/{color}]")

    console.print(table)


@main.command()
@click.pass_context
def device(ctx: click.Context) -> None:
    """Print device reconnaissance (build props, Treble/AVB/partition state)."""
    client, serial = _connected_client(ctx)
    profile = probe_device(client, serial)
    table = Table(title=f"Device: {profile.model}")
    table.add_column("Property")
    table.add_column("Value")
    for key in [
        "model", "android_version", "sdk_int", "security_patch", "fingerprint",
        "treble_enabled", "bootloader_locked", "verified_boot_state",
        "dynamic_partitions", "virtual_ab",
    ]:
        table.add_row(key, str(getattr(profile, key)))
    console.print(table)


@main.command()
@click.option("--name", required=True, help="Snapshot name, e.g. 'stock'.")
@click.option(
    "--set-baseline", "set_baseline", is_flag=True, default=False,
    help="Record this snapshot as the baseline relite apply/restore --all use.",
)
@click.pass_context
def snapshot(ctx: click.Context, name: str, set_baseline: bool) -> None:
    """Take a full snapshot of the current device state."""
    try:
        validate_local_name(name)
    except ValidationError as exc:
        console.print(f"[red]{exc}[/red]")
        sys.exit(1)
    client, serial = _connected_client(ctx)
    device_profile = probe_device(client, serial)
    device_dir = _find_device_dir(device_profile.model)
    db = load_database(device_dir) if device_dir else None
    console.print("Collecting device snapshot (packages, settings, props)...")
    snap = take_snapshot(client, serial, name, db, device_key=_device_key(device_profile))
    local_dir = _local_dir(snap.device)
    out_dir = default_snapshot_dir(local_dir)
    out_path = out_dir / f"{name}.snapshot.json"
    snap.save(out_path)
    console.print(f"[green]Saved snapshot to {out_path}[/green] ({len(snap.packages)} packages)")
    if set_baseline:
        record_baseline_snapshot(local_dir / "state.json", name)
        console.print("[green]Recorded as baseline.[/green]")


@main.command(name="baseline")
@click.argument("snapshot_name")
@click.pass_context
def baseline_cmd(ctx: click.Context, snapshot_name: str) -> None:
    """Record an *already-taken* snapshot as the baseline, without
    retaking it (use `relite snapshot --name X --set-baseline` to take a
    fresh one and set it in one step)."""
    try:
        validate_local_name(snapshot_name)
    except ValidationError as exc:
        console.print(f"[red]{exc}[/red]")
        sys.exit(1)
    client, serial = _connected_client(ctx)
    device_profile = probe_device(client, serial)
    local_dir = _local_dir(device_profile)
    path = _baseline_snapshot_path(local_dir, snapshot_name)
    validation = validate_baseline(path, device_profile, _device_key(device_profile))
    if validation.status != "valid":
        detail = validation.detail or validation.status
        console.print(f"[red]Cannot set '{snapshot_name}' as baseline: {detail}[/red]")
        sys.exit(1)
    record_baseline_snapshot(local_dir / "state.json", snapshot_name)
    console.print(f"[green]Baseline set to '{snapshot_name}'.[/green]")


@main.command()
@click.pass_context
def scan(ctx: click.Context) -> None:
    """Inventory installed packages."""
    client, _serial = _connected_client(ctx)
    packages = list_packages(client)
    console.print(f"{len(packages)} packages found "
                  f"({sum(p.system for p in packages)} system, "
                  f"{sum(p.disabled for p in packages)} disabled)")


@main.command()
@click.pass_context
def analyze(ctx: click.Context) -> None:
    """Classify installed packages against the device's classification database."""
    client, serial = _connected_client(ctx)
    profile = probe_device(client, serial)
    device_dir = _find_device_dir(profile.model)
    if device_dir is None:
        console.print(f"[yellow]No device profile found for '{profile.model}' under devices/. "
                       "All packages will be treated as unknown (kept).[/yellow]")
        db = load_database(Path("devices/_unknown"))
    else:
        db = load_database(device_dir)

    packages = list_packages(client)
    table = Table(title="Package classification")
    table.add_column("Package")
    table.add_column("Category")
    table.add_column("Confidence")
    for pkg in packages:
        classification = db.classify(pkg.name)
        if classification.confidence == "none":
            continue
        table.add_row(pkg.name, ",".join(classification.category), classification.confidence)
    console.print(table)


@main.command()
@click.option(
    "--profile", "profile_name", required=True, type=click.Choice(["safe", "performance", "maximum"])
)
@click.pass_context
def plan(ctx: click.Context, profile_name: str) -> None:
    """Print the plan of actions a profile would apply, without applying it.

    Uses the same baseline-aware planner as `apply` (section 8 of the
    v0.3.0 plan) — there is no second, independently-derived preview.
    """
    client, serial = _connected_client(ctx)
    device_profile = probe_device(client, serial)
    device_dir = _find_device_dir(device_profile.model)
    if device_dir is None:
        console.print(f"[red]No device profile for '{device_profile.model}'; nothing to plan.[/red]")
        return
    db = load_database(device_dir)
    local_dir = _local_dir(device_profile)

    baseline_states, warning = _resolve_baseline(
        client, serial, local_dir, device_profile, db, allow_create=False, allow_fallback_to_current=True
    )
    if warning:
        console.print(f"[yellow]{warning}[/yellow]")
    if baseline_states is None:
        return
    current_states = _current_states(client, db)
    transitions = plan_profile_transition(baseline_states, current_states, db, profile_name)  # type: ignore[arg-type]

    _render_header(device_profile, profile_name, local_dir)
    _render_changes(transitions)

    table = Table(title="Reasons")
    table.add_column("Package")
    table.add_column("Action")
    table.add_column("Reason")
    for item in transitions:
        table.add_row(item.package, item.action, item.reason)
    console.print(table)


@main.command()
@click.option(
    "--profile", "profile_name", required=True, type=click.Choice(["safe", "performance", "maximum"])
)
@click.option("--dry-run", is_flag=True, default=False)
@click.pass_context
def apply(ctx: click.Context, profile_name: str, dry_run: bool) -> None:
    """Apply a profile: full state-transition pipeline (device
    reconnaissance -> inventory -> baseline -> desired-state plan ->
    execute -> tuning -> verify -> journal -> record) — section 8."""
    client, serial = _connected_client(ctx)
    device_profile = probe_device(client, serial)
    device_dir = _find_device_dir(device_profile.model)
    if device_dir is None:
        console.print(f"[red]No device profile for '{device_profile.model}'; refusing to apply.[/red]")
        sys.exit(1)
    db = load_database(device_dir)
    local_dir = _local_dir(device_profile)

    # Section 4/7: a user must not be able to accidentally apply ReLite
    # for the first time with no rollback point, and an apply must not
    # proceed against a baseline that turns out to be unusable or
    # firmware-drifted. --dry-run never creates/modifies a snapshot, so
    # it falls back to previewing against current live state instead.
    baseline_states, warning = _resolve_baseline(
        client, serial, local_dir, device_profile, db,
        allow_create=not dry_run, allow_fallback_to_current=dry_run,
    )
    if warning:
        c = "yellow" if baseline_states else "red"
        console.print(f"[{c}]{warning}[/{c}]")
    if baseline_states is None:
        sys.exit(1)

    current_states = _current_states(client, db)
    transitions = plan_profile_transition(baseline_states, current_states, db, profile_name)  # type: ignore[arg-type]

    _render_header(device_profile, profile_name, local_dir)
    _render_changes(transitions)
    console.print(f"Protected packages: verified ({len(db.protected)} entries loaded)")
    console.print()

    console.print(f"Applying [{'dry-run' if dry_run else 'live'}]...")
    apply_id = new_apply_id()
    journal_path = local_dir / "actions.jsonl"
    records = apply_transitions(client, apply_id, profile_name, transitions, journal_path, dry_run=dry_run)
    ok = sum(1 for r in records if r.result == "ok" or (dry_run and r.result == "dry-run"))
    failed = [r for r in records if r.result not in ("ok", "dry-run")]
    console.print(f"[green]{ok}/{len(records)} package action(s) applied[/green] (apply_id {apply_id})")
    if failed:
        console.print(f"[yellow]{len(failed)} action(s) did not take effect (platform refused):[/yellow]")
        for r in failed:
            console.print(f"  - {r.package}: {r.result}")

    if not dry_run:
        previous_scale = read_animation_scale(client)
        apply_animation_profile(client, profile_name)
        target_scale = {key: load_profiles()[profile_name].animation_scale for key in ANIMATION_SCALE_KEYS}
        record_tuning_change(local_dir / "tuning.jsonl", apply_id, previous_scale, target_scale)
        console.print("Animation scale applied.")

        # Section 6/14: only mark the profile "active" if a fresh live
        # integrity check — package state AND managed tuning — confirms
        # it. A partial failure must not be recorded as a clean apply.
        post_installed = list_packages(client)
        integrity = check_profile_integrity(
            post_installed, db, profile_name, client=client, baseline_states=baseline_states  # type: ignore[arg-type]
        )
        state = record_profile_applied(
            local_dir / "state.json",
            profile_name,
            integrity.status,
            unexpected_failures=len(integrity.mismatches) + len(integrity.tuning_mismatches),
        )
        color = {"PASS": "green", "PASS_WITH_LIMITATIONS": "green", "DEGRADED": "yellow", "FAIL": "red"}
        c = color[integrity.status]
        console.print(f"Integrity after apply: [{c}]{integrity.status}[/{c}]")
        if state.active_profile:
            console.print(f"Active profile recorded: {profile_name}")
        else:
            console.print(
                f"[red]Profile NOT recorded as active — {len(integrity.mismatches)} unexpected "
                f"mismatch(es). Run 'relite status' for details.[/red]"
            )


@main.command()
@click.option(
    "--snapshot", "snapshot_name", default=None, help="Restore from a named snapshot instead of the journal."
)
@click.option(
    "--all", "restore_all", is_flag=True, default=False,
    help="Full restore: the recorded baseline snapshot (not a hardcoded 'stock').",
)
@click.option("--dry-run", is_flag=True, default=False)
@click.pass_context
def restore(ctx: click.Context, snapshot_name: str | None, restore_all: bool, dry_run: bool) -> None:
    """Roll back ReLite-managed changes.

    `--all` restores the *recorded* baseline (section 6/7) — whatever
    `relite apply` auto-created or the user explicitly set with
    `relite snapshot --name ... --set-baseline` — never a hardcoded
    "stock" filename. See `relite undo` for reversing only the most
    recent apply instead of returning to baseline.
    """
    client, serial = _connected_client(ctx)

    device_profile = probe_device(client, serial)
    local_dir = _local_dir(device_profile)

    if snapshot_name or restore_all:
        if snapshot_name:
            try:
                validate_local_name(snapshot_name)
            except ValidationError as exc:
                console.print(f"[red]{exc}[/red]")
                sys.exit(1)
            snap_path = _baseline_snapshot_path(local_dir, snapshot_name)
        else:
            state = load_state(local_dir / "state.json")
            if not state.baseline_snapshot:
                console.print(
                    "[red]No baseline snapshot recorded. Run 'relite apply' once (it creates one "
                    "automatically) or 'relite snapshot --name <name> --set-baseline' explicitly.[/red]"
                )
                sys.exit(1)
            snap_path = _baseline_snapshot_path(local_dir, state.baseline_snapshot)
            snapshot_name = state.baseline_snapshot

        validation = validate_baseline(snap_path, device_profile, _device_key(device_profile))
        if validation.status != "valid":
            if validation.ownership == OwnershipStatus.FIRMWARE_DIFFERENT:
                console.print(
                    f"[yellow]Baseline firmware differs from current firmware ({validation.detail}). "
                    "Restoring package/setting state anyway — it's still this device's own recorded "
                    "baseline, just possibly predating an OTA.[/yellow]"
                )
            elif validation.ownership == OwnershipStatus.DEVICE_MISMATCH:
                console.print(f"[red]{validation.detail}[/red]")
                sys.exit(1)
            elif validation.ownership == OwnershipStatus.LEGACY_OWNERSHIP_UNKNOWN:
                if restore_all:
                    # Section 11: `--all` resolves the baseline implicitly
                    # — never silently restore from an ownership-ambiguous
                    # pre-v0.3.0 snapshot just because it's the one on
                    # record. An explicit `--snapshot <name>` is the user
                    # deliberately choosing it, so that path proceeds below
                    # with a warning instead of refusing.
                    console.print(f"[red]{validation.detail}[/red]")
                    sys.exit(1)
                console.print(f"[yellow]{validation.detail}[/yellow]")
            else:
                console.print(f"[red]Snapshot not found or unusable ({validation.status}): {snap_path}[/red]")
                sys.exit(1)
        snap = validation.snapshot or Snapshot.load(snap_path)
        # restore_from_snapshot restores every ReLite-managed setting
        # (including Private DNS) to its exact pre-ReLite value — see
        # relite/snapshot.py:MANAGED_SETTINGS — never a blind "turn it
        # off", since the user may have configured Private DNS themselves
        # before ReLite ever ran.
        result = restore_from_snapshot(client, snap, dry_run=dry_run)
        if not dry_run:
            restore_status = "OK" if not result.errors else "PARTIAL"
            record_snapshot_restored(
                local_dir / "state.json", snapshot_name, status=restore_status, error_count=len(result.errors)
            )
            if result.errors:
                console.print(
                    f"[red]Restore completed with {len(result.errors)} unverified change(s) — "
                    "NOT a clean restore. See errors below.[/red]"
                )
    else:
        result = restore_from_journal(client, local_dir / "actions.jsonl", dry_run=dry_run)

    console.print(f"[green]{len(result.packages_restored)} package(s) restored[/green]")
    if result.settings_restored:
        console.print(f"[green]{len(result.settings_restored)} managed setting(s) restored[/green]")
    if result.errors:
        console.print(f"[yellow]{len(result.errors)} error(s):[/yellow]")
        for err in result.errors:
            console.print(f"  - {err}")


@main.command()
@click.option("--dry-run", is_flag=True, default=False)
@click.pass_context
def undo(ctx: click.Context, dry_run: bool) -> None:
    """Reverse the most recent `relite apply` transaction only — not the
    device's entire ReLite history (section 12). For that, use
    `relite restore --all`, which returns to the recorded baseline."""
    client, serial = _connected_client(ctx)
    device_profile = probe_device(client, serial)
    local_dir = _local_dir(device_profile)
    journal_path = local_dir / "actions.jsonl"

    apply_id = latest_apply_id(journal_path)
    if apply_id is None:
        console.print("[yellow]No apply transaction found in the journal — nothing to undo.[/yellow]")
        return

    result = restore_from_journal(client, journal_path, apply_id=apply_id, dry_run=dry_run)
    console.print(f"Undoing apply_id {apply_id}...")
    console.print(f"[green]{len(result.packages_restored)} package(s) reverted[/green]")

    # Section 4: undo is a complete transaction — packages AND the tuning
    # (animation scale) this same apply changed, not packages alone.
    tuning_errors: list[str] = []
    previous_scale = load_tuning_change(local_dir / "tuning.jsonl", apply_id)
    if previous_scale:
        for key, value in previous_scale.items():
            setting_result = restore_managed_setting(client, "global", key, value, dry_run=dry_run)
            if setting_result.verified:
                console.print(f"[green]Tuning reverted: {key} -> {value}[/green]")
            else:
                tuning_errors.append(
                    f"{key}: restore not verified (requested {value!r}, observed {setting_result.observed!r})"
                )

    all_errors = result.errors + tuning_errors
    if all_errors:
        console.print(f"[yellow]{len(all_errors)} error(s):[/yellow]")
        for err in all_errors:
            console.print(f"  - {err}")

    if not dry_run:
        # Section 13: after undoing, the device is no longer cleanly "on"
        # whatever profile that apply had recorded — never leave a stale
        # active_profile pointing at a state that's been partially reverted.
        clear_active_profile(local_dir / "state.json")
        # Section 6: the recorded undo outcome is a verified re-query
        # result (packages via restore_from_journal's own live re-check,
        # tuning via the read-back above), not just "commands exited zero".
        undo_status = "OK" if not all_errors else "PARTIAL"
        record_undo_result(local_dir / "state.json", apply_id, status=undo_status)
        console.print("[yellow]Active profile cleared — device state is now CUSTOM/undone, "
                      "not cleanly on any recorded profile. Run 'relite status'.[/yellow]")


@main.command()
@click.pass_context
def status(ctx: click.Context) -> None:
    """Show the active profile and verify the device's live package state
    actually matches what that profile should have produced."""
    client, serial = _connected_client(ctx)
    device_profile = probe_device(client, serial)
    device_dir = _find_device_dir(device_profile.model)
    local_dir = _local_dir(device_profile)
    state = load_state(local_dir / "state.json")
    snapshot_exists = default_snapshot_dir(local_dir).exists()

    console.print(f"[bold]ReLite status — {device_profile.model}[/bold]")
    console.print()
    console.print("Active profile:")
    if state.active_profile:
        label = _profile_label(state.active_profile)
        console.print(f"  {state.active_profile}  ({label})  (applied {state.applied_at})")
    else:
        console.print("  none recorded — stock, or restored via 'relite restore' since the last apply")
        if state.last_apply_status and state.last_apply_status not in ("PASS", "PASS_WITH_LIMITATIONS"):
            console.print(
                f"  [red]Last apply attempt ({state.last_apply_profile}) did not complete cleanly: "
                f"{state.last_apply_status}, {state.last_apply_unexpected_failures} unexpected "
                f"mismatch(es)[/red]"
            )
    console.print()

    if device_dir is None:
        console.print(f"[yellow]No device profile for '{device_profile.model}'; "
                       "cannot verify integrity.[/yellow]")
        return

    db: ClassificationDatabase = load_database(device_dir)
    installed = list_packages(client)

    if state.active_profile:
        # Section 3 (v0.4.0): a "keep" decision only means something once
        # we know each package's pre-ReLite baseline state — resolve and
        # validate the baseline *before* claiming any integrity verdict,
        # exactly like `apply` does, rather than only checking package
        # state (section 2's baseline-aware `desired_state_for`) blind to
        # whether that baseline is even known/valid for this device.
        baseline_states, baseline_warning = _resolve_baseline(
            client, serial, local_dir, device_profile, db,
            allow_create=False, allow_fallback_to_current=False,
        )
        if baseline_states is None:
            console.print("Profile integrity:")
            console.print(f"  [yellow]PROFILE_STATE_UNKNOWN[/yellow] — {baseline_warning}")
            console.print(
                "  Package state alone can't be verified without a valid baseline "
                "('keep' means 'whatever it was before ReLite'). Run "
                "'relite snapshot --name <n> --set-baseline' to re-establish one."
            )
            console.print()
            report = None
        else:
            report = check_profile_integrity(
                installed, db, state.active_profile, client=client, baseline_states=baseline_states  # type: ignore[arg-type]
            )
    else:
        report = None

    if report is not None:
        console.print("Profile integrity:")
        color = {"PASS": "green", "PASS_WITH_LIMITATIONS": "green", "DEGRADED": "yellow", "FAIL": "red"}
        console.print(f"  [{color[report.status]}]{report.status}[/{color[report.status]}]")
        console.print(f"  Packages checked:  {report.total_checked}")
        console.print(f"  Currently disabled (of checked): {report.disabled_count}")
        if report.degraded:
            console.print(f"  Degraded ({len(report.degraded)}, functionally satisfied but not literal):")
            for d in report.degraded:
                console.print(f"    - {d.package}: wanted {d.expected}, got {d.observed}")
        if report.mismatches:
            console.print(f"  Mismatches ({len(report.mismatches)}):")
            for m in report.mismatches:
                console.print(f"    - {m.package}: expected {m.expected}, observed {m.observed}")
        if report.tuning_mismatches:
            console.print(f"  Tuning mismatches ({len(report.tuning_mismatches)}):")
            for t in report.tuning_mismatches:
                console.print(f"    - {t.key}: expected {t.expected}, observed {t.observed}")
        if report.known_limitations:
            console.print(f"  Known platform limitations ({len(report.known_limitations)}, not failures):")
            for lim in report.known_limitations:
                console.print(f"    - {lim.package}: {lim.reason}")
        console.print()

    console.print("Rollback:")
    console.print(f"  Snapshot on disk: {'yes' if snapshot_exists else 'no'}")
    console.print(f"  Baseline snapshot: {state.baseline_snapshot or 'none recorded'}")
    if state.baseline_snapshot:
        baseline_path = _baseline_snapshot_path(local_dir, state.baseline_snapshot)
        validation = validate_baseline(baseline_path, device_profile, _device_key(device_profile))
        if validation.ownership == OwnershipStatus.FIRMWARE_DIFFERENT:
            console.print(
                f"  [red]Baseline firmware differs from current firmware[/red] — {validation.detail}"
            )
        elif validation.status != "valid":
            console.print(f"  [yellow]Baseline snapshot is unusable: {validation.status}[/yellow]")
    console.print(f"  Last snapshot restored: {state.last_snapshot or 'none'}")
    console.print(f"  Action journal: {'present' if (local_dir / 'actions.jsonl').exists() else 'empty'}")


@main.command()
@click.argument("setting", type=click.Choice(["ram-expansion"]))
@click.argument("value", type=click.Choice(["off", "on", "probe"]))
@click.pass_context
def tune(ctx: click.Context, setting: str, value: str) -> None:
    """Apply device tuning outside the package system, e.g. `relite tune ram-expansion probe`."""
    client, _serial = _connected_client(ctx)
    if setting == "ram-expansion":
        probe = probe_ram_expansion(client)
        if not probe.detected:
            console.print("[yellow]No RAM Expansion setting key detected on this device. "
                           "See docs/manual-actions.md for the manual UI path.[/yellow]")
            return
        console.print(f"Detected RAM Expansion keys: {probe.found_keys}")
        if value == "probe":
            return
        console.print("[yellow]A confirmed key was found but automatic write is intentionally "
                       "not wired up until a specific device's key is validated in "
                       "devices/<oem>/<model>/findings.md.[/yellow]")


@main.command(name="network-adblock")
@click.option("--hostname", default=None, help="Private DNS hostname to enable.")
@click.option("--disable", "disable_flag", is_flag=True, default=False)
@click.pass_context
def network_adblock(ctx: click.Context, hostname: str | None, disable_flag: bool) -> None:
    """Configure Android Private DNS for optional network-level ad blocking."""
    client, _serial = _connected_client(ctx)
    if disable_flag:
        set_private_dns(client, None)
        console.print("Private DNS disabled.")
        return
    if not hostname:
        console.print("[red]--hostname is required unless --disable is passed. "
                       "ReLite does not default to a third-party DNS provider.[/red]")
        sys.exit(1)
    ok = set_private_dns(client, hostname)
    if ok:
        console.print(f"[green]Private DNS set to {hostname}[/green]. "
                       "Note: this may not block first-party ads and can affect captive portals.")
    else:
        console.print("[red]Failed to set Private DNS.[/red]")
        sys.exit(1)


@main.command()
@click.option("--label", required=True, help="Label for this benchmark run, e.g. 'stock' or 'safe'.")
@click.option("--runs", default=5, show_default=True, help="Iterations per app start measurement.")
@click.option("--skip-apps", is_flag=True, default=False, help="Skip app start/PSS measurements.")
@click.pass_context
def benchmark(ctx: click.Context, label: str, runs: int, skip_apps: bool) -> None:
    """Run the benchmark harness and store a labeled result.

    App start/PSS targets come from the device's device.yaml
    (`benchmark_targets` / `pss_targets`) — see docs/development.md.
    """
    import json as _json

    from relite.benchmark import RECOMMENDED_MIN_RUNS, run_benchmark
    from relite.device_metadata import load_device_metadata

    if runs < 1:
        console.print(f"[red]--runs must be >= 1, got {runs}.[/red]")
        sys.exit(1)
    if runs < RECOMMENDED_MIN_RUNS:
        console.print(
            f"[yellow]--runs {runs} is below the recommended minimum of "
            f"{RECOMMENDED_MIN_RUNS} for published measurements.[/yellow]"
        )

    client, serial = _connected_client(ctx)
    device_profile = probe_device(client, serial)
    device_dir = _find_device_dir(device_profile.model)

    app_targets: list[dict[str, str]] = []
    pss_targets: list[dict[str, str]] = []
    if device_dir and not skip_apps:
        device_yaml_path = device_dir / "device.yaml"
        if device_yaml_path.exists():
            metadata = load_device_metadata(device_yaml_path)
            app_targets = [t.to_dict() for t in metadata.benchmark_targets]
            pss_targets = [t.to_dict() for t in metadata.pss_targets]

    console.print(f"Running benchmark '{label}' ({len(app_targets)} app target(s), "
                  f"{len(pss_targets)} PSS target(s), {runs} run(s) each)...")
    result = run_benchmark(client, label, app_targets=app_targets, pss_targets=pss_targets, runs=runs)

    out_dir = Path("benchmarks/results") / device_profile.model
    out_dir.mkdir(parents=True, exist_ok=True)
    out_path = out_dir / f"{label}.json"
    out_path.write_text(_json.dumps(result.to_dict(), indent=2, sort_keys=True) + "\n")
    console.print(f"[green]Benchmark '{label}' saved to {out_path}[/green]")
    if result.measurement_failures:
        console.print(
            f"[yellow]{len(result.measurement_failures)} measurement(s) produced no valid sample "
            f"(not recorded as 0 — see 'measurement_failures' in the saved result):[/yellow]"
        )
        for failure in result.measurement_failures:
            console.print(f"  - {failure}")


@main.command()
@click.option(
    "--label-a", default="launcher", show_default=True, help="pss_targets label for the first launcher."
)
@click.option("--label-b", default="relite_home", show_default=True, help="pss_targets label for the second.")
@click.option("--runs", default=3, show_default=True, help="Alternating samples per launcher.")
@click.pass_context
def benchmark_launchers(ctx: click.Context, label_a: str, label_b: str, runs: int) -> None:
    """Section 16 (v0.4.0): controlled, single-session, alternating-order
    A/B settled-PSS comparison between two launchers (e.g. stock vs.
    ReLite Home) — the library-only `run_launcher_ab_benchmark()` exposed
    as an actual command instead of something only a Python caller could
    reach, so a controlled result can actually be produced and published
    (section 81 of this plan / section 40 of v0.3.0's) instead of relying
    on two separately-timed sessions.

    Targets come from device.yaml's `pss_targets` (both must have an
    `activity` — an ambient-only target like systemui can't be cold-
    started for a fair comparison).
    """
    import json as _json

    from relite.benchmark import RECOMMENDED_MIN_RUNS, run_launcher_ab_benchmark
    from relite.device_metadata import load_device_metadata

    if runs < 1:
        console.print(f"[red]--runs must be >= 1, got {runs}.[/red]")
        sys.exit(1)
    if runs < RECOMMENDED_MIN_RUNS:
        console.print(
            f"[yellow]--runs {runs} is below the recommended minimum of "
            f"{RECOMMENDED_MIN_RUNS} for a published comparison.[/yellow]"
        )

    client, serial = _connected_client(ctx)
    device_profile = probe_device(client, serial)
    device_dir = _find_device_dir(device_profile.model)
    if device_dir is None:
        console.print(f"[red]No device profile for '{device_profile.model}'.[/red]")
        sys.exit(1)
    device_yaml_path = device_dir / "device.yaml"
    if not device_yaml_path.exists():
        console.print(f"[red]{device_yaml_path} not found.[/red]")
        sys.exit(1)
    metadata = load_device_metadata(device_yaml_path)
    by_label = {t.label: t for t in metadata.pss_targets}
    missing = [label for label in (label_a, label_b) if label not in by_label]
    if missing:
        console.print(f"[red]pss_targets missing label(s) {missing} in {device_yaml_path}.[/red]")
        sys.exit(1)
    target_a, target_b = by_label[label_a], by_label[label_b]
    if target_a.activity is None or target_b.activity is None:
        console.print("[red]Both targets need an 'activity' for a cold-start A/B comparison.[/red]")
        sys.exit(1)

    console.print(f"Running controlled A/B: {label_a} vs {label_b}, {runs} alternating sample(s) each...")
    result = run_launcher_ab_benchmark(client, target_a.to_dict(), target_b.to_dict(), runs_each=runs)

    out_dir = Path("benchmarks/results") / device_profile.model
    out_dir.mkdir(parents=True, exist_ok=True)
    out_path = out_dir / f"ab-{label_a}-vs-{label_b}.json"
    out_path.write_text(_json.dumps(result.to_dict(), indent=2, sort_keys=True) + "\n")
    console.print(f"[green]Saved: {out_path}[/green]")

    stats_a = result.stats_for(label_a)
    stats_b = result.stats_for(label_b)
    console.print(f"{label_a}: median {stats_a.median:,.0f} kB (n={len(stats_a.samples_kb)})")
    console.print(f"{label_b}: median {stats_b.median:,.0f} kB (n={len(stats_b.samples_kb)})")
    if stats_a.median > 0:
        change = (stats_b.median - stats_a.median) / stats_a.median * 100
        console.print(f"{label_b} vs {label_a}: {change:+.1f}%")


@main.command()
@click.pass_context
def report(ctx: click.Context) -> None:
    """Render a Markdown/JSON/CSV comparison report from all saved benchmark results."""
    from relite.benchmark import BenchmarkResult, PssStats, TimingStats

    client, serial = _connected_client(ctx)
    device_profile = probe_device(client, serial)
    results_dir = Path("benchmarks/results") / device_profile.model
    if not results_dir.exists():
        console.print(f"[yellow]No benchmark results found under {results_dir}[/yellow]")
        return

    # Canonical profile progression so the report's baseline/comparison
    # columns read left-to-right as stock -> safe -> performance ->
    # maximum, not alphabetical (which would put "maximum" first).
    label_order = {"stock": 0, "safe": 1, "performance": 2, "maximum": 3}

    def sort_key(path: Path) -> tuple[int, str]:
        return (label_order.get(path.stem, len(label_order)), path.stem)

    results = []
    for path in sorted(results_dir.glob("*.json"), key=sort_key):
        if path.name in ("latest.json",):
            continue
        import json as _json
        data = _json.loads(path.read_text())
        # Section 15: a v0.3.0-and-earlier saved result only has flat
        # `pss_kb` (single int per label) — loaded as a one-sample
        # PssStats rather than refusing to render older results at all.
        if "pss" in data:
            pss = {k: PssStats(v["samples_kb"]) for k, v in data["pss"].items()}
        else:
            pss = {k: PssStats([v]) for k, v in data.get("pss_kb", {}).items()}
        results.append(
            BenchmarkResult(
                label=data["label"],
                enabled_packages=data["enabled_packages"],
                disabled_packages=data["disabled_packages"],
                system_packages=data["system_packages"],
                meminfo=data["meminfo"],
                app_start_times={
                    k: TimingStats(v["samples_ms"]) for k, v in data.get("app_start_times", {}).items()
                },
                app_warm_start_times={
                    k: TimingStats(v["samples_ms"]) for k, v in data.get("app_warm_start_times", {}).items()
                },
                pss=pss,
            )
        )

    paths = write_report(results_dir, device_profile.model, device_profile.fingerprint, results)
    console.print(f"[green]Report written:[/green] {paths['markdown']}")


if __name__ == "__main__":
    main()
