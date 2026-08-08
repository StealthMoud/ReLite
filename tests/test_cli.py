from __future__ import annotations

from pathlib import Path

import pytest
from click.testing import CliRunner

import relite.cli as cli_module
from relite.adb import AdbClient


@pytest.fixture
def runner() -> CliRunner:
    return CliRunner()


def test_main_help(runner: CliRunner):
    result = runner.invoke(cli_module.main, ["--help"])
    assert result.exit_code == 0
    assert "ReLite" in result.output


def test_doctor_runs_without_a_connected_device(runner: CliRunner, monkeypatch: pytest.MonkeyPatch):
    monkeypatch.setattr(cli_module.shutil, "which", lambda _: None)
    result = runner.invoke(cli_module.main, ["doctor"])
    assert result.exit_code == 0
    assert "adb on PATH" in result.output


def test_device_command_reports_no_usable_device(runner: CliRunner, monkeypatch: pytest.MonkeyPatch, fake_runner):
    fake_runner.set_response(["adb", "devices", "-l"], stdout="List of devices attached\n")
    monkeypatch.setattr(cli_module, "AdbClient", lambda serial=None: AdbClient(serial=serial, runner=fake_runner))

    result = runner.invoke(cli_module.main, ["device"])
    assert result.exit_code == 1
    assert "No usable device" in result.output


def test_plan_reports_missing_device_profile(
    runner: CliRunner, monkeypatch: pytest.MonkeyPatch, fake_runner, tmp_path: Path
):
    fake_runner.set_response(
        ["adb", "devices", "-l"], stdout="List of devices attached\nEMULATOR123\tdevice\n"
    )
    # getprop/uname/meminfo/df calls fall through FakeAdbRunner's default (empty stdout).

    monkeypatch.setattr(cli_module, "AdbClient", lambda serial=None: AdbClient(serial=serial, runner=fake_runner))
    monkeypatch.setattr(cli_module, "DEVICES_ROOT", tmp_path / "devices")

    result = runner.invoke(cli_module.main, ["plan", "--profile", "safe"])
    assert result.exit_code == 0
    assert "No device profile" in result.output
