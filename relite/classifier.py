"""Package classification database and safety policy.

Loads a device's `packages.yaml` (classification data) and
`protected-packages.yaml` (hard safety floor), and decides what action —
if any — a given profile should take on a given installed package.

The core rule (section 9 of the master plan): **unknown packages default
to `keep`**, and protected packages can never be scheduled for removal
regardless of what the classification database says.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Literal

import yaml

Action = Literal["keep", "disable", "uninstall-user"]
Profile = Literal["safe", "performance", "maximum"]

VALID_CATEGORIES = {
    "critical", "telephony", "network", "bluetooth", "system-ui", "permissions",
    "storage", "media", "camera", "biometrics", "location", "security", "update",
    "launcher", "cloud", "analytics", "ads", "recommendations", "games", "themes",
    "browser", "assistant", "diagnostics", "optional", "unknown",
}

VALID_ACTIONS = {"keep", "disable", "uninstall-user"}
VALID_PROFILES = {"safe", "performance", "maximum"}


@dataclass
class PackageClassification:
    package: str
    category: list[str] = field(default_factory=list)
    confidence: str = "low"
    action: dict[str, Action] = field(default_factory=dict)
    risk: str = "unknown"
    reason: str = ""
    dependencies: list[str] = field(default_factory=list)
    rollback_supported: bool = True

    def action_for(self, profile: Profile) -> Action:
        return self.action.get(profile, "keep")


@dataclass
class ProtectedEntry:
    package: str
    reason: str = ""


@dataclass
class ClassificationDatabase:
    entries: dict[str, PackageClassification] = field(default_factory=dict)
    protected: dict[str, ProtectedEntry] = field(default_factory=dict)

    def is_protected(self, package: str) -> bool:
        return package in self.protected

    def classify(self, package: str) -> PackageClassification:
        if package in self.entries:
            return self.entries[package]
        # Unknown packages default to keep — never inferred as removable.
        return PackageClassification(package=package, category=["unknown"], confidence="none", action={})

    def decide(self, package: str, profile: Profile) -> Action:
        """The single authoritative decision function: protected policy always wins."""
        if self.is_protected(package):
            return "keep"
        return self.classify(package).action_for(profile)


def _validate_entry(raw: dict[str, Any]) -> None:
    package = raw.get("package")
    if not package or not isinstance(package, str):
        raise ValueError(f"packages.yaml entry missing 'package': {raw!r}")
    for cat in raw.get("category", []):
        if cat not in VALID_CATEGORIES:
            raise ValueError(f"{package}: unknown category '{cat}'")
    for profile, action in (raw.get("action") or {}).items():
        if profile not in VALID_PROFILES:
            raise ValueError(f"{package}: unknown profile '{profile}' in action map")
        if action not in VALID_ACTIONS:
            raise ValueError(f"{package}: unknown action '{action}' for profile '{profile}'")


def load_packages_yaml(path: Path) -> dict[str, PackageClassification]:
    if not path.exists():
        return {}
    raw_entries = yaml.safe_load(path.read_text()) or []
    if not isinstance(raw_entries, list):
        raise ValueError(f"{path}: expected a list of package entries")

    entries: dict[str, PackageClassification] = {}
    for raw in raw_entries:
        _validate_entry(raw)
        rollback = raw.get("rollback", {}) or {}
        entries[raw["package"]] = PackageClassification(
            package=raw["package"],
            category=list(raw.get("category", [])),
            confidence=raw.get("confidence", "low"),
            action=dict(raw.get("action", {})),
            risk=raw.get("risk", "unknown"),
            reason=raw.get("reason", "").strip(),
            dependencies=list(raw.get("dependencies", [])),
            rollback_supported=bool(rollback.get("supported", True)),
        )
    return entries


def load_protected_yaml(path: Path) -> dict[str, ProtectedEntry]:
    if not path.exists():
        return {}
    raw = yaml.safe_load(path.read_text()) or {}
    packages = raw.get("protected", []) if isinstance(raw, dict) else raw
    protected: dict[str, ProtectedEntry] = {}
    for item in packages:
        if isinstance(item, str):
            protected[item] = ProtectedEntry(package=item)
        else:
            protected[item["package"]] = ProtectedEntry(
                package=item["package"], reason=item.get("reason", "")
            )
    return protected


def load_database(device_dir: Path) -> ClassificationDatabase:
    entries = load_packages_yaml(device_dir / "packages.yaml")
    protected = load_protected_yaml(device_dir / "protected-packages.yaml")
    return ClassificationDatabase(entries=entries, protected=protected)
