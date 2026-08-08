# RMX5303 ReLite Benchmark

Firmware: realme/RMX5303EEA/RE60B8:15/AP3A.240905.015.A2/V.R4T2.1776089958:user/release-keys

| Metric | stock | safe | performance | maximum | Change (last vs. first) |
|---|---|---|---|---|---|
| Enabled packages | 400 | 397 | 390 | 385 | -3.8% |
| Disabled packages | 2 | 5 | 12 | 17 | +750.0% |
| MemAvailable (kB) | 5176736 | 5359204 | 5252636 | 5132800 | -0.8% |
| camera cold start (median) | 724 ms | 629 ms | 612 ms | 596 ms | -17.7% |
| settings cold start (median) | 1177 ms | 1170 ms | 544 ms | 583 ms | -50.5% |
| camera warm start (median) | 0 ms | 90 ms | 82 ms | 80 ms | n/a |
| settings warm start (median) | 0 ms | 91 ms | 91 ms | 125 ms | n/a |
| launcher PSS | 159,536 kB | 152,107 kB | 173,798 kB | 143,723 kB | -9.9% |
| systemui PSS | 264,348 kB | 249,456 kB | 250,630 kB | 256,731 kB | -2.9% |

_Timing figures are host-observed medians across multiple runs (baseline: `stock`). See `benchmarks/methodology.md`._
