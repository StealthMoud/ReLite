"""Loads and validates a device's `device.yaml` (section 25 of the
v0.3.0 plan) — replaces the bare `yaml.safe_load()` the CLI used to call
directly, which parsed the file but never checked that the values it
actually consumes (benchmark target package/component names,
`support_status`) were real.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

import yaml

from relite.validate import ValidationError, validate_component_name, validate_package_name

VALID_SUPPORT_STATUS = {
    "unvalidated",
    "validated-against-real-device",
    "validated-against-emulator-only",
    "deprecated",
}


@dataclass
class BenchmarkTarget:
    label: str
    package: str
    activity: str | None = None

    def to_dict(self) -> dict[str, Any]:
        data: dict[str, Any] = {"label": self.label, "package": self.package}
        if self.activity is not None:
            data["activity"] = self.activity
        return data


@dataclass
class DeviceMetadata:
    model: str
    support_status: str
    benchmark_targets: list[BenchmarkTarget] = field(default_factory=list)
    pss_targets: list[BenchmarkTarget] = field(default_factory=list)
    raw: dict[str, Any] = field(default_factory=dict)


def _validate_target(raw: dict[str, Any], *, require_activity: bool, context: str) -> BenchmarkTarget:
    label = raw.get("label")
    if not label or not isinstance(label, str):
        raise ValueError(f"{context}: target missing 'label': {raw!r}")
    package = raw.get("package")
    if not package or not isinstance(package, str):
        raise ValueError(f"{context} '{label}': missing 'package'")
    try:
        validate_package_name(package)
    except ValidationError as exc:
        raise ValueError(f"{context} '{label}': {exc}") from exc

    activity = raw.get("activity")
    if activity is None:
        if require_activity:
            raise ValueError(f"{context} '{label}': missing required 'activity'")
        return BenchmarkTarget(label=label, package=package, activity=None)

    try:
        validate_component_name(f"{package}/{activity}")
    except ValidationError as exc:
        raise ValueError(f"{context} '{label}': {exc}") from exc
    return BenchmarkTarget(label=label, package=package, activity=activity)


def load_device_metadata(path: Path) -> DeviceMetadata:
    if not path.exists():
        raise FileNotFoundError(f"device metadata not found: {path}")
    raw = yaml.safe_load(path.read_text()) or {}
    if not isinstance(raw, dict):
        raise ValueError(f"{path}: expected a mapping at the top level")

    model = raw.get("model")
    if not model or not isinstance(model, str):
        raise ValueError(f"{path}: missing 'model'")

    support_status = raw.get("support_status", "unvalidated")
    if support_status not in VALID_SUPPORT_STATUS:
        valid = sorted(VALID_SUPPORT_STATUS)
        raise ValueError(f"{path}: invalid support_status '{support_status}', expected one of {valid}")

    benchmark_targets = [
        _validate_target(t, require_activity=True, context=f"{path} benchmark_targets")
        for t in raw.get("benchmark_targets", []) or []
    ]
    pss_targets = [
        _validate_target(t, require_activity=False, context=f"{path} pss_targets")
        for t in raw.get("pss_targets", []) or []
    ]

    return DeviceMetadata(
        model=model,
        support_status=support_status,
        benchmark_targets=benchmark_targets,
        pss_targets=pss_targets,
        raw=raw,
    )
