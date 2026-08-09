from __future__ import annotations

from pathlib import Path

import pytest
import yaml

from relite.classifier import (
    ClassificationDatabase,
    PackageClassification,
    ProtectedEntry,
    find_protected_conflicts,
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


def test_load_packages_yaml_parses_platform_limitation(tmp_path: Path):
    data = [
        {
            "package": "com.example.stuck",
            "action": {"safe": "disable"},
            "platform_limitation": "pm disable-user is silently refused on this OEM build",
        }
    ]
    path = tmp_path / "packages.yaml"
    path.write_text(yaml.safe_dump(data))
    entries = load_packages_yaml(path)
    assert entries["com.example.stuck"].platform_limitation == (
        "pm disable-user is silently refused on this OEM build"
    )


def test_load_packages_yaml_defaults_platform_limitation_to_none(tmp_path: Path):
    path = tmp_path / "packages.yaml"
    path.write_text(yaml.safe_dump([{"package": "com.example.foo"}]))
    entries = load_packages_yaml(path)
    assert entries["com.example.foo"].platform_limitation is None


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


def test_load_packages_yaml_rejects_invalid_confidence(tmp_path: Path):
    path = tmp_path / "packages.yaml"
    path.write_text(yaml.safe_dump([{"package": "com.example.foo", "confidence": "extremely-high"}]))
    with pytest.raises(ValueError, match="confidence"):
        load_packages_yaml(path)


def test_load_packages_yaml_rejects_invalid_risk(tmp_path: Path):
    path = tmp_path / "packages.yaml"
    path.write_text(yaml.safe_dump([{"package": "com.example.foo", "risk": "extreme"}]))
    with pytest.raises(ValueError, match="risk"):
        load_packages_yaml(path)


def test_load_packages_yaml_rejects_malformed_package_name(tmp_path: Path):
    path = tmp_path / "packages.yaml"
    path.write_text(yaml.safe_dump([{"package": "com.example; rm -rf /"}]))
    with pytest.raises(ValueError):
        load_packages_yaml(path)


def test_load_packages_yaml_rejects_malformed_dependency(tmp_path: Path):
    path = tmp_path / "packages.yaml"
    path.write_text(
        yaml.safe_dump([{"package": "com.example.foo", "dependencies": ["not a package name"]}])
    )
    with pytest.raises(ValueError, match="dependency"):
        load_packages_yaml(path)


def test_load_packages_yaml_rejects_duplicate_package_entries(tmp_path: Path):
    path = tmp_path / "packages.yaml"
    path.write_text(
        yaml.safe_dump(
            [
                {"package": "com.example.foo", "action": {"safe": "keep"}},
                {"package": "com.example.foo", "action": {"safe": "disable"}},
            ]
        )
    )
    with pytest.raises(ValueError, match="duplicate"):
        load_packages_yaml(path)


def test_load_packages_yaml_rejects_non_reversible_non_keep_action(tmp_path: Path):
    """Section 22: an action ReLite can't reliably reverse doesn't belong
    in a normal profile."""
    path = tmp_path / "packages.yaml"
    path.write_text(
        yaml.safe_dump(
            [
                {
                    "package": "com.example.foo",
                    "action": {"maximum": "uninstall-user"},
                    "rollback": {"supported": False},
                }
            ]
        )
    )
    with pytest.raises(ValueError, match="rollback"):
        load_packages_yaml(path)


def test_load_packages_yaml_allows_non_reversible_keep_only_entry(tmp_path: Path):
    """rollback.supported=False is fine as long as every action is keep."""
    path = tmp_path / "packages.yaml"
    path.write_text(
        yaml.safe_dump(
            [{"package": "com.example.foo", "action": {"safe": "keep"}, "rollback": {"supported": False}}]
        )
    )
    entries = load_packages_yaml(path)
    assert entries["com.example.foo"].rollback_supported is False


def test_load_packages_yaml_rejects_non_monotonic_action_map(tmp_path: Path):
    """Section 24: safe: disable, performance: keep is a policy-drift bug
    unless explicitly documented as intentional."""
    path = tmp_path / "packages.yaml"
    path.write_text(
        yaml.safe_dump(
            [{"package": "com.example.foo", "action": {"safe": "disable", "performance": "keep"}}]
        )
    )
    with pytest.raises(ValueError, match="non-monotonic"):
        load_packages_yaml(path)


def test_load_packages_yaml_allows_documented_monotonicity_exception(tmp_path: Path):
    path = tmp_path / "packages.yaml"
    path.write_text(
        yaml.safe_dump(
            [
                {
                    "package": "com.example.foo",
                    "action": {"safe": "disable", "performance": "keep"},
                    "monotonicity_exception": "re-enabled in performance because X",
                }
            ]
        )
    )
    entries = load_packages_yaml(path)
    assert entries["com.example.foo"].action_for("performance") == "keep"


def test_load_packages_yaml_allows_monotonic_increase(tmp_path: Path):
    path = tmp_path / "packages.yaml"
    path.write_text(
        yaml.safe_dump(
            [
                {
                    "package": "com.example.foo",
                    "action": {"safe": "keep", "performance": "disable", "maximum": "uninstall-user"},
                }
            ]
        )
    )
    entries = load_packages_yaml(path)  # must not raise
    assert entries["com.example.foo"].action_for("maximum") == "uninstall-user"


def test_find_protected_conflicts_detects_contradiction():
    db = ClassificationDatabase(
        entries={
            "com.example.core": PackageClassification(
                package="com.example.core", action={"safe": "disable"}
            )
        },
        protected={"com.example.core": ProtectedEntry(package="com.example.core")},
    )
    conflicts = find_protected_conflicts(db)
    assert len(conflicts) == 1
    assert "com.example.core" in conflicts[0]


def test_find_protected_conflicts_clean_when_protected_is_keep_only():
    db = ClassificationDatabase(
        entries={
            "com.example.core": PackageClassification(
                package="com.example.core", action={"safe": "keep"}
            )
        },
        protected={"com.example.core": ProtectedEntry(package="com.example.core")},
    )
    assert find_protected_conflicts(db) == []


def test_load_database_raises_on_protected_conflict(tmp_path: Path):
    (tmp_path / "packages.yaml").write_text(
        yaml.safe_dump([{"package": "com.example.core", "action": {"safe": "disable"}}])
    )
    (tmp_path / "protected-packages.yaml").write_text(yaml.safe_dump({"protected": ["com.example.core"]}))
    with pytest.raises(ValueError, match="conflict"):
        load_database(tmp_path)


def test_real_rmx5303_database_loads_and_protects_systemui():
    device_dir = Path(__file__).resolve().parents[1] / "devices" / "realme" / "RMX5303"
    db = load_database(device_dir)
    assert db.is_protected("com.android.systemui")
    assert db.is_protected("com.android.phone")
    assert db.decide("com.android.systemui", "maximum") == "keep"
