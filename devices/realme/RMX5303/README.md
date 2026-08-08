# RMX5303 — realme C71

First supported ReLite device.

| | |
|---|---|
| Model | RMX5303 |
| Marketing name | realme C71 |
| SoC family | UNISOC T7250 (UMS9230) |
| Physical RAM | 6 GB |
| OEM RAM Expansion | up to +16 GB (storage-backed, not physical) |
| Firmware | detect live via `relite device` — never assumed |

## Status

Research-and-tooling-ready. The classification database in this directory
(`packages.yaml`, `protected-packages.yaml`) is a **candidate seed**, not a
validated on-device inventory — see `findings.md` for what has and hasn't
been confirmed against a real device yet, and `NOTICE.md` for why this
approach was chosen over hard-coding a "known bad" list.

## Using ReLite with this device

```bash
relite doctor
relite device
relite snapshot --name stock
relite scan
relite analyze
relite plan --profile safe
relite apply --profile safe
relite benchmark --label stock
relite benchmark --label safe
relite report
```

If anything looks wrong, `relite restore --snapshot stock` reverts every
package and setting change back to the recorded stock state.

## Files

- `device.yaml` — device identity and hardware facts.
- `packages.yaml` — package classification database (category, confidence,
  per-profile action, risk, reason, rollback support).
- `protected-packages.yaml` — hard safety floor; always wins over
  `packages.yaml`.
- `tuning.yaml` — animation scale and RAM Expansion tuning parameters.
- `findings.md` — running log of what has actually been observed on a real
  RMX5303 (or explicitly notes that a step is still pending hardware
  access).
