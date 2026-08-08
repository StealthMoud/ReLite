from __future__ import annotations

import pytest

from relite.adb import AdbClient
from relite.benchmark import TimingStats, measure_pss, measure_pss_settled


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
