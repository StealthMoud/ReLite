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

from relite.validate import ValidationError, validate_package_name

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
VALID_CONFIDENCE = {"none", "low", "medium", "high"}
VALID_RISK = {"low", "medium", "high", "critical", "unknown"}

# Section 24: profiles get more aggressive left-to-right; a package's
# action must never *decrease* in rank from safe -> performance -> maximum
# unless explicitly documented as intentional (schema_exception field).
_ACTION_RANK = {"keep": 0, "disable": 1, "uninstall-user": 2}
_PROFILE_ORDER: tuple[Profile, ...] = ("safe", "performance", "maximum")


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
    # Set when real-device testing found the platform itself refuses this
    # action regardless of what ReLite does (e.g. `pm disable-user` exits
    # 0 but the platform silently keeps the package enabled). The intended
    # action stays in `action` — it's still correct and harmless to
    # attempt — but `relite status`'s integrity check treats a mismatch
    # here as a known limitation, not a compliance failure to alarm about
    # on every run.
    platform_limitation: str | None = None
    # Section 24: documents *why* this package's action map intentionally
    # gets less aggressive at a higher profile (rare) — schema validation
    # rejects a non-monotonic action map unless this is set, so an
    # accidental policy typo can't ship silently.
    monotonicity_exception: str | None = None

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


def find_protected_conflicts(db: ClassificationDatabase) -> list[str]:
    """Section 23: a package in protected-packages.yaml with a non-keep
    action anywhere in packages.yaml is contradictory data — protected
    policy wins at runtime either way (`decide()` always returns "keep"
    for a protected package), but shipping the contradiction silently
    invites someone to "fix" the wrong file later. Returns one message
    per conflicting package; empty means clean."""
    conflicts = []
    for name, entry in db.entries.items():
        if name not in db.protected:
            continue
        non_keep = {profile: action for profile, action in entry.action.items() if action != "keep"}
        if non_keep:
            conflicts.append(
                f"{name} is protected but packages.yaml sets a non-keep action: {non_keep}"
            )
    return conflicts


def _validate_entry(raw: dict[str, Any], seen_packages: set[str]) -> None:
    package = raw.get("package")
    if not package or not isinstance(package, str):
        raise ValueError(f"packages.yaml entry missing 'package': {raw!r}")
    try:
        validate_package_name(package)
    except ValidationError as exc:
        raise ValueError(f"packages.yaml: {exc}") from exc

    if package in seen_packages:
        raise ValueError(f"packages.yaml: duplicate entry for package '{package}'")

    for cat in raw.get("category", []):
        if cat not in VALID_CATEGORIES:
            raise ValueError(f"{package}: unknown category '{cat}'")

    confidence = raw.get("confidence", "low")
    if confidence not in VALID_CONFIDENCE:
        raise ValueError(
            f"{package}: invalid confidence '{confidence}', expected one of {sorted(VALID_CONFIDENCE)}"
        )

    risk = raw.get("risk", "unknown")
    if risk not in VALID_RISK:
        raise ValueError(f"{package}: invalid risk '{risk}', expected one of {sorted(VALID_RISK)}")

    action_map = raw.get("action") or {}
    for profile, action in action_map.items():
        if profile not in VALID_PROFILES:
            raise ValueError(f"{package}: unknown profile '{profile}' in action map")
        if action not in VALID_ACTIONS:
            raise ValueError(f"{package}: unknown action '{action}' for profile '{profile}'")

    for dependency in raw.get("dependencies", []):
        try:
            validate_package_name(dependency)
        except ValidationError as exc:
            raise ValueError(f"{package}: invalid dependency package name: {exc}") from exc

    # Section 22: reversibility policy. A non-keep action ReLite can't
    # reliably reverse doesn't belong in a normal profile — ReLite's core
    # promise is reversibility.
    rollback = raw.get("rollback", {}) or {}
    rollback_supported = bool(rollback.get("supported", True))
    if not rollback_supported:
        non_keep_actions = {p: a for p, a in action_map.items() if a != "keep"}
        if non_keep_actions:
            raise ValueError(
                f"{package}: rollback.supported is false but has non-keep action(s) {non_keep_actions} "
                "— an unreversible action is not allowed in a normal profile"
            )

    # Section 24: profile monotonicity — action rank must never decrease
    # going safe -> performance -> maximum, unless documented.
    if not raw.get("monotonicity_exception"):
        ranks = [
            _ACTION_RANK[action_map[p]]
            for p in _PROFILE_ORDER
            if p in action_map
        ]
        if ranks != sorted(ranks):
            raise ValueError(
                f"{package}: action map is non-monotonic across safe -> performance -> maximum "
                f"({ {p: action_map[p] for p in _PROFILE_ORDER if p in action_map} }); "
                "set 'monotonicity_exception: <reason>' if this is intentional"
            )


def load_packages_yaml(path: Path) -> dict[str, PackageClassification]:
    if not path.exists():
        return {}
    raw_entries = yaml.safe_load(path.read_text()) or []
    if not isinstance(raw_entries, list):
        raise ValueError(f"{path}: expected a list of package entries")

    entries: dict[str, PackageClassification] = {}
    seen_packages: set[str] = set()
    for raw in raw_entries:
        _validate_entry(raw, seen_packages)
        seen_packages.add(raw["package"])
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
            platform_limitation=raw.get("platform_limitation"),
            monotonicity_exception=raw.get("monotonicity_exception"),
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
    db = ClassificationDatabase(entries=entries, protected=protected)
    conflicts = find_protected_conflicts(db)
    if conflicts:
        raise ValueError(f"{device_dir}: protected/packages.yaml conflicts: {conflicts}")
    return db
