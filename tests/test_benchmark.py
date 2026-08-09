from __future__ import annotations

import pytest

from relite.adb import AdbClient
from relite.benchmark import (
    MeasurementFailedError,
    PssStats,
    TimingStats,
    measure_app_start,
    measure_pss,
    measure_pss_settled,
    measure_pss_settled_stats,
    run_benchmark,
    run_launcher_ab_benchmark,
    validate_runs,
)
from relite.validate import ValidationError


def test_timing_stats_median_min_max_p95():
    stats = TimingStats(samples_ms=[100.0, 200.0, 150.0, 300.0, 120.0])
    assert stats.median == 150.0
    assert stats.minimum == 100.0
    assert stats.maximum == 300.0
    assert stats.p95 >= stats.median


def test_measure_pss_parses_total_pss_line(fake_client: AdbClient, fake_runner):
    fake_runner.set_response(
        ["adb", "-s", "EMULATOR123", "shell", "dumpsys meminfo com.example.app"],
        stdout="        TOTAL   294753   168200\n\n           TOTAL PSS:   123456            TOTAL RSS:   200000\n",
    )
    assert measure_pss(fake_client, "com.example.app") == 123456


def test_measure_pss_returns_none_when_app_not_running(fake_client: AdbClient, fake_runner):
    fake_runner.set_response(
        ["adb", "-s", "EMULATOR123", "shell", "dumpsys meminfo com.example.app"],
        stdout="No process found for: com.example.app\n",
    )
    assert measure_pss(fake_client, "com.example.app") is None


def test_measure_pss_settled_force_stops_and_restarts_before_measuring(
    fake_client: AdbClient, fake_runner, monkeypatch: pytest.MonkeyPatch
):
    """Real-device finding (RMX5303, 2026-08-08): PSS measured immediately
    after cold start is not representative — measure_pss_settled must
    force-stop, restart, and wait before reading PSS."""
    monkeypatch.setattr("relite.benchmark.time.sleep", lambda _seconds: None)

    fake_runner.set_response(
        ["adb", "-s", "EMULATOR123", "shell", "dumpsys meminfo com.example.app"],
        stdout="           TOTAL PSS:    74817            TOTAL RSS:   150000\n",
    )

    pss = measure_pss_settled(fake_client, "com.example.app", ".MainActivity", settle_seconds=45)

    assert pss == 74817
    called = [" ".join(c) for c in fake_runner.calls]
    assert any("force-stop com.example.app" in c for c in called)
    assert any("am start -W com.example.app/.MainActivity" in c for c in called)


def test_timing_stats_raises_when_constructed_empty():
    """Section 35: no fabricated 0ms sample — an empty sample list is a
    measurement failure, not a valid zero reading."""
    with pytest.raises(MeasurementFailedError):
        TimingStats(samples_ms=[])


def test_timing_stats_accepts_genuine_zero_sample():
    """A real `TotalTime: 0` from `am start -W` is a valid sample, not a
    failure — must not be confused with "no sample at all"."""
    stats = TimingStats(samples_ms=[0.0])
    assert stats.median == 0.0


def test_pss_stats_median_min_max():
    stats = PssStats(samples_kb=[100, 120, 90])
    assert stats.median == 100
    assert stats.minimum == 90
    assert stats.maximum == 120


def test_pss_stats_raises_when_constructed_empty():
    with pytest.raises(MeasurementFailedError):
        PssStats(samples_kb=[])


@pytest.mark.parametrize("runs", [0, -1, -5])
def test_validate_runs_rejects_non_positive(runs):
    with pytest.raises(ValueError):
        validate_runs(runs)


def test_validate_runs_accepts_one():
    assert validate_runs(1) == 1


def test_measure_app_start_rejects_zero_runs(fake_client: AdbClient, fake_runner):
    with pytest.raises(ValueError):
        measure_app_start(fake_client, "com.example.app", ".MainActivity", runs=0)


