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
from relite.snapshot import SUPPORTED_SCHEMAS, Snapshot


class OwnershipStatus(StrEnum):
    MATCH = "match"
    DEVICE_MISMATCH = "device_mismatch"
    FIRMWARE_DIFFERENT = "firmware_different"


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


def check_ownership(snapshot: Snapshot, current_device: DeviceProfile) -> OwnershipStatus:
    """Section 5: a snapshot must be bound to the physical device it came
    from, not merely "a device with a matching model name" — and even a
    genuine match should flag an OTA-driven firmware change rather than
    silently trusting a baseline that may no longer reflect reality.
    """
    if snapshot.device.model != current_device.model or snapshot.device.serial != current_device.serial:
        return OwnershipStatus.DEVICE_MISMATCH
    if snapshot.device.fingerprint != current_device.fingerprint:
        return OwnershipStatus.FIRMWARE_DIFFERENT
    return OwnershipStatus.MATCH


def validate_baseline(path: Path, current_device: DeviceProfile) -> BaselineValidation:
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

    ownership = check_ownership(raw_snapshot, current_device)
    if ownership != OwnershipStatus.MATCH:
        return BaselineValidation(
            status="ownership_error",
            snapshot=raw_snapshot,
            ownership=ownership,
            detail=(
                "snapshot belongs to a different physical device"
                if ownership == OwnershipStatus.DEVICE_MISMATCH
                else "firmware fingerprint differs from the snapshot's — likely an OTA since it was taken"
            ),
        )

    return BaselineValidation(status="valid", snapshot=raw_snapshot, ownership=OwnershipStatus.MATCH)
