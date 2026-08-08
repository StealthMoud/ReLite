"""Benchmarking harness: package counts, memory, app start timing, and boot
time. Every timing metric reports median/min/max/p95 across multiple runs —
never a single cherry-picked number.
"""

from __future__ import annotations

import re
import statistics
import time
from dataclasses import dataclass, field
from typing import Any

from relite.adb import AdbClient
from relite.packages import list_packages

DEFAULT_RUNS = 5


@dataclass
class TimingStats:
    samples_ms: list[float]

    @property
    def median(self) -> float:
        return statistics.median(self.samples_ms)

    @property
    def minimum(self) -> float:
        return min(self.samples_ms)

    @property
    def maximum(self) -> float:
        return max(self.samples_ms)

    @property
    def p95(self) -> float:
        if len(self.samples_ms) < 2:
            return self.samples_ms[0]
        ordered = sorted(self.samples_ms)
        idx = min(len(ordered) - 1, int(round(0.95 * (len(ordered) - 1))))
        return ordered[idx]

    def to_dict(self) -> dict[str, Any]:
        return {
            "samples_ms": self.samples_ms,
            "median_ms": self.median,
            "min_ms": self.minimum,
            "max_ms": self.maximum,
            "p95_ms": self.p95,
        }


@dataclass
class BenchmarkResult:
    label: str
    enabled_packages: int
    disabled_packages: int
    system_packages: int
    meminfo: dict[str, int]
    app_start_times: dict[str, TimingStats] = field(default_factory=dict)
    boot_time: TimingStats | None = None

    def to_dict(self) -> dict[str, Any]:
        return {
            "label": self.label,
            "enabled_packages": self.enabled_packages,
            "disabled_packages": self.disabled_packages,
            "system_packages": self.system_packages,
            "meminfo": self.meminfo,
            "app_start_times": {k: v.to_dict() for k, v in self.app_start_times.items()},
            "boot_time": self.boot_time.to_dict() if self.boot_time else None,
        }


_TOTAL_START_RE = re.compile(r"TotalTime:\s*(\d+)")
_MEMINFO_LINE_RE = re.compile(r"^([\w()\s.]+?):\s*(\d+)\s*kB", re.MULTILINE)


def measure_meminfo(client: AdbClient) -> dict[str, int]:
    output = client.shell("cat /proc/meminfo").stdout
    return {name.strip(): int(value) for name, value in _MEMINFO_LINE_RE.findall(output)}


def measure_app_start(
    client: AdbClient, package: str, activity: str, runs: int = DEFAULT_RUNS
) -> TimingStats:
    """Cold-start `package/activity` `runs` times via `am start -W`, force-stopping
    between each run so every sample is a genuine cold start."""
    samples: list[float] = []
    for _ in range(runs):
        client.shell(f"am force-stop {package}")
        time.sleep(0.5)
        result = client.shell(f"am start -W {package}/{activity}")
        match = _TOTAL_START_RE.search(result.stdout)
        if match:
            samples.append(float(match.group(1)))
    return TimingStats(samples_ms=samples or [0.0])


def measure_warm_start(
    client: AdbClient, package: str, activity: str, runs: int = DEFAULT_RUNS
) -> TimingStats:
    """Warm-start timing: send the app to background (not force-stopped) between runs."""
    samples: list[float] = []
    for _ in range(runs):
        client.shell("input keyevent KEYCODE_HOME")
        time.sleep(0.3)
        result = client.shell(f"am start -W {package}/{activity}")
        match = _TOTAL_START_RE.search(result.stdout)
        if match:
            samples.append(float(match.group(1)))
    return TimingStats(samples_ms=samples or [0.0])


def measure_boot_time(client: AdbClient, poll_interval: float = 1.0, timeout: float = 180.0) -> float:
    """Host-observed time (seconds) from now until `sys.boot_completed=1`.

    Caller is responsible for triggering the reboot beforehand; this only
    polls. Labeled explicitly as host-observed, not device-internal timing —
    see section 23 of the master plan.
    """
    start = time.monotonic()
    while time.monotonic() - start < timeout:
        result = client.shell("getprop sys.boot_completed")
        if result.stdout.strip() == "1":
            return time.monotonic() - start
        time.sleep(poll_interval)
    raise TimeoutError("device did not report sys.boot_completed within timeout")


def run_benchmark(client: AdbClient, label: str) -> BenchmarkResult:
    packages = list_packages(client)
    return BenchmarkResult(
        label=label,
        enabled_packages=sum(1 for p in packages if p.enabled and not p.disabled),
        disabled_packages=sum(1 for p in packages if p.disabled),
        system_packages=sum(1 for p in packages if p.system),
        meminfo=measure_meminfo(client),
    )
