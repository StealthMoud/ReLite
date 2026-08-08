# RMX5303 benchmark results

Empty by design: no physical RMX5303 was available in the environment
that built this scaffolding, so no `stock`/`safe`/`performance`/`maximum`
benchmark run has been recorded yet.

Once a device is available:

```bash
relite snapshot --name stock
relite benchmark --label stock
relite apply --profile safe
relite benchmark --label safe
relite report
```

`relite benchmark` writes `<label>.json` here; `relite report` reads every
`*.json` in this directory and generates `latest.md` / `latest.json` /
`latest.csv` — see `benchmarks/methodology.md` for what each metric means
and how it's sampled.
