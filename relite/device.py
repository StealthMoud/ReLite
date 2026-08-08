"""Device reconnaissance: read-only probing of a connected Android device.

Nothing in this module writes to the device. See section 5 of the master
plan for the exact property/command list this mirrors.
"""

from __future__ import annotations

import re
from dataclasses import dataclass, field
from typing import Any

from relite.adb import AdbClient

BUILD_PROPS = [
    "ro.product.model",
    "ro.product.device",
    "ro.product.name",
    "ro.product.manufacturer",
    "ro.build.version.release",
    "ro.build.version.sdk",
    "ro.build.version.security_patch",
    "ro.build.fingerprint",
    "ro.product.cpu.abi",
    "ro.product.cpu.abilist",
    "ro.treble.enabled",
    "ro.vndk.version",
    "ro.boot.flash.locked",
    "ro.boot.vbmeta.device_state",
    "ro.boot.verifiedbootstate",
    "ro.boot.slot_suffix",
    "ro.virtual_ab.enabled",
    "ro.boot.dynamic_partitions",
]


@dataclass
class DeviceProfile:
    """Snapshot of the properties collected during reconnaissance."""

    serial: str
    props: dict[str, str] = field(default_factory=dict)
    uname: str = ""
    meminfo_raw: str = ""
    df_raw: str = ""

    @property
    def model(self) -> str:
        return self.props.get("ro.product.model", "unknown")

    @property
    def android_version(self) -> str:
        return self.props.get("ro.build.version.release", "unknown")

    @property
    def sdk_int(self) -> int | None:
        raw = self.props.get("ro.build.version.sdk")
        return int(raw) if raw and raw.isdigit() else None

    @property
    def security_patch(self) -> str:
        return self.props.get("ro.build.version.security_patch", "unknown")

    @property
    def fingerprint(self) -> str:
        return self.props.get("ro.build.fingerprint", "unknown")

    @property
    def treble_enabled(self) -> bool:
        return self.props.get("ro.treble.enabled", "").lower() == "true"

    @property
    def bootloader_locked(self) -> bool | None:
        val = self.props.get("ro.boot.flash.locked")
        if val is None:
            return None
        return val == "1"

    @property
    def verified_boot_state(self) -> str:
        return self.props.get("ro.boot.verifiedbootstate", "unknown")

    @property
    def dynamic_partitions(self) -> bool:
        return self.props.get("ro.boot.dynamic_partitions", "").lower() == "true"

    @property
    def virtual_ab(self) -> bool:
        return self.props.get("ro.virtual_ab.enabled", "").lower() == "true"

    def total_ram_kb(self) -> int | None:
        match = re.search(r"MemTotal:\s+(\d+)\s*kB", self.meminfo_raw)
        return int(match.group(1)) if match else None

    def available_ram_kb(self) -> int | None:
        match = re.search(r"MemAvailable:\s+(\d+)\s*kB", self.meminfo_raw)
        return int(match.group(1)) if match else None

    def to_dict(self) -> dict[str, Any]:
        return {
            "serial": self.serial,
            "props": dict(self.props),
            "uname": self.uname,
            "meminfo_raw": self.meminfo_raw,
            "df_raw": self.df_raw,
        }

    @classmethod
    def from_dict(cls, data: dict[str, Any]) -> DeviceProfile:
        return cls(
            serial=data.get("serial", ""),
            props=dict(data.get("props", {})),
            uname=data.get("uname", ""),
            meminfo_raw=data.get("meminfo_raw", ""),
            df_raw=data.get("df_raw", ""),
        )


def probe_device(client: AdbClient, serial: str) -> DeviceProfile:
    """Run the full read-only reconnaissance sweep against `serial`."""
    scoped = AdbClient(serial=serial, adb_path=client.adb_path, runner=client.runner)
    profile = DeviceProfile(serial=serial)
    for prop in BUILD_PROPS:
        profile.props[prop] = scoped.getprop(prop)
    profile.uname = scoped.shell("uname -a").stdout.strip()
    profile.meminfo_raw = scoped.shell("cat /proc/meminfo").stdout
    profile.df_raw = scoped.shell("df -h").stdout
    return profile
