# Energy report: net5-cpu-power

Metric: wall (README: what it contains). Data from 2026-11-22T22:14:46Z to 2026-11-22T23:12:46Z.

Baseline: 12.59 W, from marker phase 'baseline' (0:09:48 covered, from 2026-11-22T22:14:35Z).

| phase | start (UTC) | duration | covered | mean W | peak W | kWh | kWh above baseline | notes |
|---|---|---|---|---|---|---|---|---|
| baseline | 2026-11-22T22:14:35Z | 0:10:00 | 0:09:48 | 12.6 | 14.0 | 0.0021 | 0.0000 |  |
| cpu-small | 2026-11-22T22:25:35Z | 0:11:00 | 0:11:00 | 46.1 | 59.4 | 0.0085 | 0.0061 |  |
| cpu-large | 2026-11-22T22:37:35Z | 0:11:01 | 0:11:01 | 42.7 | 53.4 | 0.0078 | 0.0055 |  |
| gpu-small | 2026-11-22T22:49:36Z | 0:05:30 | 0:05:30 | 48.5 | 57.8 | 0.0044 | 0.0033 |  |
| gpu-large | 2026-11-22T22:56:07Z | 0:05:30 | 0:05:30 | 41.8 | 51.0 | 0.0038 | 0.0027 |  |
| baseline-after | 2026-11-22T23:02:37Z | 0:10:00 | 0:10:00 | 11.0 | 12.3 | 0.0018 | -0.0003 |  |
| **sum of phases** (without baseline) | | | 0:43:02 | | | **0.0264** | **0.0174** | |
| **whole file** (run net5-cpu-power) | 2026-11-22T22:14:46Z | | 0:58:00 | 31.1 | 59.4 | **0.0301** | **0.0179** | |

Peak W is the highest one-minute mean. Duration is from the markers; covered is the time with data.
