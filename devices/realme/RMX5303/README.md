# RMX5303 — realme C71

First ReLite target device. **Validated end-to-end against a physical
unit** (2026-08-08) — see `findings.md` for the full evidence trail.

| | |
|---|---|
| Model | RMX5303 |
| Marketing name | realme C71 |
| SoC family | UNISOC T7250 (UMS9230) |
| Physical RAM | ~8 GB (corrected from an original 6 GB assumption via live `/proc/meminfo` — see `device.yaml`) |
| OEM RAM Expansion | offers Off/6GB/10GB/16GB, confirmed **off by default** |
| Firmware | detect live via `relite device` — never assumed |
| Recommended profile | `performance` |

## Status

Validated. `packages.yaml`/`protected-packages.yaml` were cross-checked
against this device's actual `pm`/`dumpsys` output and on-device
Settings "App info" labels — not guessed. See `findings.md` for the
dated log and `PACKAGES.md` for the generated, always-current package
table.

## Using ReLite with this device

```bash
relite doctor
relite device
relite snapshot --name stock
relite scan
relite analyze
relite plan --profile performance
relite apply --profile performance
relite status
relite benchmark --label stock
relite benchmark --label performance
relite report
```

If anything looks wrong, `relite restore --snapshot stock` reverts every
package and setting change back to the recorded stock state — see
`docs/recovery.md`.

## Files

- `device.yaml` — device identity and hardware facts, including the
  `benchmark_targets`/`pss_targets` `relite benchmark` reads.
- `packages.yaml` — package classification database (category, confidence,
  per-profile action, risk, reason, rollback support, and
  `platform_limitation` where a real-device quirk means an action can't
  fully take effect regardless of what ReLite does).
- `protected-packages.yaml` — hard safety floor; always wins over
  `packages.yaml`.
- `PACKAGES.md` — **generated** human-readable table of the above two
  files; regenerate with `scripts/generate_package_docs.py` after
  editing either source, never hand-edit it directly. See `docs/profiles.md`.
- `tuning.yaml` — animation scale and RAM Expansion tuning parameters.
- `findings.md` — dated log of what has actually been observed on a real
  RMX5303, including bugs found and fixed during validation.
