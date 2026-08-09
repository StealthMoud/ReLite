"""Full stock/point-in-time device snapshots (packages, settings, animation
scale, etc.), with schema versioning so old snapshots stay readable.
"""

from __future__ import annotations

import json
from dataclasses import dataclass, field
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

from relite.adb import AdbClient
from relite.classifier import ClassificationDatabase
from relite.device import DeviceProfile, probe_device
from relite.package_state import PackageState, state_of
from relite.packages import PackageInfo, list_packages
from relite.sanitize import sanitize_dict

SCHEMA_VERSION = 2
SUPPORTED_SCHEMAS = (1, 2)

SETTINGS_NAMESPACES = ("global", "secure", "system")

ANIMATION_KEYS = (
    "window_animation_scale",
    "transition_animation_scale",
    "animator_duration_scale",
)

# Every (namespace, key) ReLite ever writes to. Restore must handle exactly
# this set and nothing else — see docs/profiles.md and tuning.py for the
# policy of never blindly rewriting settings ReLite doesn't own.
MANAGED_SETTINGS: tuple[tuple[str, str], ...] = (
    ("global", "window_animation_scale"),
    ("global", "transition_animation_scale"),
    ("global", "animator_duration_scale"),
    ("global", "private_dns_mode"),
    ("global", "private_dns_specifier"),
)


@dataclass
class Snapshot:
    schema: int
    name: str
    created_at: str
    device: DeviceProfile
    packages: dict[str, PackageInfo] = field(default_factory=dict)
    settings: dict[str, dict[str, str]] = field(default_factory=dict)
    # Section 4 (v0.3.0): explicit baseline PackageState for every package
    # ReLite is *capable* of modifying (a device's classification
    # database), not every package on the phone — that would be most of
    # the OS for no benefit. Values are PackageState.value strings.
    # Only populated for schema >= 2; see `baseline_states()` for how a
    # schema-1 snapshot (or a schema-2 one predating a device's current
    # classification database) falls back to deriving the same
    # information from the full `packages` inventory instead.
    managed_package_states: dict[str, str] = field(default_factory=dict)

    def to_dict(self) -> dict[str, Any]:
        return {
            "schema": self.schema,
            "name": self.name,
            "created_at": self.created_at,
            "device": self.device.to_dict(),
            "packages": {name: pkg.to_dict() for name, pkg in self.packages.items()},
            "settings": self.settings,
            "managed_package_states": self.managed_package_states,
        }

    @classmethod
    def from_dict(cls, data: dict[str, Any]) -> Snapshot:
        schema = data.get("schema", 1)
        if schema not in SUPPORTED_SCHEMAS:
            raise ValueError(f"unsupported snapshot schema {schema}, expected one of {SUPPORTED_SCHEMAS}")
        return cls(
            schema=schema,
            name=data["name"],
            created_at=data["created_at"],
            device=DeviceProfile.from_dict(data["device"]),
            packages={
                name: PackageInfo.from_dict(pkg) for name, pkg in data.get("packages", {}).items()
            },
            settings=data.get("settings", {}),
            managed_package_states=dict(data.get("managed_package_states", {})),
        )

    def animation_scales(self) -> dict[str, str]:
        system = self.settings.get("global", {})
        return {key: system.get(key, "1.0") for key in ANIMATION_KEYS}

    def baseline_states(self, db: ClassificationDatabase) -> dict[str, PackageState]:
        """The pre-ReLite `PackageState` of every package `db` classifies,
        for use as a `profile_planner` baseline.

        Prefers the snapshot's own recorded `managed_package_states`
        (schema 2+, and only for packages present there — a device's
        classification database can grow between when a snapshot was
        taken and when it's used, so packages missing from an otherwise
        valid schema-2 snapshot still fall back to the inventory below).
        A schema-1 snapshot has no `managed_package_states` at all, so
        every classified package falls back — this is the v1-in-memory
        migration the schema bump promised: old snapshot files keep
        working, not just keep loading.
        """
        states: dict[str, PackageState] = {}
        for name in db.entries:
            recorded = self.managed_package_states.get(name)
            if recorded is not None:
                states[name] = PackageState(recorded)
            else:
                states[name] = state_of(self.packages.get(name))
        return states

    def save(self, path: Path, *, sanitize: bool = True) -> None:
        data = self.to_dict()
        if sanitize:
            data = sanitize_dict(data)
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(data, indent=2, sort_keys=True) + "\n")

    @classmethod
    def load(cls, path: Path) -> Snapshot:
        return cls.from_dict(json.loads(path.read_text()))


def _read_settings(scoped: AdbClient, namespace: str) -> dict[str, str]:
    result = scoped.shell(f"settings list {namespace}")
    values: dict[str, str] = {}
    for line in result.lines():
        if "=" not in line:
            continue
        key, _, value = line.partition("=")
        values[key.strip()] = value.strip()
    return values


def take_snapshot(
    client: AdbClient, serial: str, name: str, db: ClassificationDatabase | None = None
) -> Snapshot:
    """Take a full point-in-time snapshot. When `db` is supplied (the
    device's classification database), `managed_package_states` is
    populated for every package it classifies — this is what makes the
    snapshot usable as a `profile_planner` baseline. Without `db`, the
    snapshot is still complete (full package inventory + settings) but
    `managed_package_states` is left empty; `Snapshot.baseline_states()`
    derives the same information from the inventory on demand either way.
    """
    scoped = AdbClient(serial=serial, adb_path=client.adb_path, runner=client.runner)
    device = probe_device(client, serial)
    packages = {pkg.name: pkg for pkg in list_packages(scoped)}
    settings = {ns: _read_settings(scoped, ns) for ns in SETTINGS_NAMESPACES}
    managed_package_states: dict[str, str] = {}
    if db is not None:
        for pkg_name in db.entries:
            managed_package_states[pkg_name] = state_of(packages.get(pkg_name)).value
    return Snapshot(
        schema=SCHEMA_VERSION,
        name=name,
        created_at=datetime.now(UTC).isoformat(),
        device=device,
        packages=packages,
        settings=settings,
        managed_package_states=managed_package_states,
    )


def default_snapshot_dir(device_local_dir: Path) -> Path:
    """`device_local_dir` is the per-physical-device root, e.g. from
    `relite.device_identity.device_local_dir()` — never a bare model name,
    so two units of the same model never share a snapshot directory."""
    return device_local_dir / "snapshots"
