from __future__ import annotations

from relite.adb import AdbClient
from relite.packages import PackageInfo, enrich_package, list_packages


def test_list_packages_classifies_system_third_party_disabled(fake_client: AdbClient, fake_runner):
    fake_runner.set_response(
        ["adb", "-s", "EMULATOR123", "shell", "pm list packages"],
        stdout="package:com.android.systemui\npackage:com.example.ads\npackage:com.example.thirdparty\n",
    )
    fake_runner.set_response(
        ["adb", "-s", "EMULATOR123", "shell", "pm list packages -s"],
        stdout="package:com.android.systemui\npackage:com.example.ads\n",
    )
    fake_runner.set_response(
        ["adb", "-s", "EMULATOR123", "shell", "pm list packages -3"],
        stdout="package:com.example.thirdparty\n",
    )
    fake_runner.set_response(
        ["adb", "-s", "EMULATOR123", "shell", "pm list packages -d"],
        stdout="package:com.example.ads\n",
    )
    fake_runner.set_response(
        ["adb", "-s", "EMULATOR123", "shell", "pm list packages -e"],
        stdout="package:com.android.systemui\npackage:com.example.thirdparty\n",
    )

    packages = {p.name: p for p in list_packages(fake_client)}
    assert packages["com.android.systemui"].system is True
    assert packages["com.example.ads"].disabled is True
    assert packages["com.example.thirdparty"].third_party is True


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


def test_package_info_dict_round_trip():
    pkg = PackageInfo(name="com.example.foo", system=True, permissions=["android.permission.INTERNET"])
    restored = PackageInfo.from_dict(pkg.to_dict())
    assert restored == pkg
