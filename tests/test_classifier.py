from __future__ import annotations

from pathlib import Path

import pytest
import yaml

from relite.classifier import (
    ClassificationDatabase,
    PackageClassification,
    ProtectedEntry,
    load_database,
    load_packages_yaml,
    load_protected_yaml,
)


def test_unknown_package_defaults_to_keep():
    db = ClassificationDatabase()
    assert db.decide("com.totally.unknown.app", "maximum") == "keep"


def test_protected_package_can_never_enter_removal_list():
    db = ClassificationDatabase(
        entries={
            "com.android.systemui": PackageClassification(
                package="com.android.systemui",
                action={"safe": "uninstall-user", "performance": "uninstall-user", "maximum": "uninstall-user"},
            )
        },
        protected={"com.android.systemui": ProtectedEntry(package="com.android.systemui")},
    )
    # even though the classification database says remove it, protection wins
    for profile in ("safe", "performance", "maximum"):
        assert db.decide("com.android.systemui", profile) == "keep"


def test_high_confidence_ad_package_maps_correctly():
    db = ClassificationDatabase(
        entries={
            "com.example.ads": PackageClassification(
                package="com.example.ads",
                category=["ads"],
                confidence="high",
                action={"safe": "disable", "performance": "disable", "maximum": "uninstall-user"},
            )
        }
    )
    assert db.decide("com.example.ads", "safe") == "disable"
    assert db.decide("com.example.ads", "maximum") == "uninstall-user"


def test_load_packages_yaml_round_trip(tmp_path: Path):
    data = [
        {
            "package": "com.example.foo",
            "category": ["ads"],
            "confidence": "high",
            "action": {"safe": "disable", "performance": "disable", "maximum": "uninstall-user"},
            "risk": "low",
            "reason": "Promotional recommendation component.",
            "dependencies": [],
            "rollback": {"supported": True},
        }
    ]
    path = tmp_path / "packages.yaml"
    path.write_text(yaml.safe_dump(data))
    entries = load_packages_yaml(path)
    assert entries["com.example.foo"].category == ["ads"]
    assert entries["com.example.foo"].action_for("maximum") == "uninstall-user"


def test_load_packages_yaml_rejects_unknown_category(tmp_path: Path):
    path = tmp_path / "packages.yaml"
    path.write_text(yaml.safe_dump([{"package": "com.example.foo", "category": ["not-a-real-category"]}]))
    with pytest.raises(ValueError):
        load_packages_yaml(path)


def test_load_packages_yaml_rejects_unknown_action(tmp_path: Path):
    path = tmp_path / "packages.yaml"
    path.write_text(yaml.safe_dump([{"package": "com.example.foo", "action": {"safe": "nuke-from-orbit"}}]))
    with pytest.raises(ValueError):
        load_packages_yaml(path)


def test_load_protected_yaml_accepts_string_and_dict_entries(tmp_path: Path):
    path = tmp_path / "protected-packages.yaml"
    path.write_text(
        yaml.safe_dump(
            {
                "protected": [
                    "com.android.phone",
                    {"package": "com.android.systemui", "reason": "shell UI"},
                ]
            }
        )
    )
    protected = load_protected_yaml(path)
    assert "com.android.phone" in protected
    assert protected["com.android.systemui"].reason == "shell UI"


def test_missing_files_produce_empty_but_valid_database(tmp_path: Path):
    db = load_database(tmp_path / "does-not-exist")
    assert db.entries == {}
    assert db.protected == {}
    assert db.decide("anything", "maximum") == "keep"


def test_real_rmx5303_database_loads_and_protects_systemui():
    device_dir = Path(__file__).resolve().parents[1] / "devices" / "realme" / "RMX5303"
    db = load_database(device_dir)
    assert db.is_protected("com.android.systemui")
    assert db.is_protected("com.android.phone")
    assert db.decide("com.android.systemui", "maximum") == "keep"
