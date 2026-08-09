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


def test_require_single_device_with_requested_serial_missing(fake_runner):
    """Section 17: requesting a serial that isn't connected at all must
    raise, not silently substitute a different connected device."""
    client = AdbClient(serial="REQUESTED", runner=fake_runner)
    fake_runner.set_response(
        ["adb", "-s", "REQUESTED", "devices", "-l"],
        stdout="List of devices attached\nOTHER\tdevice\n",
    )
    with pytest.raises(AdbUnavailableError) as exc:
        client.require_single_device()
    assert exc.value.state == DeviceState.NO_DEVICE


def test_require_single_device_with_requested_serial_offline(fake_runner):
    client = AdbClient(serial="REQUESTED", runner=fake_runner)
    fake_runner.set_response(
        ["adb", "-s", "REQUESTED", "devices", "-l"],
        stdout="List of devices attached\nREQUESTED\toffline\nOTHER\tdevice\n",
    )
    with pytest.raises(AdbUnavailableError) as exc:
        client.require_single_device()
    assert exc.value.state == DeviceState.OFFLINE


def test_require_single_device_with_requested_serial_unauthorized(fake_runner):
    client = AdbClient(serial="REQUESTED", runner=fake_runner)
    fake_runner.set_response(
        ["adb", "-s", "REQUESTED", "devices", "-l"],
        stdout="List of devices attached\nREQUESTED\tunauthorized\n",
    )
    with pytest.raises(AdbUnavailableError) as exc:
        client.require_single_device()
    assert exc.value.state == DeviceState.UNAUTHORIZED


def test_require_single_device_with_two_devices_connected_returns_exact_requested(fake_runner):
    """The historical bug this fixes: with --serial set and *some* other
    device also usable, the requested serial must be the one actually
    returned/verified, not just assumed correct because len(usable) >= 1."""
    client = AdbClient(serial="REQUESTED", runner=fake_runner)
    fake_runner.set_response(
        ["adb", "-s", "REQUESTED", "devices", "-l"],
        stdout="List of devices attached\nREQUESTED\tdevice\nOTHER\tdevice\n",
    )
    assert client.require_single_device() == "REQUESTED"


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
