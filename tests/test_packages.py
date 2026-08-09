from __future__ import annotations

import pytest

from relite.adb import AdbClient
from relite.packages import PackageInfo, PackageInventoryError, enrich_package, list_packages
from relite.validate import ValidationError


def test_list_packages_classifies_system_third_party_disabled(fake_client: AdbClient, fake_runner):
    fake_runner.set_response(
        ["adb", "-s", "EMULATOR123", "shell", "pm list packages --user 0"],
        stdout="package:com.android.systemui\npackage:com.example.ads\npackage:com.example.thirdparty\n",
    )
    fake_runner.set_response(
        ["adb", "-s", "EMULATOR123", "shell", "pm list packages -s --user 0"],
        stdout="package:com.android.systemui\npackage:com.example.ads\n",
    )
    fake_runner.set_response(
        ["adb", "-s", "EMULATOR123", "shell", "pm list packages -3 --user 0"],
        stdout="package:com.example.thirdparty\n",
    )
    fake_runner.set_response(
        ["adb", "-s", "EMULATOR123", "shell", "pm list packages -d --user 0"],
        stdout="package:com.example.ads\n",
    )
    fake_runner.set_response(
        ["adb", "-s", "EMULATOR123", "shell", "pm list packages -e --user 0"],
        stdout="package:com.android.systemui\npackage:com.example.thirdparty\n",
    )

    packages = {p.name: p for p in list_packages(fake_client)}
    assert packages["com.android.systemui"].system is True
    assert packages["com.example.ads"].disabled is True
    assert packages["com.example.thirdparty"].third_party is True


def test_list_packages_excludes_packages_uninstalled_for_user_0(fake_client: AdbClient, fake_runner):
    """Real-device finding (RMX5303, 2026-08-08): `pm list packages`
    without `--user 0` lists packages uninstalled via
    `pm uninstall --user 0` as if they were still fully present."""
    fake_runner.set_response(
        ["adb", "-s", "EMULATOR123", "shell", "pm list packages --user 0"],
        stdout="package:com.android.systemui\n",
    )
    fake_runner.set_response(
        ["adb", "-s", "EMULATOR123", "shell", "pm list packages -s --user 0"],
        stdout="package:com.android.systemui\n",
    )
    for flag in ("-3", "-d", "-e"):
        fake_runner.set_response(
            ["adb", "-s", "EMULATOR123", "shell", f"pm list packages {flag} --user 0"], stdout=""
        )

    packages = {p.name: p for p in list_packages(fake_client)}
    assert set(packages) == {"com.android.systemui"}


def test_enrich_package_extracts_version_installer_permissions(fake_client: AdbClient, fake_runner):
    dump = """
    versionName=1.2.3
    installerPackageName=com.android.vending
        android.permission.INTERNET: granted=true
        android.permission.ACCESS_FINE_LOCATION: granted=false
    """
    fake_runner.set_response(
        ["adb", "-s", "EMULATOR123", "shell", "dumpsys package com.example.foo"], stdout=dump
    )
    fake_runner.set_response(
        ["adb", "-s", "EMULATOR123", "shell", "pm path com.example.foo"],
        stdout="package:/data/app/com.example.foo/base.apk",
    )

    pkg = enrich_package(fake_client, PackageInfo(name="com.example.foo"))
    assert pkg.version_name == "1.2.3"
    assert pkg.installer == "com.android.vending"
    assert "android.permission.INTERNET" in pkg.permissions
    assert pkg.apk_path == "/data/app/com.example.foo/base.apk"


def test_list_packages_fails_closed_when_a_required_query_errors(fake_client: AdbClient, fake_runner):
    """Section 15: a failed pm query must not silently become an empty
    package set — that would make every profile's plan look like a
    no-op instead of surfacing the real problem."""
    fake_runner.set_response(
        ["adb", "-s", "EMULATOR123", "shell", "pm list packages --user 0"],
        stdout="", stderr="device offline", returncode=1,
    )
    with pytest.raises(PackageInventoryError):
        list_packages(fake_client)


def test_list_packages_fails_closed_on_invariant_violation(fake_client: AdbClient, fake_runner):
    """A package appearing in -d (disabled) but not in the bare listing
    indicates inconsistent/garbled pm output, even if every individual
    command exited 0."""
    fake_runner.set_response(
        ["adb", "-s", "EMULATOR123", "shell", "pm list packages --user 0"],
        stdout="package:com.example.a\n",
    )
    for flag in ("-s", "-3", "-e"):
        fake_runner.set_response(["adb", "-s", "EMULATOR123", "shell", f"pm list packages {flag} --user 0"], stdout="")
    fake_runner.set_response(
        ["adb", "-s", "EMULATOR123", "shell", "pm list packages -d --user 0"],
        stdout="package:com.example.a\npackage:com.example.ghost\n",
    )
    with pytest.raises(PackageInventoryError, match="invariant violated"):
        list_packages(fake_client)


def test_enrich_package_rejects_malformed_package_name(fake_client: AdbClient, fake_runner):
    with pytest.raises(ValidationError):
        enrich_package(fake_client, PackageInfo(name="com.example; rm -rf /"))
    assert fake_runner.calls == []


def test_package_info_dict_round_trip():
    pkg = PackageInfo(name="com.example.foo", system=True, permissions=["android.permission.INTERNET"])
    restored = PackageInfo.from_dict(pkg.to_dict())
    assert restored == pkg
