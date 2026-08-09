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

import shutil
import sys
from pathlib import Path

import click
from rich.console import Console
from rich.table import Table

from relite import __version__
from relite.actions import PlannedAction, apply_plan, build_plan
from relite.adb import AdbClient, AdbUnavailableError
from relite.classifier import ClassificationDatabase, load_database
from relite.device import DeviceProfile, probe_device
from relite.device_identity import default_local_root, device_local_dir, migrate_legacy_layout
from relite.packages import list_packages
from relite.profiles import load_profiles
from relite.report import write_report
from relite.restore import restore_from_journal, restore_from_snapshot
from relite.snapshot import Snapshot, default_snapshot_dir, take_snapshot
from relite.state import check_profile_integrity, load_state, record_profile_applied, record_snapshot_restored
from relite.tuning import apply_animation_profile, probe_ram_expansion, set_private_dns

console = Console()

DEVICES_ROOT = Path("devices")


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


def _render_changes(actions: list[PlannedAction]) -> None:
    by_action: dict[str, list[PlannedAction]] = {"disable": [], "uninstall-user": []}
    for item in actions:
        by_action.setdefault(item.action, []).append(item)

    console.print("Changes:")
    if by_action["disable"]:
        console.print(f"  Disable ({len(by_action['disable'])}):")
        for item in by_action["disable"]:
            console.print(f"    - {item.package}")
    if by_action["uninstall-user"]:
        console.print(f"  Uninstall for user 0, reversible ({len(by_action['uninstall-user'])}):")
        for item in by_action["uninstall-user"]:
            console.print(f"    - {item.package}")
    if not actions:
        console.print("  (none — device already matches this profile)")
    console.print()


@click.group()
@click.version_option(version=__version__, prog_name="relite")
@click.option(
    "--serial", default=None, help="Target a specific ADB serial when multiple devices are attached."
)
@click.pass_context
def main(ctx: click.Context, serial: str | None) -> None:
    """ReLite — make Android lighter without replacing the hardware layer."""
    ctx.ensure_object(dict)
    ctx.obj["serial"] = serial


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
@click.pass_context
def snapshot(ctx: click.Context, name: str) -> None:
    """Take a full snapshot of the current device state."""
    client, serial = _connected_client(ctx)
    console.print("Collecting device snapshot (packages, settings, props)...")
    snap = take_snapshot(client, serial, name)
    local_dir = _local_dir(snap.device)
    out_dir = default_snapshot_dir(local_dir)
    out_path = out_dir / f"{name}.snapshot.json"
    snap.save(out_path)
    console.print(f"[green]Saved snapshot to {out_path}[/green] ({len(snap.packages)} packages)")


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
    """Print the plan of actions a profile would apply, without applying it."""
    client, serial = _connected_client(ctx)
    device_profile = probe_device(client, serial)
    device_dir = _find_device_dir(device_profile.model)
    if device_dir is None:
        console.print(f"[red]No device profile for '{device_profile.model}'; nothing to plan.[/red]")
        return
    db = load_database(device_dir)
    installed = list_packages(client)
    actions = build_plan(installed, db, profile_name)  # type: ignore[arg-type]

    _render_header(device_profile, profile_name, _local_dir(device_profile))
    _render_changes(actions)

    table = Table(title="Reasons")
    table.add_column("Package")
    table.add_column("Action")
    table.add_column("Reason")
    for item in actions:
        table.add_row(item.package, item.action, item.reason)
    console.print(table)


