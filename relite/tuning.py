"""Non-package tuning: animation scale, OEM RAM Expansion, and optional
Private-DNS-based ad blocking.

Nothing here touches CPU governors, LMKD, zRAM, background process limits,
or kernel sysctls — see docs/safety.md for why those are explicitly out of
scope.
"""

from __future__ import annotations

from dataclasses import dataclass

from relite.adb import AdbClient

ANIMATION_PROFILES: dict[str, str] = {
    "safe": "0.5",
    "performance": "0.5",
    "maximum": "0",
}

# Candidate global/secure/system setting keys observed across OEM skins for
# "RAM Expansion" / "extended RAM" / "virtual RAM" style features. ReLite
# only *reports* what it finds unless a key is confirmed present on the
# device — see devices/realme/RMX5303/findings.md for what was actually
# observed on this device.
RAM_EXPANSION_CANDIDATE_KEYS = (
    "ram_expand_size",
    "ram_expand_switch",
    "extend_ram_switch",
    "virtual_ram_switch",
    "reserved_ram_extend",
)


def set_animation_scale(client: AdbClient, scale: str) -> dict[str, bool]:
    results = {}
    for key in ("window_animation_scale", "transition_animation_scale", "animator_duration_scale"):
        result = client.shell(f"settings put global {key} {scale}")
        results[key] = result.ok
    return results


def apply_animation_profile(client: AdbClient, profile: str) -> dict[str, bool]:
    scale = ANIMATION_PROFILES.get(profile)
    if scale is None:
        raise ValueError(f"unknown profile {profile!r}")
    return set_animation_scale(client, scale)


@dataclass
class RamExpansionProbe:
    found_keys: dict[str, str]

    @property
    def detected(self) -> bool:
        return bool(self.found_keys)


def probe_ram_expansion(client: AdbClient) -> RamExpansionProbe:
    """Search global/secure/system settings for a RAM-expansion-like key.

    This does not guess a key name to write blindly — section 14 of the
    master plan requires finding the *actual* setting on the device before
    acting on it.
    """
    found: dict[str, str] = {}
    for namespace in ("global", "secure", "system"):
        result = client.shell(f"settings list {namespace}")
        for line in result.lines():
            if "=" not in line:
                continue
            key, _, value = line.partition("=")
            key = key.strip()
            for candidate in RAM_EXPANSION_CANDIDATE_KEYS:
                if candidate in key.lower():
                    found[f"{namespace}.{key}"] = value.strip()
    return RamExpansionProbe(found_keys=found)


def set_ram_expansion(client: AdbClient, setting_key: str, namespace: str, value: str) -> bool:
    """Only call this with a key confirmed present by `probe_ram_expansion`."""
    result = client.shell(f"settings put {namespace} {setting_key} {value}")
    return result.ok


def set_private_dns(client: AdbClient, hostname: str | None) -> bool:
    """Configure Android Private DNS. `hostname=None` disables (mode=off).

    Never called with a hard-coded default provider — the caller/CLI must
    supply a hostname explicitly (see `relite network-adblock`).
    """
    if hostname is None:
        mode_result = client.shell("settings put global private_dns_mode off")
        return mode_result.ok
    mode_result = client.shell("settings put global private_dns_mode hostname")
    host_result = client.shell(f"settings put global private_dns_specifier {hostname}")
    return mode_result.ok and host_result.ok
