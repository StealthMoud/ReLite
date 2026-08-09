"""Baseline snapshot tracking and ownership validation (sections 5-7 of
the v0.3.0 plan).

A "baseline" is the one snapshot `relite apply`'s planner treats as
"what this package looked like before ReLite ever touched it", and the
one `relite restore --all` restores to. It must be explicitly recorded
(`DeviceState.baseline_snapshot`), not inferred from a snapshot file
happening to be named "stock" — and it must be verified to actually
belong to the device currently connected, not just assumed valid because
a directory with snapshots in it exists.
"""

from __future__ import annotations

from dataclasses import dataclass
from enum import StrEnum
from pathlib import Path

from relite.device import DeviceProfile
from relite.sanitize import REDACTED
from relite.snapshot import SUPPORTED_SCHEMAS, Snapshot


class OwnershipStatus(StrEnum):
    MATCH = "match"
    DEVICE_MISMATCH = "device_mismatch"
    FIRMWARE_DIFFERENT = "firmware_different"
    # Section 11 (v0.4.0): a v1 snapshot predates `device_key` AND had its
    # raw serial redacted by the privacy sanitizer on save — there is no
    # remaining signal (pseudonymous key or raw serial) to cryptographically
    # bind it to *this* physical device. Distinct from DEVICE_MISMATCH,
    # which asserts a positive mismatch; this asserts "cannot know".
    LEGACY_OWNERSHIP_UNKNOWN = "legacy_ownership_unknown"


@dataclass
class BaselineValidation:
    status: str  # "valid" | "missing" | "corrupt" | "unsupported_schema" | "ownership_error"
    snapshot: Snapshot | None = None
    ownership: OwnershipStatus | None = None
    detail: str = ""

    @property
    def usable(self) -> bool:
        """A baseline the planner can safely use as-is. FIRMWARE_DIFFERENT
        is deliberately excluded — see check_ownership()'s docstring."""
        return self.status == "valid" and self.ownership == OwnershipStatus.MATCH


def check_ownership(
    snapshot: Snapshot, current_device: DeviceProfile, current_device_key: str = ""
) -> OwnershipStatus:
    """Section 5: a snapshot must be bound to the physical device it came
    from, not merely "a device with a matching model name" — and even a
    genuine match should flag an OTA-driven firmware change rather than
    silently trusting a baseline that may no longer reflect reality.

    Prefers comparing the pseudonymous `device_key` (section 18) when
    both sides have one — `snapshot.device.serial` is redacted by the
    privacy sanitizer on save (see snapshot.py's `save()` docstring), so
    it's not reliably comparable for any snapshot saved with
    `sanitize=True`. Falls back to comparing the raw serial for a
    snapshot taken before `device_key` existed, or when the caller
    doesn't supply `current_device_key` — best-effort, not authoritative,
    for that older data.
    """
    if snapshot.device.model != current_device.model:
        return OwnershipStatus.DEVICE_MISMATCH
    if snapshot.device_key and current_device_key:
        if snapshot.device_key != current_device_key:
            return OwnershipStatus.DEVICE_MISMATCH
    elif snapshot.device.serial == REDACTED:
        # No device_key (pre-v0.3.0 snapshot) and the raw serial was
        # scrubbed by the sanitizer on save — neither identifier survives
        # to compare against. Never treat this as a match by omission.
        return OwnershipStatus.LEGACY_OWNERSHIP_UNKNOWN
    elif snapshot.device.serial != current_device.serial:
        return OwnershipStatus.DEVICE_MISMATCH
    if snapshot.device.fingerprint != current_device.fingerprint:
        return OwnershipStatus.FIRMWARE_DIFFERENT
    return OwnershipStatus.MATCH


def validate_baseline(
    path: Path, current_device: DeviceProfile, current_device_key: str = ""
) -> BaselineValidation:
    """Section 7: "the snapshots/ directory exists" is not "a valid
    rollback baseline exists". Checks existence, parseability, schema
    support, and device ownership, in that order.
    """
    if not path.exists():
        return BaselineValidation(status="missing", detail=f"{path} does not exist")

    try:
        raw_snapshot = Snapshot.load(path)
    except ValueError as exc:
        if "unsupported snapshot schema" in str(exc):
            return BaselineValidation(status="unsupported_schema", detail=str(exc))
        return BaselineValidation(status="corrupt", detail=str(exc))
    except (OSError, KeyError) as exc:
        return BaselineValidation(status="corrupt", detail=str(exc))

    if raw_snapshot.schema not in SUPPORTED_SCHEMAS:
        return BaselineValidation(status="unsupported_schema", detail=f"schema {raw_snapshot.schema}")

    ownership = check_ownership(raw_snapshot, current_device, current_device_key)
    if ownership != OwnershipStatus.MATCH:
        details = {
            OwnershipStatus.DEVICE_MISMATCH: "snapshot belongs to a different physical device",
            OwnershipStatus.FIRMWARE_DIFFERENT: (
                "firmware fingerprint differs from the snapshot's — likely an OTA since it was taken"
            ),
            OwnershipStatus.LEGACY_OWNERSHIP_UNKNOWN: (
                "pre-v0.3.0 snapshot has no pseudonymous device key and its raw serial was redacted "
                "on save — ownership cannot be verified either way. Use "
                "'relite snapshot --name <n> --set-baseline' to explicitly adopt or replace it."
            ),
        }
        return BaselineValidation(
            status="ownership_error", snapshot=raw_snapshot, ownership=ownership, detail=details[ownership]
        )

    return BaselineValidation(status="valid", snapshot=raw_snapshot, ownership=OwnershipStatus.MATCH)
