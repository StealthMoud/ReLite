# ReLite benchmarking methodology

No performance claim ships without a measurement behind it (master plan
section 22). This document describes exactly how `relite benchmark` and
the scripts in `benchmarks/scripts/` produce numbers, so results can be
reproduced and audited rather than taken on faith.

## Principles

- **Multiple runs, always.** Every timing metric (app cold/warm start,
  boot time) is sampled at least `DEFAULT_RUNS = 5` times
  (`relite/benchmark.py`). We report median, min, max, and p95 — never a
  single run.
- **No cherry-picking.** All samples from a run are stored in the result
  JSON (`samples_ms`), not just the summary statistics, so a reviewer can
  recompute or discard outliers themselves.
- **Host-observed timing is labeled as such.** Boot-completion timing is
  measured by polling from the host machine, not from device-internal
  instrumentation — see `measure_boot_time()`. It is never presented as
  millisecond-precise.
- **Cold start means cold.** `measure_app_start()` force-stops the target
  app before every sample so `am start -W`'s `TotalTime` reflects an
  actual cold start, not a resume.
- **No artificial benchmark tuning.** ReLite never changes CPU governors,
  disables thermal throttling, or otherwise puts the device into a
  non-representative state before measuring (see `docs/safety.md`).
  Benchmarks measure the device in the state a real user would have it.

## What gets measured

| Category | Metric | Source |
|---|---|---|
| Package load | enabled / disabled / system package counts | `pm list packages` |
| Memory | `/proc/meminfo` breakdown (`MemTotal`, `MemAvailable`, etc.) | `cat /proc/meminfo` |
| App start | cold start `TotalTime` | `am start -W` after `am force-stop` |
| App start | warm start `TotalTime` | `am start -W` after `KEYCODE_HOME` |
| Boot | host-observed time to `sys.boot_completed=1` | polling `getprop` after a reboot |
| Frame timing | `dumpsys gfxinfo` / framestats, where supported | manual/scripted, see below |

### A note on "warm start" and `TotalTime: 0`

Real-device testing (RMX5303, 2026-08-08) found that `measure_warm_start`'s
approach — press `KEYCODE_HOME`, then `am start` the same activity again —
frequently reports `TotalTime: 0` with `Warning: Activity not started,
intent has been delivered to currently running top-most instance.` This
is not a bug: for a `singleTask`-style root activity whose task is still
resident, Android's own instrumentation genuinely does zero start work —
it's a window-focus change, not an activity creation. Treat `0 ms` warm
starts as real, accurate data (the platform's own measurement of "no
start work happened"), not as a failed sample — but be aware it does not
capture the perceived UI transition time a user would actually see.

### A note on PSS settle time

Real-device testing (RMX5303, 2026-08-08, comparing ReLite Home to the
stock launcher) found that `dumpsys meminfo`'s `TOTAL PSS` right after a
cold start (`am start -W` returning) is not representative of steady
-state memory use — it includes freshly-touched-but-soon-reclaimed pages
from class loading, resource decoding, and JIT compilation. On this
device, a single continuous process measured repeatedly without
restarting showed PSS drop from ~229 MB to ~80 MB between 5s and 15s
post-launch, then stay flat through 60s. A batch of automated
force-stop-then-restart cycles, by contrast, produced a *misleadingly
consistent* ~229 MB across 5 runs — restarting the process resets the
decay clock every time, so "5 runs of a 15s-post-start reading" is not
the same measurement as "5 runs of a properly-settled reading." **Always
verify the settle point with a decay curve (measure the same running
instance at several increasing intervals) before trusting a fixed
-delay, multi-restart batch** — don't assume any particular number of
seconds is "enough" without checking on the actual device. This
methodology's PSS comparisons use a 45s settle time, confirmed flat via
a decay curve first.

## Running a comparison

```bash
relite snapshot --name stock
relite benchmark --label stock

relite apply --profile safe
relite benchmark --label safe

relite report
```

`relite report` reads every `benchmarks/results/<model>/*.json` file and
renders `latest.md` / `latest.json` / `latest.csv` — a diff table, not
marketing copy. See `relite/report.py`.

## Boot benchmark procedure

`benchmarks/scripts/boot_benchmark.sh` wraps the reboot + poll loop:

1. Record the host clock.
2. `adb reboot`.
3. Wait for `adb wait-for-device`.
4. Poll `adb shell getprop sys.boot_completed` every second.
5. Record elapsed host time once it reads `1`.
6. Repeat `N` times (default 3) and report median/min/max.

This intentionally does **not** claim boot-loader-level or
millisecond-accurate timing — see section 23 of the master plan.

## Frame/jank analysis

`benchmarks/scripts/frame_stats.sh` resets and reads `dumpsys gfxinfo
<package> framestats` around a scripted interaction (e.g. opening the app
drawer and scrolling it). This is exploratory tooling, not yet wired into
the automated `relite benchmark` command — see `benchmarks/results/RMX5303/`
for whether a run has been recorded.

## What "stock" and "ReLite" mean in a report

- **stock**: a snapshot taken immediately after first boot / factory
  reset, before any ReLite action has been applied.
- **safe / performance / maximum**: the same device, after `relite apply
  --profile <name>`, with no other manual changes in between.

Comparisons across different physical units, different firmware
versions, or with manual changes mixed in are not valid "stock vs.
ReLite" comparisons and should not be reported as such.
