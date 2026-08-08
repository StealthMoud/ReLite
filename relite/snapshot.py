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
from relite.device import DeviceProfile, probe_device
from relite.packages import PackageInfo, list_packages
from relite.sanitize import sanitize_dict

SCHEMA_VERSION = 1

SETTINGS_NAMESPACES = ("global", "secure", "system")

ANIMATION_KEYS = (
    "window_animation_scale",
    "transition_animation_scale",
    "animator_duration_scale",
)


@dataclass
class Snapshot:
    schema: int
    name: str
    created_at: str
    device: DeviceProfile
    packages: dict[str, PackageInfo] = field(default_factory=dict)
    settings: dict[str, dict[str, str]] = field(default_factory=dict)

    def to_dict(self) -> dict[str, Any]:
        return {
            "schema": self.schema,
            "name": self.name,
            "created_at": self.created_at,
            "device": self.device.to_dict(),
            "packages": {name: pkg.to_dict() for name, pkg in self.packages.items()},
            "settings": self.settings,
        }

    @classmethod
    def from_dict(cls, data: dict[str, Any]) -> Snapshot:
        schema = data.get("schema", 1)
        if schema != SCHEMA_VERSION:
            raise ValueError(f"unsupported snapshot schema {schema}, expected {SCHEMA_VERSION}")
        return cls(
            schema=schema,
            name=data["name"],
            created_at=data["created_at"],
            device=DeviceProfile.from_dict(data["device"]),
            packages={
                name: PackageInfo.from_dict(pkg) for name, pkg in data.get("packages", {}).items()
            },
            settings=data.get("settings", {}),
        )

    def animation_scales(self) -> dict[str, str]:
        system = self.settings.get("global", {})
        return {key: system.get(key, "1.0") for key in ANIMATION_KEYS}

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


def take_snapshot(client: AdbClient, serial: str, name: str) -> Snapshot:
    scoped = AdbClient(serial=serial, adb_path=client.adb_path, runner=client.runner)
    device = probe_device(client, serial)
    packages = {pkg.name: pkg for pkg in list_packages(scoped)}
    settings = {ns: _read_settings(scoped, ns) for ns in SETTINGS_NAMESPACES}
    return Snapshot(
        schema=SCHEMA_VERSION,
        name=name,
        created_at=datetime.now(UTC).isoformat(),
        device=device,
        packages=packages,
        settings=settings,
    )


def default_snapshot_dir(device_model: str) -> Path:
    return Path(".local") / device_model / "snapshots"