def test_measure_app_start_validates_target(fake_client: AdbClient, fake_runner):
    with pytest.raises(ValidationError):
        measure_app_start(fake_client, "com.example; rm -rf /", ".MainActivity", runs=1)
    assert fake_runner.calls == []


def test_measure_app_start_raises_when_every_run_fails_to_parse(fake_client: AdbClient, fake_runner):
    fake_runner.set_response(
        ["adb", "-s", "EMULATOR123", "shell", "am force-stop com.example.app"], stdout="ok"
    )
    fake_runner.set_response(
        ["adb", "-s", "EMULATOR123", "shell", "am start -W com.example.app/.MainActivity"],
        stdout="Error: Activity not started",  # no TotalTime line at all
    )
    with pytest.raises(MeasurementFailedError):
        measure_app_start(fake_client, "com.example.app", ".MainActivity", runs=2)


def test_measure_pss_settled_stats_returns_median_of_multiple_runs(
    fake_client: AdbClient, fake_runner, monkeypatch: pytest.MonkeyPatch
):
    monkeypatch.setattr("relite.benchmark.time.sleep", lambda _seconds: None)
    fake_runner.set_response(
        ["adb", "-s", "EMULATOR123", "shell", "dumpsys meminfo com.example.app"],
        stdout="           TOTAL PSS:    50000            TOTAL RSS:   100000\n",
    )
    stats = measure_pss_settled_stats(fake_client, "com.example.app", ".MainActivity", runs=3)
    assert stats.samples_kb == [50000, 50000, 50000]
    assert stats.median == 50000


def test_run_benchmark_records_measurement_failure_instead_of_faking_a_result(
    fake_client: AdbClient, fake_runner, monkeypatch: pytest.MonkeyPatch
):
    monkeypatch.setattr("relite.benchmark.time.sleep", lambda _seconds: None)
    fake_runner.set_response(
        ["adb", "-s", "EMULATOR123", "shell", "am start -W com.example.app/.MainActivity"],
        stdout="Error: Activity not started",
    )
    result = run_benchmark(
        fake_client, "test",
        app_targets=[{"label": "app", "package": "com.example.app", "activity": ".MainActivity"}],
        runs=1,
    )
    assert "app" not in result.app_start_times
    assert any("app" in f for f in result.measurement_failures)


def test_run_benchmark_rejects_zero_runs(fake_client: AdbClient, fake_runner):
    with pytest.raises(ValueError):
        run_benchmark(fake_client, "test", runs=0)


def test_run_launcher_ab_benchmark_alternates_and_collects_both_targets(
    fake_client: AdbClient, fake_runner, monkeypatch: pytest.MonkeyPatch
):
    monkeypatch.setattr("relite.benchmark.time.sleep", lambda _seconds: None)
    fake_runner.set_response(
        ["adb", "-s", "EMULATOR123", "shell", "dumpsys meminfo io.relite.home"],
        stdout="           TOTAL PSS:    50000            TOTAL RSS:   100000\n",
    )
    fake_runner.set_response(
        ["adb", "-s", "EMULATOR123", "shell", "dumpsys meminfo com.android.launcher3"],
        stdout="           TOTAL PSS:    90000            TOTAL RSS:   150000\n",
    )
    target_a = {"label": "relite_home", "package": "io.relite.home", "activity": ".ui.MainActivity"}
    target_b = {
        "label": "stock_launcher", "package": "com.android.launcher3",
        "activity": "com.android.launcher3.uioverrides.QuickstepLauncher",
    }

    result = run_launcher_ab_benchmark(fake_client, target_a, target_b, runs_each=2, shuffle=False)

    assert len(result.samples) == 4
    assert result.stats_for("relite_home").median == 50000
    assert result.stats_for("stock_launcher").median == 90000
    # alternation: labels shouldn't all be grouped together
    labels_in_order = [s.label for s in result.samples]
    assert labels_in_order == ["relite_home", "stock_launcher", "relite_home", "stock_launcher"]
