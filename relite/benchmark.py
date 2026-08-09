"""Benchmarking harness: package counts, memory, app start timing, and boot
time. Every timing metric reports median/min/max/p95 across multiple runs —
never a single cherry-picked number.
"""

from __future__ import annotations

import random
import re
import statistics
import time
from dataclasses import dataclass, field
from datetime import UTC, datetime
from typing import Any

from relite.adb import AdbClient
from relite.packages import list_packages
from relite.validate import validate_component_name, validate_package_name

DEFAULT_RUNS = 5
MIN_RUNS = 1
RECOMMENDED_MIN_RUNS = 3


class MeasurementFailedError(RuntimeError):
    """Raised when every attempt to sample a metric failed to produce a
    real value — never silently substituted with a fabricated 0 (section
    35 of the v0.3.0 plan). A genuine `TotalTime: 0` from `am start -W`
    is a valid sample and is recorded as 0.0; this is only raised when
    *no* run produced a parseable value at all.
    """


def validate_runs(runs: int) -> int:
    """Section 36: reject nonsensical run counts outright."""
    if runs < MIN_RUNS:
        raise ValueError(f"runs must be >= {MIN_RUNS}, got {runs}")
    return runs


@dataclass
class TimingStats:
    samples_ms: list[float]

    def __post_init__(self) -> None:
        if not self.samples_ms:
            raise MeasurementFailedError("no valid timing samples")

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
class PssStats:
    """Section 38: PSS as a statistical measurement — a single reading is
    never advertised as representative of a launcher/app's real memory
    footprint; publish a median of several settled samples instead."""

    samples_kb: list[int]

    def __post_init__(self) -> None:
        if not self.samples_kb:
            raise MeasurementFailedError("no valid PSS samples")

    @property
    def median(self) -> float:
        return statistics.median(self.samples_kb)

    @property
    def minimum(self) -> int:
        return min(self.samples_kb)

    @property
    def maximum(self) -> int:
        return max(self.samples_kb)

    @property
    def p95(self) -> float:
        if len(self.samples_kb) < 2:
            return float(self.samples_kb[0])
        ordered = sorted(self.samples_kb)
        idx = min(len(ordered) - 1, int(round(0.95 * (len(ordered) - 1))))
        return float(ordered[idx])

    def to_dict(self) -> dict[str, Any]:
        return {
            "samples_kb": self.samples_kb,
            "median_kb": self.median,
            "min_kb": self.minimum,
            "max_kb": self.maximum,
            "p95_kb": self.p95,
        }


@dataclass
class BenchmarkResult:
    label: str
    enabled_packages: int
    disabled_packages: int
    system_packages: int
    meminfo: dict[str, int]
    app_start_times: dict[str, TimingStats] = field(default_factory=dict)
    app_warm_start_times: dict[str, TimingStats] = field(default_factory=dict)
    # Section 15 (v0.4.0): PSS is a statistical measurement here too —
    # `pss_kb`, a single int per target, used to be the only thing
    # `run_benchmark()` populated even though `PssStats` (median/min/max/
    # p95 across several samples) already existed as library code nobody
    # called from the normal benchmarking path.
    pss: dict[str, PssStats] = field(default_factory=dict)
    boot_time: TimingStats | None = None
    # Section 35: targets that produced no valid sample at all are
    # recorded here, by target label, rather than silently omitted or
    # faked as a 0ms/0kB result.
    measurement_failures: list[str] = field(default_factory=list)

    @property
    def pss_kb(self) -> dict[str, int]:
        """Backward-compatible single-number view (the pre-v0.4.0 field
        name/shape) — the median of each target's samples."""
        return {label: int(round(stats.median)) for label, stats in self.pss.items()}

    def to_dict(self) -> dict[str, Any]:
        return {
            "label": self.label,
            "enabled_packages": self.enabled_packages,
            "disabled_packages": self.disabled_packages,
            "system_packages": self.system_packages,
            "meminfo": self.meminfo,
            "app_start_times": {k: v.to_dict() for k, v in self.app_start_times.items()},
            "app_warm_start_times": {k: v.to_dict() for k, v in self.app_warm_start_times.items()},
            "pss": {k: v.to_dict() for k, v in self.pss.items()},
            "pss_kb": self.pss_kb,  # kept for tools/scripts reading the older single-number shape
            "boot_time": self.boot_time.to_dict() if self.boot_time else None,
            "measurement_failures": self.measurement_failures,
        }


_TOTAL_START_RE = re.compile(r"TotalTime:\s*(\d+)")
_MEMINFO_LINE_RE = re.compile(r"^([\w()\s.]+?):\s*(\d+)\s*kB", re.MULTILINE)


def measure_meminfo(client: AdbClient) -> dict[str, int]:
    output = client.shell("cat /proc/meminfo").stdout
    return {name.strip(): int(value) for name, value in _MEMINFO_LINE_RE.findall(output)}


