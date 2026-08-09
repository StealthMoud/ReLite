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
        self.sequences: dict[tuple[str, ...], list[FakeCompletedProcess]] = {}
        self.calls: list[list[str]] = []

    def set_response(self, args: list[str], stdout: str = "", stderr: str = "", returncode: int = 0) -> None:
        self.responses[tuple(args)] = FakeCompletedProcess(returncode, stdout, stderr)

    def set_response_sequence(self, args: list[str], responses: list[FakeCompletedProcess]) -> None:
        """Return a different canned response each successive time `args` is
        called — needed to simulate live device state changing *because of*
        an earlier call in the same test (e.g. verifying package state after
        a restore actually mutated it)."""
        self.sequences[tuple(args)] = list(responses)

    def __call__(self, args: list[str], capture_output: bool, text: bool, timeout: float) -> FakeCompletedProcess:
        self.calls.append(args)
        key = tuple(args)
        if key in self.sequences and self.sequences[key]:
            response = self.sequences[key].pop(0)
            if not self.sequences[key]:
                del self.sequences[key]
            return response
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
