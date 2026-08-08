"""Generate human-readable Markdown reports (plus JSON/CSV) comparing
benchmark runs. No marketing language — numbers only.
"""

from __future__ import annotations

import csv
import json
from dataclasses import asdict, is_dataclass
from io import StringIO
from pathlib import Path
from typing import Any

from relite.benchmark import BenchmarkResult


def _pct_change(before: float, after: float) -> str:
    if before == 0:
        return "n/a"
    change = (after - before) / before * 100
    sign = "+" if change >= 0 else ""
    return f"{sign}{change:.1f}%"


def render_markdown(device_model: str, firmware: str, results: list[BenchmarkResult]) -> str:
    lines = [f"# {device_model} ReLite Benchmark", "", f"Firmware: {firmware}", ""]

    if not results:
        lines.append("_No benchmark results recorded yet._")
        return "\n".join(lines) + "\n"

    baseline = results[0]
    header = "| Metric | " + " | ".join(r.label for r in results)
    if len(results) > 1:
        header += " | Change (last vs. first) |"
    else:
        header += " |"
    lines.append(header)
    lines.append("|---" * (len(results) + (2 if len(results) > 1 else 1)) + "|")

    def row(name: str, values: list[Any], before: float | None = None, after: float | None = None) -> str:
        cells = " | ".join(str(v) for v in values)
        line = f"| {name} | {cells} |"
        if len(results) > 1 and before is not None and after is not None:
            line = f"| {name} | {cells} | {_pct_change(before, after)} |"
        return line

    enabled = [r.enabled_packages for r in results]
    lines.append(row("Enabled packages", enabled, enabled[0], enabled[-1]))

    disabled = [r.disabled_packages for r in results]
    lines.append(row("Disabled packages", disabled, disabled[0], disabled[-1]))

    mem_avail = [r.meminfo.get("MemAvailable", 0) for r in results]
    lines.append(row("MemAvailable (kB)", mem_avail, mem_avail[0], mem_avail[-1]))

    all_app_labels = sorted({name for r in results for name in r.app_start_times})
    for app_label in all_app_labels:
        medians = [
            f"{r.app_start_times[app_label].median:.0f} ms" if app_label in r.app_start_times else "—"
            for r in results
        ]
        before_val = results[0].app_start_times.get(app_label)
        after_val = results[-1].app_start_times.get(app_label)
        before = before_val.median if before_val else None
        after = after_val.median if after_val else None
        lines.append(row(f"{app_label} start (median)", medians, before, after))

    lines.append("")
    lines.append(
        "_Timing figures are host-observed medians across multiple runs "
        f"(baseline: `{baseline.label}`). See `benchmarks/methodology.md`._"
    )
    return "\n".join(lines) + "\n"


def _to_plain(obj: Any) -> Any:
    if is_dataclass(obj) and not isinstance(obj, type):
        return asdict(obj)
    return obj


def render_json(results: list[BenchmarkResult]) -> str:
    return json.dumps([r.to_dict() for r in results], indent=2, sort_keys=True) + "\n"


def render_csv(results: list[BenchmarkResult]) -> str:
    buf = StringIO()
    writer = csv.writer(buf)
    writer.writerow(
        ["label", "enabled_packages", "disabled_packages", "system_packages", "mem_available_kb"]
    )
    for r in results:
        mem_available = r.meminfo.get("MemAvailable", 0)
        writer.writerow([r.label, r.enabled_packages, r.disabled_packages, r.system_packages, mem_available])
    return buf.getvalue()


def write_report(
    output_dir: Path,
    device_model: str,
    firmware: str,
    results: list[BenchmarkResult],
) -> dict[str, Path]:
    output_dir.mkdir(parents=True, exist_ok=True)
    paths = {
        "markdown": output_dir / "latest.md",
        "json": output_dir / "latest.json",
        "csv": output_dir / "latest.csv",
    }
    paths["markdown"].write_text(render_markdown(device_model, firmware, results))
    paths["json"].write_text(render_json(results))
    paths["csv"].write_text(render_csv(results))
    return paths