_TOTAL_PSS_RE = re.compile(r"TOTAL(?:\s+PSS)?:\s*(\d+)")


def measure_pss(client: AdbClient, package: str) -> int | None:
    """Total PSS (kB) for `package` via `dumpsys meminfo`, or None if not running.

    Measures whatever state the process is already in — appropriate for
    always-on components (e.g. SystemUI) that shouldn't be restarted.
    For a fair cold-start comparison between two apps (e.g. a launcher),
    use `measure_pss_settled`/`measure_pss_settled_stats` instead.
    """
    validate_package_name(package)
    output = client.shell(f"dumpsys meminfo {package}").stdout
    match = _TOTAL_PSS_RE.search(output)
    return int(match.group(1)) if match else None


# Real-device finding (RMX5303, 2026-08-08): PSS measured immediately
# after `am start -W` returns is not representative of steady-state
# memory use — it includes freshly-touched-but-soon-reclaimed pages from
# class loading and resource decoding. A continuous process measured
# repeatedly without restarting showed PSS drop by roughly two-thirds
# between 5s and 15s post-launch, then stay flat through 60s. See
# benchmarks/methodology.md for the full decay-curve writeup.
DEFAULT_SETTLE_SECONDS = 45


def measure_pss_settled(
    client: AdbClient,
    package: str,
    activity: str,
    settle_seconds: float = DEFAULT_SETTLE_SECONDS,
) -> int | None:
    """Force-stop, cold-start, wait for the process to settle, then measure
    PSS — the fair, comparable methodology for benchmarking one app against
    another (e.g. ReLite Home vs. the stock launcher)."""
    validate_package_name(package)
    validate_component_name(f"{package}/{activity}")
    client.shell(f"am force-stop {package}")
    time.sleep(1)
    client.shell(f"am start -W {package}/{activity}")
    time.sleep(settle_seconds)
    return measure_pss(client, package)


def measure_pss_settled_stats(
    client: AdbClient,
    package: str,
    activity: str,
    runs: int = RECOMMENDED_MIN_RUNS,
    settle_seconds: float = DEFAULT_SETTLE_SECONDS,
) -> PssStats:
    """Section 38: `runs` independent settled-PSS samples, published as a
    median rather than one arbitrary reading."""
    validate_runs(runs)
    samples = []
    for _ in range(runs):
        pss = measure_pss_settled(client, package, activity, settle_seconds)
        if pss is not None:
            samples.append(pss)
    return PssStats(samples_kb=samples)


def measure_pss_stats(client: AdbClient, package: str, runs: int = RECOMMENDED_MIN_RUNS) -> PssStats:
    """`runs` PSS samples of a package's *current* running state, without
    force-stopping/restarting it between samples — appropriate for
    always-on components (e.g. SystemUI) where measure_pss_settled_stats's
    cold-start methodology doesn't apply. Still reported as a real
    PssStats rather than a single reading (section 15)."""
    validate_runs(runs)
    samples = []
    for _ in range(runs):
        pss = measure_pss(client, package)
        if pss is not None:
            samples.append(pss)
    return PssStats(samples_kb=samples)


def measure_app_start(
    client: AdbClient, package: str, activity: str, runs: int = DEFAULT_RUNS
) -> TimingStats:
    """Cold-start `package/activity` `runs` times via `am start -W`, force-stopping
    between each run so every sample is a genuine cold start."""
    validate_runs(runs)
    validate_package_name(package)
    validate_component_name(f"{package}/{activity}")
    samples: list[float] = []
    for _ in range(runs):
        client.shell(f"am force-stop {package}")
        time.sleep(0.5)
        result = client.shell(f"am start -W {package}/{activity}")
        match = _TOTAL_START_RE.search(result.stdout)
        if match:
            samples.append(float(match.group(1)))
    return TimingStats(samples_ms=samples)


def measure_warm_start(
    client: AdbClient, package: str, activity: str, runs: int = DEFAULT_RUNS
) -> TimingStats:
    """Warm-start timing: send the app to background (not force-stopped) between runs."""
    validate_runs(runs)
    validate_package_name(package)
    validate_component_name(f"{package}/{activity}")
    samples: list[float] = []
    for _ in range(runs):
        client.shell("input keyevent KEYCODE_HOME")
        time.sleep(0.3)
        result = client.shell(f"am start -W {package}/{activity}")
        match = _TOTAL_START_RE.search(result.stdout)
        if match:
            samples.append(float(match.group(1)))
    return TimingStats(samples_ms=samples)


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


