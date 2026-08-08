"""Package inventory: enumerate installed packages and their runtime state.

Collects what section 8 of the master plan asks for, to the extent it's
reachable without root: enabled/disabled/system state from `pm`, and
manifest-derived details from `dumpsys package`.
"""

from __future__ import annotations

import re
from dataclasses import dataclass, field
from typing import Any

from relite.adb import AdbClient

_PM_FLAGS = {
    "-f": "system",  # path + package, unfiltered marker handled separately
    "-s": "system",
    "-3": "third_party",
    "-d": "disabled",
    "-e": "enabled",
}


@dataclass
class PackageInfo:
    name: str
    system: bool = False
    third_party: bool = False
    enabled: bool = True
    disabled: bool = False
    apk_path: str = ""
    version_name: str = ""
    installer: str = ""
    permissions: list[str] = field(default_factory=list)
    services: list[str] = field(default_factory=list)
    receivers: list[str] = field(default_factory=list)
    persistent: bool = False

    def to_dict(self) -> dict[str, Any]:
        return {
            "name": self.name,
            "system": self.system,
            "third_party": self.third_party,
            "enabled": self.enabled,
            "disabled": self.disabled,
            "apk_path": self.apk_path,
            "version_name": self.version_name,
            "installer": self.installer,
            "permissions": self.permissions,
            "services": self.services,
            "receivers": self.receivers,
            "persistent": self.persistent,
        }

    @classmethod
    def from_dict(cls, data: dict[str, Any]) -> PackageInfo:
        return cls(
            name=data["name"],
            system=data.get("system", False),
            third_party=data.get("third_party", False),
            enabled=data.get("enabled", True),
            disabled=data.get("disabled", False),
            apk_path=data.get("apk_path", ""),
            version_name=data.get("version_name", ""),
            installer=data.get("installer", ""),
            permissions=list(data.get("permissions", [])),
            services=list(data.get("services", [])),
            receivers=list(data.get("receivers", [])),
            persistent=data.get("persistent", False),
        )


def _parse_pm_list(output: str) -> set[str]:
    names = set()
    for line in output.splitlines():
        line = line.strip()
        if not line.startswith("package:"):
            continue
        # `pm list packages -f` emits `package:/path/to.apk=com.pkg.name`
        value = line[len("package:") :]
        name = value.rsplit("=", 1)[-1]
        names.add(name)
    return names


def list_packages(client: AdbClient) -> list[PackageInfo]:
    """Enumerate all packages with their system/enabled/disabled flags."""
    all_names = _parse_pm_list(client.shell("pm list packages").stdout)
    system_names = _parse_pm_list(client.shell("pm list packages -s").stdout)
    third_party_names = _parse_pm_list(client.shell("pm list packages -3").stdout)
    disabled_names = _parse_pm_list(client.shell("pm list packages -d").stdout)
    enabled_names = _parse_pm_list(client.shell("pm list packages -e").stdout)

    packages = []
    for name in sorted(all_names):
        packages.append(
            PackageInfo(
                name=name,
                system=name in system_names,
                third_party=name in third_party_names,
                enabled=name in enabled_names or name not in disabled_names,
                disabled=name in disabled_names,
            )
        )
    return packages


_VERSION_RE = re.compile(r"versionName=(\S+)")
_INSTALLER_RE = re.compile(r"installerPackageName=(\S+)")
_PERMISSION_RE = re.compile(r"^\s+(android\.permission\.\S+|com\.\S+\.permission\.\S+):", re.MULTILINE)
_SERVICE_RE = re.compile(r"Service\{[^}]*\s([\w.]+/[\w.$]+)\}")
_RECEIVER_RE = re.compile(r"Receiver\{[^}]*\s([\w.]+/[\w.$]+)\}")


def enrich_package(client: AdbClient, pkg: PackageInfo) -> PackageInfo:
    """Fill in manifest-derived detail for a single package via `dumpsys package`."""
    dump = client.shell(f"dumpsys package {pkg.name}").stdout
    path_result = client.shell(f"pm path {pkg.name}")
    if path_result.ok and path_result.stdout.strip().startswith("package:"):
        pkg.apk_path = path_result.stdout.strip()[len("package:") :].splitlines()[0]

    if match := _VERSION_RE.search(dump):
        pkg.version_name = match.group(1)
    if match := _INSTALLER_RE.search(dump):
        pkg.installer = match.group(1)

    pkg.permissions = sorted(set(_PERMISSION_RE.findall(dump)))
    pkg.services = sorted(set(_SERVICE_RE.findall(dump)))
    pkg.receivers = sorted(set(_RECEIVER_RE.findall(dump)))
    pkg.persistent = "persistent=true" in dump.replace(" ", "").lower() or "flags=[ PERSISTENT" in dump
    return pkg
