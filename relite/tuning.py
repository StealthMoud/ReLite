"""Non-package tuning: animation scale, OEM RAM Expansion, and optional
Private-DNS-based ad blocking.

Nothing here touches CPU governors, LMKD, zRAM, background process limits,
or kernel sysctls — see docs/safety.md for why those are explicitly out of
scope.
"""

from __future__ import annotations

import json
import re
from dataclasses import dataclass
from pathlib import Path

from relite.adb import AdbClient
from relite.profiles import load_profiles
from relite.validate import validate_dns_hostname, validate_setting_key

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


ANIMATION_SCALE_KEYS = ("window_animation_scale", "transition_animation_scale", "animator_duration_scale")


def read_animation_scale(client: AdbClient) -> dict[str, str]:
    """The live value of every ReLite-managed animation-scale key, used to
    capture "what it was before this apply" so `relite undo` can restore
    tuning as part of the same transaction it reverses packages in
    (section 4 of the v0.4.0 plan) — not just re-derive some other
    profile's nominal value, which could be wrong if the setting had
    drifted (manually changed, OEM override) before this apply ran."""
    return {key: client.shell(f"settings get global {key}").stdout.strip() for key in ANIMATION_SCALE_KEYS}


def set_animation_scale(client: AdbClient, scale: str) -> dict[str, bool]:
    results = {}
    for key in ANIMATION_SCALE_KEYS:
        result = client.shell(f"settings put global {key} {scale}")
        results[key] = result.ok
    return results


def apply_animation_profile(client: AdbClient, profile: str) -> dict[str, bool]:
    profiles = load_profiles()
    meta = profiles.get(profile)
    if meta is None:
        raise ValueError(f"unknown profile {profile!r}")
    return set_animation_scale(client, meta.animation_scale)


def record_tuning_change(
    path: Path, apply_id: str, previous: dict[str, str], target: dict[str, str]
) -> None:
    """Append one journal line per `relite apply`'s tuning change (section
    4 of the v0.4.0 plan) so `relite undo` can restore animation scale as
    part of the same transaction it reverses packages in, instead of undo
    only ever touching package state and silently leaving tuning on
    whatever the last-applied profile set it to."""
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("a") as journal:
        journal.write(json.dumps({"apply_id": apply_id, "previous": previous, "target": target}) + "\n")


def load_tuning_change(path: Path, apply_id: str) -> dict[str, str] | None:
    """The pre-apply animation-scale values recorded for `apply_id`, or
    None if this apply never touched tuning (or predates section 4)."""
    if not path.exists():
        return None
    for line in path.read_text().splitlines():
        if not line.strip():
            continue
        record = json.loads(line)
        if record.get("apply_id") == apply_id:
            previous = record.get("previous")
            return dict(previous) if isinstance(previous, dict) else None
    return None


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
    validate_dns_hostname(hostname)
    mode_result = client.shell("settings put global private_dns_mode hostname")
    host_result = client.shell(f"settings put global private_dns_specifier {hostname}")
    return mode_result.ok and host_result.ok


_ANIMATION_SCALE_RE = re.compile(r"^\d+(\.\d+)?$")
_PRIVATE_DNS_MODE_RE = re.compile(r"^(off|opportunistic|hostname)$")


@dataclass
class SettingRestoreResult:
    """Section 8 of the v0.4.0 plan: `restore_managed_setting` used to
    return nothing, so a failed `settings put`, a failed `settings delete`,
    or a rejected (invalid-format) snapshot value was silently swallowed —
    `relite restore` could report "N settings restored" while some of them
    silently didn't take. Every call now reports what was requested, what
    command ran, and what the read-back value actually is afterward."""

    namespace: str
    key: str
    requested: str | None  # None means "delete the key", matching original_value
    command_ok: bool
    observed: str | None
    verified: bool


def restore_managed_setting(
    client: AdbClient,
    namespace: str,
    key: str,
    original_value: str | None,
    *,
    dry_run: bool = False,
) -> SettingRestoreResult:
    """Restore a single ReLite-managed setting to its recorded pre-ReLite
    value, distinguishing "the key existed with this value" from "the key
    did not exist at all" (`settings delete`, not a guessed default).

    Section 3 of the v0.2.0 plan: restore must never assume a value ReLite
    didn't originally observe — e.g. restoring Private DNS must never just
    turn it off, since the user may have configured it before ReLite ever
    ran.
    """
    validate_setting_key(key)
    if dry_run:
        return SettingRestoreResult(
            namespace=namespace, key=key, requested=original_value, command_ok=True, observed=None,
            verified=True,
        )

    if original_value is not None:
        if key in ANIMATION_SCALE_KEYS and not _ANIMATION_SCALE_RE.match(original_value):
            return SettingRestoreResult(
                namespace=namespace, key=key, requested=original_value, command_ok=False, observed=None,
                verified=False,
            )
        if key == "private_dns_mode" and not _PRIVATE_DNS_MODE_RE.match(original_value):
            return SettingRestoreResult(
                namespace=namespace, key=key, requested=original_value, command_ok=False, observed=None,
                verified=False,
            )
        if (
            key == "private_dns_specifier"
            and original_value
            and not re.match(r"^[A-Za-z0-9.-]+$", original_value)
        ):
            return SettingRestoreResult(
                namespace=namespace, key=key, requested=original_value, command_ok=False, observed=None,
                verified=False,
            )

    if original_value is None:
        command_ok = client.shell(f"settings delete {namespace} {key}").ok
    else:
        command_ok = client.shell(f"settings put {namespace} {key} {original_value}").ok

    observed = client.shell(f"settings get global {key}").stdout.strip() if namespace == "global" else None
    if namespace == "global":
        observed_norm = None if observed in ("null", "") else observed
        verified = command_ok and observed_norm == original_value
    else:
        # No portable read-back path is used for non-global namespaces today
        # (nothing currently restores secure/system settings) — command
        # success is the only signal available.
        verified = command_ok

    return SettingRestoreResult(
        namespace=namespace, key=key, requested=original_value, command_ok=command_ok, observed=observed,
        verified=verified,
    )