def run_benchmark(
    client: AdbClient,
    label: str,
    app_targets: list[dict[str, str]] | None = None,
    pss_targets: list[dict[str, str]] | None = None,
    runs: int = DEFAULT_RUNS,
) -> BenchmarkResult:
    """Full benchmark sweep: package counts, memory, and — if targets are
    supplied (see devices/<model>/device.yaml `benchmark_targets` /
    `pss_targets`) — app cold/warm start timing and per-package PSS.

    Section 37: target package/activity values come from a device's
    committed YAML, which is human-edited and not immune to becoming
    malformed — validated the same way any other shell-bound input is,
    not assumed safe just because it's checked into the repo.

    Section 35: a target whose measurement fails entirely (no valid
    sample across every run) is recorded in `measurement_failures`
    rather than silently coming out as a fabricated 0ms/absent result.
    """
    validate_runs(runs)
    packages = list_packages(client)
    result = BenchmarkResult(
        label=label,
        enabled_packages=sum(1 for p in packages if p.enabled and not p.disabled),
        disabled_packages=sum(1 for p in packages if p.disabled),
        system_packages=sum(1 for p in packages if p.system),
        meminfo=measure_meminfo(client),
    )

    for target in app_targets or []:
        target_label = target["label"]
        try:
            result.app_start_times[target_label] = measure_app_start(
                client, target["package"], target["activity"], runs=runs
            )
        except MeasurementFailedError:
            result.measurement_failures.append(f"{target_label} (cold start)")
        try:
            result.app_warm_start_times[target_label] = measure_warm_start(
                client, target["package"], target["activity"], runs=runs
            )
        except MeasurementFailedError:
            result.measurement_failures.append(f"{target_label} (warm start)")

    for target in pss_targets or []:
        target_label = target["label"]
        try:
            if "activity" in target:
                # RECOMMENDED_MIN_RUNS, not the (possibly much larger)
                # app-timing `runs` count — each settled sample costs a
                # full DEFAULT_SETTLE_SECONDS wait, unlike a timing sample.
                result.pss[target_label] = measure_pss_settled_stats(
                    client, target["package"], target["activity"], runs=RECOMMENDED_MIN_RUNS
                )
            else:
                result.pss[target_label] = measure_pss_stats(client, target["package"])
        except MeasurementFailedError:
            result.measurement_failures.append(f"{target_label} (pss)")

    return result


@dataclass
class ABSample:
    order_index: int
    label: str
    settled_pss_kb: int
    timestamp: str


@dataclass
class ABBenchmarkResult:
    """Section 39: a controlled A/B comparison run within a single
    session, alternating sample order between two targets to reduce
    time-of-session drift, background-state drift, and thermal-order
    bias — rather than two separately-timed sessions compared after the
    fact (the methodology gap `benchmarks/results/*/v0.2.0.md` and
    `v0.3.0.md` explicitly flag as unaddressed until this)."""

    label_a: str
    label_b: str
    samples: list[ABSample]

    def stats_for(self, label: str) -> PssStats:
        return PssStats(samples_kb=[s.settled_pss_kb for s in self.samples if s.label == label])

    def to_dict(self) -> dict[str, Any]:
        return {
            "label_a": self.label_a,
            "label_b": self.label_b,
            "samples": [
                {
                    "order_index": s.order_index,
                    "label": s.label,
                    "settled_pss_kb": s.settled_pss_kb,
                    "timestamp": s.timestamp,
                }
                for s in self.samples
            ],
            "stats": {
                self.label_a: self.stats_for(self.label_a).to_dict(),
                self.label_b: self.stats_for(self.label_b).to_dict(),
            },
        }


def run_launcher_ab_benchmark(
    client: AdbClient,
    target_a: dict[str, str],
    target_b: dict[str, str],
    runs_each: int = RECOMMENDED_MIN_RUNS,
    settle_seconds: float = DEFAULT_SETTLE_SECONDS,
    *,
    shuffle: bool = True,
) -> ABBenchmarkResult:
    """Section 39: alternate settled-PSS samples between two launcher
    targets within one session. `shuffle=True` (default) randomizes each
    pair's order rather than a fixed A,B,A,B,... pattern, so a
    monotonic drift over the session doesn't systematically favor
    whichever target always goes first/second.
    """
    validate_runs(runs_each)
    validate_package_name(target_a["package"])
    validate_package_name(target_b["package"])
    validate_component_name(f"{target_a['package']}/{target_a['activity']}")
    validate_component_name(f"{target_b['package']}/{target_b['activity']}")

    samples: list[ABSample] = []
    for _ in range(runs_each):
        pair = [target_a, target_b]
        if shuffle:
            random.shuffle(pair)
        for target in pair:
            pss = measure_pss_settled(client, target["package"], target["activity"], settle_seconds)
            if pss is not None:
                samples.append(
                    ABSample(
                        order_index=len(samples),
                        label=target["label"],
                        settled_pss_kb=pss,
                        timestamp=datetime.now(UTC).isoformat(),
                    )
                )

    return ABBenchmarkResult(label_a=target_a["label"], label_b=target_b["label"], samples=samples)