@main.command()
@click.option(
    "--profile", "profile_name", required=True, type=click.Choice(["safe", "performance", "maximum"])
)
@click.option("--dry-run", is_flag=True, default=False)
@click.pass_context
def apply(ctx: click.Context, profile_name: str, dry_run: bool) -> None:
    """Apply a performance profile: package actions + animation scale."""
    client, serial = _connected_client(ctx)
    device_profile = probe_device(client, serial)
    device_dir = _find_device_dir(device_profile.model)
    if device_dir is None:
        console.print(f"[red]No device profile for '{device_profile.model}'; refusing to apply.[/red]")
        sys.exit(1)
    db = load_database(device_dir)
    installed = list_packages(client)
    actions = build_plan(installed, db, profile_name)  # type: ignore[arg-type]
    local_dir = _local_dir(device_profile)

    _render_header(device_profile, profile_name, local_dir)
    _render_changes(actions)
    console.print(f"Protected packages: verified ({len(db.protected)} entries loaded)")
    console.print()

    # Section 4: a user must not be able to accidentally apply ReLite for
    # the first time with no rollback point. --dry-run never creates or
    # modifies snapshots.
    snapshot_dir = default_snapshot_dir(local_dir)
    if not dry_run and not snapshot_dir.exists():
        console.print("No baseline snapshot found — creating one automatically before any change...")
        auto_snap = take_snapshot(client, serial, "auto-pre-relite")
        auto_snap_path = snapshot_dir / "auto-pre-relite.snapshot.json"
        auto_snap.save(auto_snap_path)
        console.print(f"[green]Safety snapshot created: {auto_snap_path}[/green]")
        console.print()

    console.print(f"Applying [{'dry-run' if dry_run else 'live'}]...")
    records = apply_plan(client, actions, local_dir / "actions.jsonl", dry_run=dry_run)
    ok = sum(1 for r in records if r.result == "ok" or (dry_run and r.result == "dry-run"))
    failed = [r for r in records if r.result not in ("ok", "dry-run")]
    console.print(f"[green]{ok}/{len(records)} package action(s) applied[/green]")
    if failed:
        console.print(f"[yellow]{len(failed)} action(s) did not take effect (platform refused):[/yellow]")
        for r in failed:
            console.print(f"  - {r.package}: {r.result}")

    if not dry_run:
        apply_animation_profile(client, profile_name)
        console.print("Animation scale applied.")

        # Section 6: only mark the profile "active" if a fresh live
        # integrity check confirms it — a partial failure must not be
        # recorded as a clean apply.
        post_installed = list_packages(client)
        integrity = check_profile_integrity(post_installed, db, profile_name)  # type: ignore[arg-type]
        state = record_profile_applied(
            local_dir / "state.json",
            profile_name,
            integrity.status,
            unexpected_failures=len(integrity.mismatches),
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
    "--all", "restore_all", is_flag=True, default=False, help="Full restore: snapshot + journal + tuning."
)
@click.option("--dry-run", is_flag=True, default=False)
@click.pass_context
def restore(ctx: click.Context, snapshot_name: str | None, restore_all: bool, dry_run: bool) -> None:
    """Roll back ReLite-managed changes."""
    client, serial = _connected_client(ctx)

    device_profile = probe_device(client, serial)
    local_dir = _local_dir(device_profile)

    if snapshot_name or restore_all:
        snap_name = snapshot_name or "stock"
        snap_path = default_snapshot_dir(local_dir) / f"{snap_name}.snapshot.json"
        if not snap_path.exists():
            console.print(f"[red]Snapshot not found: {snap_path}[/red]")
            sys.exit(1)
        snap = Snapshot.load(snap_path)
        # restore_from_snapshot restores every ReLite-managed setting
        # (including Private DNS) to its exact pre-ReLite value — see
        # relite/snapshot.py:MANAGED_SETTINGS — never a blind "turn it
        # off", since the user may have configured Private DNS themselves
        # before ReLite ever ran.
        result = restore_from_snapshot(client, snap, dry_run=dry_run)
        if not dry_run:
            record_snapshot_restored(local_dir / "state.json", snap_name)
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
        report = check_profile_integrity(installed, db, state.active_profile)  # type: ignore[arg-type]
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
        if report.known_limitations:
            console.print(f"  Known platform limitations ({len(report.known_limitations)}, not failures):")
            for lim in report.known_limitations:
                console.print(f"    - {lim.package}: {lim.reason}")
        console.print()

    console.print("Rollback:")
    console.print(f"  Snapshot on disk: {'yes' if snapshot_exists else 'no'}")
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

    import yaml

    from relite.benchmark import run_benchmark

    client, serial = _connected_client(ctx)
    device_profile = probe_device(client, serial)
    device_dir = _find_device_dir(device_profile.model)

    app_targets: list[dict[str, str]] = []
    pss_targets: list[dict[str, str]] = []
    if device_dir and not skip_apps:
        device_yaml_path = device_dir / "device.yaml"
        if device_yaml_path.exists():
            device_yaml = yaml.safe_load(device_yaml_path.read_text()) or {}
            app_targets = device_yaml.get("benchmark_targets", [])
            pss_targets = device_yaml.get("pss_targets", [])

    console.print(f"Running benchmark '{label}' ({len(app_targets)} app target(s), "
                  f"{len(pss_targets)} PSS target(s), {runs} run(s) each)...")
    result = run_benchmark(client, label, app_targets=app_targets, pss_targets=pss_targets, runs=runs)

    out_dir = Path("benchmarks/results") / device_profile.model
    out_dir.mkdir(parents=True, exist_ok=True)
    out_path = out_dir / f"{label}.json"
    out_path.write_text(_json.dumps(result.to_dict(), indent=2, sort_keys=True) + "\n")
    console.print(f"[green]Benchmark '{label}' saved to {out_path}[/green]")


@main.command()
@click.pass_context
def report(ctx: click.Context) -> None:
    """Render a Markdown/JSON/CSV comparison report from all saved benchmark results."""
    from relite.benchmark import BenchmarkResult, TimingStats

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
                pss_kb=data.get("pss_kb", {}),
            )
        )

    paths = write_report(results_dir, device_profile.model, device_profile.fingerprint, results)
    console.print(f"[green]Report written:[/green] {paths['markdown']}")


if __name__ == "__main__":
    main()
