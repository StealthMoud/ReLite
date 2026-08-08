"""Shared test fixtures: a fake adb "runner" so the whole engine can be
exercised without a real device or the `adb` binary being on PATH.
"""

from __future__ import annotations

from dataclasses import dataclass

import pytest

from relite.adb import AdbClient


@dataclass
class FakeCompletedProcess:
    returncode: int = 0
    stdout: str = ""
    stderr: str = ""


class FakeAdbRunner:
    """Mimics subprocess.run's call signature; matches on the full arg list."""

    def __init__(self) -> None:
        self.responses: dict[tuple[str, ...], FakeCompletedProcess] = {}
        self.calls: list[list[str]] = []

    def set_response(self, args: list[str], stdout: str = "", stderr: str = "", returncode: int = 0) -> None:
        self.responses[tuple(args)] = FakeCompletedProcess(returncode, stdout, stderr)

    def __call__(self, args: list[str], capture_output: bool, text: bool, timeout: float) -> FakeCompletedProcess:
        self.calls.append(args)
        key = tuple(args)
        if key in self.responses:
            return self.responses[key]
        # Fall back to substring matching on the joined command for convenience.
        joined = " ".join(args)
        for stored_args, response in self.responses.items():
            if " ".join(stored_args) in joined or joined in " ".join(stored_args):
                return response
        return FakeCompletedProcess(0, "", "")


@pytest.fixture
def fake_runner() -> FakeAdbRunner:
    return FakeAdbRunner()


@pytest.fixture
def fake_client(fake_runner: FakeAdbRunner) -> AdbClient:
    return AdbClient(serial="EMULATOR123", runner=fake_runner)
