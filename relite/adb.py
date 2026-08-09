"""Thin, testable wrapper around the `adb` command line tool.

Every other module talks to the device only through :class:`AdbClient` so
that the whole engine can be exercised in CI against fixtures instead of a
real phone (see `tests/fixtures/`).
"""

from __future__ import annotations

import shutil
import subprocess
from dataclasses import dataclass, field
from enum import StrEnum


class DeviceState(StrEnum):
    DEVICE = "device"
    OFFLINE = "offline"
    UNAUTHORIZED = "unauthorized"
    NO_DEVICE = "no-device"
    MULTIPLE = "multiple"
    UNKNOWN = "unknown"


class AdbError(RuntimeError):
    """Raised when adb itself cannot be reached or a command fails to run."""


class AdbTimeoutError(AdbError):
    """Raised when an adb command exceeds its timeout."""


class AdbUnavailableError(AdbError):
    """Raised when no usable device is available (offline/unauthorized/none/multiple)."""

    def __init__(self, state: DeviceState, message: str | None = None):
        self.state = state
        super().__init__(message or f"adb device unavailable: {state.value}")


@dataclass
class CommandResult:
    args: list[str]
    returncode: int
    stdout: str
    stderr: str

    @property
    def ok(self) -> bool:
        return self.returncode == 0

    def lines(self) -> list[str]:
        return [line for line in self.stdout.splitlines() if line.strip()]


@dataclass
class AdbClient:
    """Wraps `adb` invocations for a single target device/serial.

    Pass a custom `runner` (any callable matching `subprocess.run`'s
    signature) to unit-test callers without touching a real adb binary.
    """

    serial: str | None = None
    adb_path: str = "adb"
    default_timeout: float = 15.0
    runner: object = field(default=subprocess.run, repr=False)

    def _binary_exists(self) -> bool:
        return shutil.which(self.adb_path) is not None or self.adb_path != "adb"

    def _base_args(self) -> list[str]:
        args = [self.adb_path]
        if self.serial:
            args += ["-s", self.serial]
        return args

    def raw(self, *args: str, timeout: float | None = None, check: bool = False) -> CommandResult:
        full_args = self._base_args() + list(args)
        try:
            proc = self.runner(  # type: ignore[operator]
                full_args,
                capture_output=True,
                text=True,
                timeout=timeout or self.default_timeout,
            )
        except FileNotFoundError as exc:
            raise AdbError(f"adb binary not found at '{self.adb_path}'") from exc
        except subprocess.TimeoutExpired as exc:
            raise AdbTimeoutError(f"adb command timed out: {' '.join(full_args)}") from exc

        result = CommandResult(
            args=full_args,
            returncode=proc.returncode,
            stdout=proc.stdout or "",
            stderr=proc.stderr or "",
        )
        if check and not result.ok:
            raise AdbError(f"command failed ({result.returncode}): {' '.join(full_args)}\n{result.stderr}")
        return result

    def shell(self, command: str, timeout: float | None = None, check: bool = False) -> CommandResult:
        return self.raw("shell", command, timeout=timeout, check=check)

    def getprop(self, name: str) -> str:
        return self.shell(f"getprop {name}").stdout.strip()

    def list_devices(self) -> dict[str, DeviceState]:
        result = self.raw("devices", "-l")
        devices: dict[str, DeviceState] = {}
        for line in result.lines():
            if line.lower().startswith("list of devices"):
                continue
            parts = line.split()
            if len(parts) < 2:
                continue
            serial, state = parts[0], parts[1]
            try:
                devices[serial] = DeviceState(state)
            except ValueError:
                devices[serial] = DeviceState.UNKNOWN
        return devices

    def require_single_device(self) -> str:
        """Return the serial of the single usable device, or raise AdbUnavailableError.

        Section 17 (v0.3.0): when `self.serial` (`--serial SERIAL`) is
        set, it must be verified against the actual `adb devices -l`
        output, not returned unconditionally. A prior version returned
        the requested serial as soon as *some* device was usable,
        without checking that the requested one was actually present —
        so `--serial WRONG` while a different device happened to be
        connected wouldn't fail here at all, only later and less clearly
        when an actual `adb -s WRONG ...` command failed.
        """
        devices = self.list_devices()

        if not devices:
            raise AdbUnavailableError(DeviceState.NO_DEVICE, "no device connected")

        if self.serial:
            state = devices.get(self.serial)
            if state is None:
                raise AdbUnavailableError(
                    DeviceState.NO_DEVICE, f"requested serial not connected: {self.serial}"
                )
            if state != DeviceState.DEVICE:
                raise AdbUnavailableError(
                    state, f"requested device present but not ready ({state.value}): {self.serial}"
                )
            return self.serial

        usable = {s: st for s, st in devices.items() if st == DeviceState.DEVICE}
        if not usable:
            # report the most informative non-ready state we found
            state = next(iter(devices.values()))
            raise AdbUnavailableError(state, f"device present but not ready: {state.value}")
        if len(usable) > 1:
            raise AdbUnavailableError(
                DeviceState.MULTIPLE,
                f"multiple devices attached, specify --serial: {', '.join(usable)}",
            )
        return next(iter(usable))
