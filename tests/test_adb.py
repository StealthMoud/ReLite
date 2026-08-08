from __future__ import annotations

import subprocess

import pytest

from relite.adb import AdbClient, AdbError, AdbTimeoutError, AdbUnavailableError, DeviceState


def test_list_devices_parses_single_ready_device(fake_client: AdbClient, fake_runner):
    fake_runner.set_response(
        ["adb", "-s", "EMULATOR123", "devices", "-l"],
        stdout="List of devices attached\nEMULATOR123\tdevice usb:1-1 product:x model:y\n",
    )
    devices = fake_client.list_devices()
    assert devices["EMULATOR123"] == DeviceState.DEVICE


def test_require_single_device_raises_on_no_device(fake_client: AdbClient, fake_runner):
    fake_runner.set_response(["adb", "-s", "EMULATOR123", "devices", "-l"], stdout="List of devices attached\n")
    with pytest.raises(AdbUnavailableError) as exc:
        fake_client.require_single_device()
    assert exc.value.state == DeviceState.NO_DEVICE


def test_require_single_device_raises_on_unauthorized(fake_client: AdbClient, fake_runner):
    fake_runner.set_response(
        ["adb", "-s", "EMULATOR123", "devices", "-l"],
        stdout="List of devices attached\nEMULATOR123\tunauthorized\n",
    )
    with pytest.raises(AdbUnavailableError) as exc:
        fake_client.require_single_device()
    assert exc.value.state == DeviceState.UNAUTHORIZED


def test_require_single_device_raises_on_multiple(fake_runner):
    client = AdbClient(runner=fake_runner)  # no serial pinned
    fake_runner.set_response(
        ["adb", "devices", "-l"],
        stdout="List of devices attached\nAAA\tdevice\nBBB\tdevice\n",
    )
    with pytest.raises(AdbUnavailableError) as exc:
        client.require_single_device()
    assert exc.value.state == DeviceState.MULTIPLE


def test_offline_state_is_reported(fake_client: AdbClient, fake_runner):
    fake_runner.set_response(
        ["adb", "-s", "EMULATOR123", "devices", "-l"],
        stdout="List of devices attached\nEMULATOR123\toffline\n",
    )
    with pytest.raises(AdbUnavailableError) as exc:
        fake_client.require_single_device()
    assert exc.value.state == DeviceState.OFFLINE


def test_command_timeout_raises_adb_timeout_error(fake_client: AdbClient):
    def timeout_runner(args, capture_output, text, timeout):
        raise subprocess.TimeoutExpired(cmd=args, timeout=timeout)

    fake_client.runner = timeout_runner
    with pytest.raises(AdbTimeoutError):
        fake_client.shell("getprop ro.product.model")


def test_missing_adb_binary_raises_adb_error(fake_client: AdbClient):
    def missing_runner(args, capture_output, text, timeout):
        raise FileNotFoundError()

    fake_client.runner = missing_runner
    with pytest.raises(AdbError):
        fake_client.shell("getprop ro.product.model")


def test_getprop_strips_output(fake_client: AdbClient, fake_runner):
    fake_runner.set_response(
        ["adb", "-s", "EMULATOR123", "shell", "getprop ro.product.model"],
        stdout="RMX5303\n",
    )
    assert fake_client.getprop("ro.product.model") == "RMX5303"
