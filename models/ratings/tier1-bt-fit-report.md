# Elo fit report

Date 2026-11-27, commit 248d75e. Anchor rule: classical browser engine at 20000 nodes = 1000, per scale.
Bootstrap: 2000 replicates over openings, seed 1; prior 1.0 virtual game per played pair. "+-" is half the 95 % percentile interval.

Input notes:

- edge net-r5-large-gen10-s200__net-r5-small-gen9-s1000__s3003: no result file, 400 games from totals, resampled as independent games (its interval is too narrow by the within-pair correlation)

## 2p-adj-r4

Anchor `ab:browser-r4@n20000` = 1000. 16 edges, 10 configs, 4800 games; share of games without a winner (draws, move limit): 0.003.

### Ratings

| Config | Elo | 95 % | +- | Wald +- | games | edges |
|---|---|---|---|---|---|---|
| `ab:browser-r4@n100000` | 1227 | [1195, 1261] | 33 | 39 | 600 | 2 |
| `net:shared-gen0@s800` | 1187 | [1157, 1218] | 30 | 33 | 900 | 3 |
| `ab:browser-r4@n44721` | 1101 | [1075, 1129] | 27 | 30 | 900 | 3 |
| `ab:browser-r4@n20000` | 1000 | [1000, 1000] | 0 | 0 | 1200 | 4 |
| `net:shared-gen0@s200` | 900 | [874, 928] | 27 | 27 | 1500 | 5 |
| `ab:browser-r4@n10000` | 866 | [836, 896] | 30 | 34 | 900 | 3 |
| `ab:browser-r4@n5000` | 838 | [805, 870] | 32 | 36 | 1200 | 4 |
| `ab:browser-r4@n2236` | 706 | [665, 746] | 40 | 48 | 600 | 2 |
| `ab:browser-r4@n1000` | 604 | [558, 648] | 45 | 49 | 600 | 2 |
| `net:shared-gen0@s50` | 565 | [524, 603] | 40 | 41 | 1200 | 4 |

Pairwise:

| a | b | a - b | 95 % |
|---|---|---|---|
| `net:shared-gen0@s200` | `ab:browser-r4@n5000` | +62 | [36, 90] |
| `net:shared-gen0@s50` | `ab:browser-r4@n1000` | -39 | [-70, -9] |
| `net:shared-gen0@s800` | `ab:browser-r4@n100000` | -40 | [-69, -11] |

### 1. Edges

| Edge | kind | players | games | observed | predicted | residual | edge-only Elo |
|---|---|---|---|---|---|---|---|
| t1.2p-adj-r4.001 | pair | ab:browser-r4@n1000 / ab:browser-r4@n2236 | 300 | 0.315 | 0.357 | -1.74 | -135 |
| t1.2p-adj-r4.002 | pair | ab:browser-r4@n2236 / ab:browser-r4@n5000 | 300 | 0.277 | 0.319 | -1.74 | -167 |
| t1.2p-adj-r4.003 | pair | ab:browser-r4@n5000 / ab:browser-r4@n10000 | 300 | 0.415 | 0.460 | -1.66 | -60 |
| t1.2p-adj-r4.004 | pair | ab:browser-r4@n10000 / ab:browser-r4@n20000 | 300 | 0.292 | 0.316 | -1.03 | -154 |
| t1.2p-adj-r4.005 | pair | ab:browser-r4@n20000 / ab:browser-r4@n44721 | 300 | 0.287 | 0.359 | -2.86 **!** | -158 |
| t1.2p-adj-r4.006 | pair | ab:browser-r4@n44721 / ab:browser-r4@n100000 | 300 | 0.312 | 0.325 | -0.56 | -138 |
| t1.2p-adj-r4.007 | pair | net:shared-gen0@s50 / net:shared-gen0@s200 | 300 | 0.127 | 0.127 | +0.00 | -335 |
| t1.2p-adj-r4.008 | pair | net:shared-gen0@s200 / net:shared-gen0@s800 | 300 | 0.145 | 0.161 | -0.88 | -308 |
| t1.2p-adj-r4.012 | pair | net:shared-gen0@s200 / ab:browser-r4@n5000 | 300 | 0.567 | 0.589 | -0.92 | 47 |
| t1.2p-adj-r4.013 | pair | net:shared-gen0@s200 / ab:browser-r4@n20000 | 300 | 0.340 | 0.361 | -0.80 | -115 |
| t1.2p-adj-r4.014 | pair | net:shared-gen0@s200 / ab:browser-r4@n44721 | 300 | 0.298 | 0.240 | +2.42 | -149 |
| t1.2p-adj-r4.009 | pair | net:shared-gen0@s50 / ab:browser-r4@n1000 | 300 | 0.403 | 0.445 | -1.57 | -68 |
| t1.2p-adj-r4.010 | pair | net:shared-gen0@s50 / ab:browser-r4@n5000 | 300 | 0.190 | 0.172 | +0.84 | -252 |
| t1.2p-adj-r4.011 | pair | net:shared-gen0@s50 / ab:browser-r4@n10000 | 300 | 0.170 | 0.150 | +0.99 | -275 |
| t1.2p-adj-r4.015 | pair | net:shared-gen0@s800 / ab:browser-r4@n20000 | 300 | 0.718 | 0.746 | -1.15 | 163 |
| t1.2p-adj-r4.016 | pair | net:shared-gen0@s800 / ab:browser-r4@n100000 | 300 | 0.455 | 0.442 | +0.50 | -31 |

Observed and predicted: share of the first player (draws as 1/2 for two players, 1/3 for three). Residual: (observed - predicted) / cluster-robust SE; **!** marks |residual| > 2.5.

### 2. Goodness of fit

Deviance 27.5 on 7 df; parametric-bootstrap p = 0.010 (200 replicates, games simulated as independent: the within-pair correlation is not simulated).

### 3. Cycles

| a | b | c | (a-b) + (b-c) - (a-c) | SE | z |
|---|---|---|---|---|---|
| `ab:browser-r4@n10000` | `ab:browser-r4@n5000` | `net:shared-gen0@s50` | +36 | 42 | +0.85 |
| `ab:browser-r4@n20000` | `ab:browser-r4@n44721` | `net:shared-gen0@s200` | -125 | 35 | -3.61 |
| `ab:browser-r4@n20000` | `net:shared-gen0@s200` | `net:shared-gen0@s800` | -30 | 38 | -0.81 |
| `ab:browser-r4@n5000` | `net:shared-gen0@s200` | `net:shared-gen0@s50` | +37 | 41 | +0.89 |

### 4. Leave one edge out

| Edge | observed | predicted from the rest | z |
|---|---|---|---|
| t1.2p-adj-r4.001 | 0.315 | 0.477 | -2.83 |
| t1.2p-adj-r4.002 | 0.277 | 0.431 | -2.78 |
| t1.2p-adj-r4.003 | 0.415 | 0.525 | -2.46 |
| t1.2p-adj-r4.004 | 0.292 | 0.358 | -1.50 |
| t1.2p-adj-r4.005 | 0.287 | 0.448 | -3.80 |
| t1.2p-adj-r4.006 | 0.312 | 0.353 | -0.87 |
| t1.2p-adj-r4.007 | 0.127 | 0.126 | +0.03 |
| t1.2p-adj-r4.008 | 0.145 | 0.172 | -1.03 |
| t1.2p-adj-r4.012 | 0.567 | 0.622 | -1.32 |
| t1.2p-adj-r4.013 | 0.340 | 0.377 | -1.02 |
| t1.2p-adj-r4.014 | 0.298 | 0.189 | +3.37 |
| t1.2p-adj-r4.009 | 0.403 | 0.572 | -2.90 |
| t1.2p-adj-r4.010 | 0.190 | 0.161 | +1.09 |
| t1.2p-adj-r4.011 | 0.170 | 0.136 | +1.32 |
| t1.2p-adj-r4.015 | 0.718 | 0.776 | -1.67 |
| t1.2p-adj-r4.016 | 0.455 | 0.410 | +0.87 |

### 5. Sensitivity

- without the prior: moved by more than their interval: none
  - `ab:browser-r4@n1000`: 602 (-2)
  - `ab:browser-r4@n10000`: 865 (-1)
  - `ab:browser-r4@n100000`: 1228 (+1)
  - `ab:browser-r4@n20000`: 1000 (+0)
  - `ab:browser-r4@n2236`: 705 (-1)
  - `ab:browser-r4@n44721`: 1101 (+0)
  - `ab:browser-r4@n5000`: 837 (-1)
  - `net:shared-gen0@s200`: 900 (-0)
  - `net:shared-gen0@s50`: 563 (-2)
  - `net:shared-gen0@s800`: 1188 (+1)
- alternative anchor `ab:browser-r4@n1000` (level 1): +- against it: `ab:browser-r4@n10000` 38, `ab:browser-r4@n100000` 54, `ab:browser-r4@n20000` 45, `ab:browser-r4@n2236` 31, `ab:browser-r4@n44721` 48, `ab:browser-r4@n5000` 35, `net:shared-gen0@s200` 41, `net:shared-gen0@s50` 30, `net:shared-gen0@s800` 52

### 6. Old against new

No edge measured both in earlier runs and in this study.

### 9. Cost

| Config | CPU-ms per move | CPU hours |
|---|---|---|
| `ab:browser-r4@n1000` | 1.5 | 0.01 |
| `ab:browser-r4@n10000` | 9.5 | 0.11 |
| `ab:browser-r4@n100000` | 113.6 | 0.87 |
| `ab:browser-r4@n20000` | 19.1 | 0.29 |
| `ab:browser-r4@n2236` | 2.1 | 0.02 |
| `ab:browser-r4@n44721` | 41.0 | 0.46 |
| `ab:browser-r4@n5000` | 5.1 | 0.08 |
| `net:shared-gen0@s200` | 96.0 | 1.83 |
| `net:shared-gen0@s50` | 29.2 | 0.44 |
| `net:shared-gen0@s800` | 459.5 | 5.30 |

Hours and kWh per block: see the energy report of the run (scripts/energy/report.py).

## 2p-adj-r5

Anchor `ab:browser-r5@n20000` = 1000. 38 edges, 21 configs, 16400 games; share of games without a winner (draws, move limit): 0.006.

### Ratings

| Config | Elo | 95 % | +- | Wald +- | games | edges |
|---|---|---|---|---|---|---|
| `net:r5-small-gen9@s1000` | 2297 | [2240, 2360] | 60 | 55 | 800 | 2 |
| `net:r5-large-gen10@s200` | 2092 | [2043, 2146] | 52 | 48 | 2000 | 3 |
| `net:r5-small-gen9@s200` | 1968 | [1923, 2017] | 47 | 43 | 3200 | 6 |
| `net:r5-small-gen6@s200` | 1881 | [1837, 1927] | 45 | 42 | 1600 | 4 |
| `net:r5-small-gen9@s100` | 1804 | [1755, 1854] | 49 | 47 | 1200 | 3 |
| `net:r5-small-gen4@s200` | 1778 | [1737, 1824] | 44 | 41 | 1600 | 4 |
| `net:r5-small-gen9@s50` | 1653 | [1609, 1700] | 45 | 47 | 1200 | 3 |
| `net:r5-small-gen2@s200` | 1636 | [1599, 1675] | 38 | 35 | 2800 | 7 |
| `net:r5-small-gen0@s1000` | 1551 | [1511, 1594] | 41 | 42 | 800 | 2 |
| `ab:browser-r5@n500000` | 1533 | [1488, 1579] | 46 | 45 | 1600 | 4 |
| `ab:browser-r5@n223607` | 1430 | [1390, 1473] | 42 | 42 | 1600 | 4 |
| `net:r5-small-gen1@s200` | 1379 | [1345, 1414] | 34 | 32 | 1600 | 4 |
| `net:r5-large-gen0@s200` | 1310 | [1277, 1345] | 34 | 34 | 2000 | 4 |
| `ab:browser-r5@n100000` | 1277 | [1244, 1311] | 33 | 34 | 1600 | 4 |
| `net:r5-small-gen0@s200` | 1204 | [1177, 1232] | 28 | 28 | 3200 | 7 |
| `ab:browser-r5@n44721` | 1151 | [1124, 1177] | 26 | 28 | 1600 | 4 |
| `ab:browser-r5@n20000` | 1000 | [1000, 1000] | 0 | 0 | 1600 | 4 |
| `ab:browser-r5@n10000` | 922 | [888, 956] | 34 | 35 | 800 | 2 |
| `ab:browser-r5@n5000` | 826 | [777, 872] | 48 | 50 | 800 | 2 |
| `ab:browser-r5@n2236` | 661 | [608, 716] | 54 | 62 | 800 | 2 |
| `ab:browser-r5@n1000` | 484 | [420, 550] | 65 | 73 | 400 | 1 |

Pairwise:

| a | b | a - b | 95 % |
|---|---|---|---|
| `net:r5-large-gen0@s200` | `ab:browser-r5@n100000` | +33 | [9, 58] |
| `net:r5-large-gen10@s200` | `ab:browser-r5@n500000` | +559 | [520, 602] |
| `net:r5-small-gen0@s1000` | `ab:browser-r5@n500000` | +17 | [-23, 60] |
| `net:r5-small-gen0@s200` | `ab:browser-r5@n100000` | -73 | [-97, -50] |
| `net:r5-small-gen1@s200` | `ab:browser-r5@n100000` | +102 | [74, 129] |
| `net:r5-small-gen2@s200` | `ab:browser-r5@n500000` | +102 | [69, 137] |
| `net:r5-small-gen4@s200` | `ab:browser-r5@n500000` | +245 | [214, 279] |
| `net:r5-small-gen6@s200` | `ab:browser-r5@n500000` | +348 | [315, 382] |
| `net:r5-small-gen9@s100` | `ab:browser-r5@n500000` | +271 | [240, 302] |
| `net:r5-small-gen9@s1000` | `ab:browser-r5@n500000` | +763 | [716, 816] |
| `net:r5-small-gen9@s200` | `ab:browser-r5@n500000` | +435 | [401, 472] |
| `net:r5-small-gen9@s50` | `ab:browser-r5@n500000` | +119 | [94, 145] |

### 1. Edges

| Edge | kind | players | games | observed | predicted | residual | edge-only Elo |
|---|---|---|---|---|---|---|---|
| t1.2p-adj-r5.001 | pair | ab:browser-r5@n1000 / ab:browser-r5@n2236 | 400 | 0.265 | 0.266 | -0.03 | -177 |
| t1.2p-adj-r5.002 | pair | ab:browser-r5@n2236 / ab:browser-r5@n5000 | 400 | 0.279 | 0.279 | -0.03 | -165 |
| t1.2p-adj-r5.003 | pair | ab:browser-r5@n5000 / ab:browser-r5@n10000 | 400 | 0.365 | 0.365 | -0.02 | -96 |
| t1.2p-adj-r5.004 | pair | ab:browser-r5@n10000 / ab:browser-r5@n20000 | 400 | 0.389 | 0.389 | -0.01 | -79 |
| t1.2p-adj-r5.005 | pair | ab:browser-r5@n20000 / ab:browser-r5@n44721 | 400 | 0.235 | 0.296 | -2.82 **!** | -205 |
| t1.2p-adj-r5.006 | pair | ab:browser-r5@n44721 / ab:browser-r5@n100000 | 400 | 0.287 | 0.325 | -1.66 | -158 |
| t1.2p-adj-r5.007 | pair | ab:browser-r5@n100000 / ab:browser-r5@n223607 | 400 | 0.276 | 0.293 | -0.80 | -167 |
| t1.2p-adj-r5.008 | pair | ab:browser-r5@n223607 / ab:browser-r5@n500000 | 400 | 0.366 | 0.356 | +0.48 | -95 |
| t1.2p-adj-r5.009 | pair | net:r5-small-gen0@s200 / net:r5-small-gen1@s200 | 400 | 0.264 | 0.267 | -0.16 | -178 |
| t1.2p-adj-r5.010 | pair | net:r5-small-gen1@s200 / net:r5-small-gen2@s200 | 400 | 0.142 | 0.186 | -2.55 **!** | -312 |
| t1.2p-adj-r5.011 | pair | net:r5-small-gen2@s200 / net:r5-small-gen4@s200 | 400 | 0.280 | 0.305 | -1.31 | -164 |
| t1.2p-adj-r5.012 | pair | net:r5-small-gen4@s200 / net:r5-small-gen6@s200 | 400 | 0.310 | 0.356 | -2.17 | -139 |
| t1.2p-adj-r5.013 | pair | net:r5-small-gen6@s200 / net:r5-small-gen9@s200 | 400 | 0.335 | 0.377 | -1.92 | -119 |
| t1.2p-adj-r5.017 | pair | net:r5-large-gen0@s200 / net:r5-small-gen0@s200 | 400 | 0.620 | 0.649 | -1.27 | 85 |
| t1.2p-adj-r5.018 | pair | net:r5-large-gen0@s200 / net:r5-small-gen1@s200 | 400 | 0.334 | 0.402 | -3.05 **!** | -120 |
| t1.2p-adj-r5.019 | pair | net:r5-small-gen9@s50 / net:r5-small-gen9@s100 | 400 | 0.263 | 0.295 | -1.70 | -179 |
| t1.2p-adj-r5.020 | pair | net:r5-small-gen9@s100 / net:r5-small-gen9@s200 | 400 | 0.275 | 0.280 | -0.22 | -168 |
| t1.2p-adj-r5.021 | pair | net:r5-small-gen9@s200 / net:r5-small-gen9@s1000 | 400 | 0.095 | 0.131 | -2.57 **!** | -392 |
| t1.2p-adj-r5.022 | pair | net:r5-small-gen0@s200 / net:r5-small-gen0@s1000 | 400 | 0.061 | 0.120 | -4.57 **!** | -474 |
| t1.2p-adj-r5.023 | pair | net:r5-small-gen0@s1000 / net:r5-small-gen2@s200 | 400 | 0.323 | 0.380 | -2.71 **!** | -129 |
| t1.2p-adj-r5.024 | pair | net:r5-small-gen9@s50 / ab:browser-r5@n223607 | 400 | 0.786 | 0.783 | +0.18 | 226 |
| t1.2p-adj-r5.026 | pair | net:r5-small-gen9@s100 / ab:browser-r5@n500000 | 400 | 0.799 | 0.826 | -1.45 | 239 |
| t1.2p-adj-r5.032 | pair | net:r5-small-gen0@s200 / ab:browser-r5@n20000 | 400 | 0.731 | 0.764 | -1.64 | 174 |
| t1.2p-adj-r5.034 | pair | net:r5-large-gen0@s200 / ab:browser-r5@n100000 | 400 | 0.591 | 0.548 | +2.00 | 64 |
| t1.2p-adj-r5.014 | pair | net:r5-small-gen0@s200 / net:r5-small-gen2@s200 | 400 | 0.079 | 0.077 | +0.14 | -427 |
| t1.2p-adj-r5.015 | pair | net:r5-small-gen2@s200 / net:r5-small-gen6@s200 | 400 | 0.188 | 0.196 | -0.46 | -255 |
| t1.2p-adj-r5.016 | pair | net:r5-small-gen4@s200 / net:r5-small-gen9@s200 | 400 | 0.247 | 0.251 | -0.18 | -193 |
| t1.2p-adj-r5.025 | pair | net:r5-small-gen9@s50 / ab:browser-r5@n500000 | 400 | 0.695 | 0.665 | +1.50 | 143 |
| t1.2p-adj-r5.027 | pair | net:r5-small-gen6@s200 / ab:browser-r5@n500000 | 400 | 0.870 | 0.881 | -0.67 | 330 |
| t1.2p-adj-r5.028 | pair | net:r5-small-gen4@s200 / ab:browser-r5@n223607 | 400 | 0.906 | 0.881 | +1.80 | 394 |
| t1.2p-adj-r5.029 | pair | net:r5-small-gen2@s200 / ab:browser-r5@n100000 | 400 | 0.866 | 0.887 | -1.34 | 325 |
| t1.2p-adj-r5.030 | pair | net:r5-small-gen2@s200 / ab:browser-r5@n44721 | 400 | 0.900 | 0.942 | -2.84 **!** | 382 |
| t1.2p-adj-r5.031 | pair | net:r5-small-gen1@s200 / ab:browser-r5@n20000 | 400 | 0.873 | 0.899 | -1.70 | 334 |
| t1.2p-adj-r5.033 | pair | net:r5-small-gen0@s200 / ab:browser-r5@n44721 | 400 | 0.642 | 0.576 | +3.06 **!** | 102 |
| net-r5-small-gen9-s200__net-r5-small-gen0-s200__s3002 | pair | net:r5-small-gen9@s200 / net:r5-small-gen0@s200 | 800 | 0.991 | 0.988 | +1.06 | 822 |
| net-r5-large-gen10-s200__net-r5-large-gen0-s200__s3002 | pair | net:r5-large-gen10@s200 / net:r5-large-gen0@s200 | 800 | 0.963 | 0.989 | -5.15 **!** | 565 |
| net-r5-large-gen10-s200__net-r5-small-gen9-s200__s3003 | pair | net:r5-large-gen10@s200 / net:r5-small-gen9@s200 | 800 | 0.680 | 0.671 | +0.63 | 131 |
| net-r5-large-gen10-s200__net-r5-small-gen9-s1000__s3003 | pair | net:r5-large-gen10@s200 / net:r5-small-gen9@s1000 | 400 | 0.270 | 0.235 | +1.56 | -173 |

Observed and predicted: share of the first player (draws as 1/2 for two players, 1/3 for three). Residual: (observed - predicted) / cluster-robust SE; **!** marks |residual| > 2.5.

### 2. Goodness of fit

Deviance 132.1 on 18 df; parametric-bootstrap p = 0.005 (200 replicates, games simulated as independent: the within-pair correlation is not simulated).

### 3. Cycles

| a | b | c | (a-b) + (b-c) - (a-c) | SE | z |
|---|---|---|---|---|---|
| `ab:browser-r5@n100000` | `ab:browser-r5@n44721` | `net:r5-small-gen2@s200` | +100 | 41 | +2.46 |
| `ab:browser-r5@n20000` | `ab:browser-r5@n44721` | `net:r5-small-gen0@s200` | -133 | 30 | -4.43 |
| `ab:browser-r5@n20000` | `net:r5-small-gen0@s200` | `net:r5-small-gen1@s200` | -18 | 32 | -0.57 |
| `ab:browser-r5@n223607` | `ab:browser-r5@n500000` | `net:r5-small-gen9@s50` | -12 | 31 | -0.39 |
| `ab:browser-r5@n44721` | `net:r5-small-gen0@s200` | `net:r5-small-gen2@s200` | -148 | 44 | -3.36 |
| `ab:browser-r5@n500000` | `net:r5-small-gen9@s100` | `net:r5-small-gen9@s50` | +83 | 29 | +2.83 |
| `net:r5-large-gen0@s200` | `net:r5-small-gen0@s200` | `net:r5-small-gen1@s200` | +27 | 30 | +0.90 |
| `net:r5-large-gen10@s200` | `net:r5-small-gen9@s1000` | `net:r5-small-gen9@s200` | +88 | 37 | +2.40 |
| `net:r5-small-gen0@s1000` | `net:r5-small-gen0@s200` | `net:r5-small-gen2@s200` | +176 | 47 | +3.71 |
| `net:r5-small-gen0@s200` | `net:r5-small-gen1@s200` | `net:r5-small-gen2@s200` | -63 | 42 | -1.50 |
| `net:r5-small-gen2@s200` | `net:r5-small-gen4@s200` | `net:r5-small-gen6@s200` | -48 | 30 | -1.59 |
| `net:r5-small-gen4@s200` | `net:r5-small-gen6@s200` | `net:r5-small-gen9@s200` | -65 | 29 | -2.22 |

### 4. Leave one edge out

| Edge | observed | predicted from the rest | z |
|---|---|---|---|
| t1.2p-adj-r5.001 | | not redundant: only edge of ab:browser-r5@n1000 | |
| t1.2p-adj-r5.002 | | not redundant: graph not connected, 2 components: ab:browser-r5@n1000, ab:browser-r5@n2236 | ab:browser-r5@n10000, ab:browser-r5@n100000, ab:browser-r5@n20000, ab:browser-r5@n223607, ab:browser-r5@n44721, ab:browser-r5@n5000, ab:browser-r5@n500000, net:r5-large-gen0@s200, net:r5-large-gen10@s200, net:r5-small-gen0@s1000, net:r5-small-gen0@s200, net:r5-small-gen1@s200, net:r5-small-gen2@s200, net:r5-small-gen4@s200, net:r5-small-gen6@s200, net:r5-small-gen9@s100, net:r5-small-gen9@s1000, net:r5-small-gen9@s200, net:r5-small-gen9@s50 | |
| t1.2p-adj-r5.003 | | not redundant: graph not connected, 2 components: ab:browser-r5@n1000, ab:browser-r5@n2236, ab:browser-r5@n5000 | ab:browser-r5@n10000, ab:browser-r5@n100000, ab:browser-r5@n20000, ab:browser-r5@n223607, ab:browser-r5@n44721, ab:browser-r5@n500000, net:r5-large-gen0@s200, net:r5-large-gen10@s200, net:r5-small-gen0@s1000, net:r5-small-gen0@s200, net:r5-small-gen1@s200, net:r5-small-gen2@s200, net:r5-small-gen4@s200, net:r5-small-gen6@s200, net:r5-small-gen9@s100, net:r5-small-gen9@s1000, net:r5-small-gen9@s200, net:r5-small-gen9@s50 | |
| t1.2p-adj-r5.004 | | not redundant: graph not connected, 2 components: ab:browser-r5@n1000, ab:browser-r5@n10000, ab:browser-r5@n2236, ab:browser-r5@n5000 | ab:browser-r5@n100000, ab:browser-r5@n20000, ab:browser-r5@n223607, ab:browser-r5@n44721, ab:browser-r5@n500000, net:r5-large-gen0@s200, net:r5-large-gen10@s200, net:r5-small-gen0@s1000, net:r5-small-gen0@s200, net:r5-small-gen1@s200, net:r5-small-gen2@s200, net:r5-small-gen4@s200, net:r5-small-gen6@s200, net:r5-small-gen9@s100, net:r5-small-gen9@s1000, net:r5-small-gen9@s200, net:r5-small-gen9@s50 | |
| t1.2p-adj-r5.005 | 0.235 | 0.377 | -3.85 |
| t1.2p-adj-r5.006 | 0.287 | 0.375 | -2.38 |
| t1.2p-adj-r5.007 | 0.276 | 0.326 | -1.24 |
| t1.2p-adj-r5.008 | 0.366 | 0.343 | +0.70 |
| t1.2p-adj-r5.009 | 0.264 | 0.268 | -0.18 |
| t1.2p-adj-r5.010 | 0.142 | 0.220 | -3.00 |
| t1.2p-adj-r5.011 | 0.280 | 0.334 | -1.68 |
| t1.2p-adj-r5.012 | 0.310 | 0.401 | -2.77 |
| t1.2p-adj-r5.013 | 0.335 | 0.427 | -2.60 |
| t1.2p-adj-r5.017 | 0.620 | 0.675 | -1.73 |
| t1.2p-adj-r5.018 | 0.334 | 0.485 | -4.09 |
| t1.2p-adj-r5.019 | 0.263 | 0.342 | -2.25 |
| t1.2p-adj-r5.020 | 0.275 | 0.287 | -0.32 |
| t1.2p-adj-r5.021 | 0.095 | 0.164 | -2.94 |
| t1.2p-adj-r5.022 | 0.061 | 0.171 | -4.57 |
| t1.2p-adj-r5.023 | 0.323 | 0.597 | -4.52 |
| t1.2p-adj-r5.024 | 0.786 | 0.780 | +0.22 |
| t1.2p-adj-r5.026 | 0.799 | 0.845 | -1.94 |
| t1.2p-adj-r5.032 | 0.731 | 0.793 | -2.23 |
| t1.2p-adj-r5.034 | 0.591 | 0.485 | +2.82 |
| t1.2p-adj-r5.014 | 0.079 | 0.076 | +0.17 |
| t1.2p-adj-r5.015 | 0.188 | 0.202 | -0.56 |
| t1.2p-adj-r5.016 | 0.247 | 0.253 | -0.21 |
| t1.2p-adj-r5.025 | 0.695 | 0.633 | +1.91 |
| t1.2p-adj-r5.027 | 0.870 | 0.888 | -0.89 |
| t1.2p-adj-r5.028 | 0.906 | 0.867 | +2.02 |
| t1.2p-adj-r5.029 | 0.866 | 0.896 | -1.60 |
| t1.2p-adj-r5.030 | 0.900 | 0.951 | -3.23 |
| t1.2p-adj-r5.031 | 0.873 | 0.911 | -2.12 |
| t1.2p-adj-r5.033 | 0.642 | 0.511 | +3.88 |
| net-r5-small-gen9-s200__net-r5-small-gen0-s200__s3002 | 0.991 | 0.988 | +1.07 |
| net-r5-large-gen10-s200__net-r5-large-gen0-s200__s3002 | 0.963 | 0.993 | -5.71 |
| net-r5-large-gen10-s200__net-r5-small-gen9-s200__s3003 | 0.680 | 0.625 | +1.34 |
| net-r5-large-gen10-s200__net-r5-small-gen9-s1000__s3003 | 0.270 | 0.167 | +3.00 |

### 5. Sensitivity

- without the prior: moved by more than their interval: none
  - `ab:browser-r5@n1000`: 483 (-1)
  - `ab:browser-r5@n10000`: 921 (-0)
  - `ab:browser-r5@n100000`: 1278 (+1)
  - `ab:browser-r5@n20000`: 1000 (+0)
  - `ab:browser-r5@n2236`: 660 (-1)
  - `ab:browser-r5@n223607`: 1432 (+2)
  - `ab:browser-r5@n44721`: 1151 (+1)
  - `ab:browser-r5@n5000`: 825 (-0)
  - `ab:browser-r5@n500000`: 1536 (+3)
  - `net:r5-large-gen0@s200`: 1311 (+1)
  - `net:r5-large-gen10@s200`: 2097 (+5)
  - `net:r5-small-gen0@s1000`: 1553 (+3)
  - `net:r5-small-gen0@s200`: 1204 (+1)
  - `net:r5-small-gen1@s200`: 1381 (+2)
  - `net:r5-small-gen2@s200`: 1639 (+3)
  - `net:r5-small-gen4@s200`: 1782 (+4)
  - `net:r5-small-gen6@s200`: 1885 (+4)
  - `net:r5-small-gen9@s100`: 1808 (+4)
  - `net:r5-small-gen9@s1000`: 2303 (+6)
  - `net:r5-small-gen9@s200`: 1973 (+5)
  - `net:r5-small-gen9@s50`: 1656 (+3)
- without saturated edges (6 dropped): moved by more than their interval: `net:r5-small-gen0@s1000`
  - `ab:browser-r5@n1000`: 484 (+0)
  - `ab:browser-r5@n10000`: 922 (+0)
  - `ab:browser-r5@n100000`: 1272 (-5)
  - `ab:browser-r5@n20000`: 1000 (+0)
  - `ab:browser-r5@n2236`: 661 (+0)
  - `ab:browser-r5@n223607`: 1442 (+12)
  - `ab:browser-r5@n44721`: 1149 (-1)
  - `ab:browser-r5@n5000`: 826 (+0)
  - `ab:browser-r5@n500000`: 1543 (+10)
  - `net:r5-large-gen0@s200`: 1295 (-16)
  - `net:r5-large-gen10@s200`: 2113 (+21)
  - `net:r5-small-gen0@s1000`: 1487 (-64)
  - `net:r5-small-gen0@s200`: 1209 (+6)
  - `net:r5-small-gen1@s200`: 1371 (-8)
  - `net:r5-small-gen2@s200`: 1615 (-20)
  - `net:r5-small-gen4@s200`: 1768 (-10)
  - `net:r5-small-gen6@s200`: 1880 (-2)
  - `net:r5-small-gen9@s100`: 1816 (+12)
  - `net:r5-small-gen9@s1000`: 2285 (-11)
  - `net:r5-small-gen9@s200`: 1982 (+14)
  - `net:r5-small-gen9@s50`: 1664 (+11)
- new data only: moved by more than their interval: `net:r5-small-gen9@s1000`
  - `ab:browser-r5@n1000`: 484 (-0)
  - `ab:browser-r5@n10000`: 922 (-0)
  - `ab:browser-r5@n100000`: 1280 (+2)
  - `ab:browser-r5@n20000`: 1000 (+0)
  - `ab:browser-r5@n2236`: 661 (-0)
  - `ab:browser-r5@n223607`: 1448 (+18)
  - `ab:browser-r5@n44721`: 1152 (+1)
  - `ab:browser-r5@n5000`: 826 (-0)
  - `ab:browser-r5@n500000`: 1557 (+24)
  - `net:r5-large-gen0@s200`: 1297 (-13)
  - `net:r5-large-gen10@s200`: not rated
  - `net:r5-small-gen0@s1000`: 1559 (+8)
  - `net:r5-small-gen0@s200`: 1203 (-1)
  - `net:r5-small-gen1@s200`: 1377 (-2)
  - `net:r5-small-gen2@s200`: 1647 (+12)
  - `net:r5-small-gen4@s200`: 1803 (+25)
  - `net:r5-small-gen6@s200`: 1908 (+27)
  - `net:r5-small-gen9@s100`: 1835 (+31)
  - `net:r5-small-gen9@s1000`: 2399 (+103)
  - `net:r5-small-gen9@s200`: 2010 (+41)
  - `net:r5-small-gen9@s50`: 1677 (+25)
- alternative anchor `ab:browser-r5@n1000` (level 1): +- against it: `ab:browser-r5@n10000` 58, `ab:browser-r5@n100000` 74, `ab:browser-r5@n20000` 65, `ab:browser-r5@n2236` 33, `ab:browser-r5@n223607` 78, `ab:browser-r5@n44721` 70, `ab:browser-r5@n5000` 50, `ab:browser-r5@n500000` 79, `net:r5-large-gen0@s200` 75, `net:r5-large-gen10@s200` 86, `net:r5-small-gen0@s1000` 78, `net:r5-small-gen0@s200` 71, `net:r5-small-gen1@s200` 77, `net:r5-small-gen2@s200` 77, `net:r5-small-gen4@s200` 78, `net:r5-small-gen6@s200` 81, `net:r5-small-gen9@s100` 80, `net:r5-small-gen9@s1000` 91, `net:r5-small-gen9@s200` 82, `net:r5-small-gen9@s50` 79

### 6. Old against new

No edge measured both in earlier runs and in this study.

### 9. Cost

| Config | CPU-ms per move | CPU hours |
|---|---|---|
| `ab:browser-r5@n1000` | 1.9 | 0.01 |
| `ab:browser-r5@n10000` | 17.1 | 0.23 |
| `ab:browser-r5@n100000` | 211.9 | 5.84 |
| `ab:browser-r5@n20000` | 40.1 | 1.10 |
| `ab:browser-r5@n2236` | 4.0 | 0.06 |
| `ab:browser-r5@n223607` | 474.3 | 13.03 |
| `ab:browser-r5@n44721` | 90.7 | 2.49 |
| `ab:browser-r5@n5000` | 8.6 | 0.12 |
| `ab:browser-r5@n500000` | 1083.8 | 29.41 |
| `net:r5-large-gen0@s200` | 631.6 | 22.99 |
| `net:r5-large-gen10@s200` | 596.8 | 16.86 |
| `net:r5-small-gen0@s1000` | 613.0 | 8.53 |
| `net:r5-small-gen0@s200` | 123.9 | 6.94 |
| `net:r5-small-gen1@s200` | 127.5 | 3.58 |
| `net:r5-small-gen2@s200` | 123.5 | 5.90 |
| `net:r5-small-gen4@s200` | 117.5 | 3.15 |
| `net:r5-small-gen6@s200` | 118.4 | 3.17 |
| `net:r5-small-gen9@s100` | 60.7 | 1.20 |
| `net:r5-small-gen9@s1000` | 546.1 | 3.57 |
| `net:r5-small-gen9@s200` | 110.5 | 5.91 |
| `net:r5-small-gen9@s50` | 27.8 | 0.55 |

Equal-time configs: `net:r5-large-gen0@s200` 631.6 ms, `net:r5-small-gen0@s1000` 613.0 ms, `net:r5-small-gen9@s1000` 546.1 ms; ratio max/min 1.16.

Hours and kWh per block: see the energy report of the run (scripts/energy/report.py).

## 3p-adj-r4

Anchor `ab:browser-3p@n20000` = 1000. 32 edges, 10 configs, 14400 games; share of games without a winner (draws, move limit): 0.000.

### Ratings

| Config | Elo | 95 % | +- | Wald +- | games | edges |
|---|---|---|---|---|---|---|
| `net:shared-gen0@s800` | 1521 | [1495, 1549] | 27 | 25 | 2700 | 6 |
| `net:shared-gen0@s200` | 1331 | [1312, 1349] | 18 | 20 | 4500 | 10 |
| `ab:browser-3p@n100000` | 1237 | [1213, 1260] | 24 | 27 | 1800 | 4 |
| `ab:browser-3p@n44721` | 1202 | [1183, 1222] | 19 | 20 | 2700 | 6 |
| `net:shared-gen0@s50` | 1102 | [1078, 1124] | 23 | 24 | 3600 | 8 |
| `ab:browser-3p@n20000` | 1000 | [1000, 1000] | 0 | 0 | 3600 | 8 |
| `ab:browser-3p@n10000` | 839 | [820, 859] | 19 | 22 | 2700 | 6 |
| `ab:browser-3p@n5000` | 793 | [766, 820] | 27 | 26 | 3600 | 8 |
| `ab:browser-3p@n2236` | 759 | [726, 789] | 32 | 32 | 1800 | 4 |
| `ab:browser-3p@n1000` | 749 | [717, 779] | 31 | 33 | 1800 | 4 |

Pairwise:

| a | b | a - b | 95 % |
|---|---|---|---|
| `net:shared-gen0@s200` | `ab:browser-3p@n100000` | +94 | [73, 117] |
| `net:shared-gen0@s50` | `ab:browser-3p@n20000` | +102 | [78, 124] |
| `net:shared-gen0@s800` | `ab:browser-3p@n100000` | +284 | [259, 312] |

### 1. Edges

| Edge | kind | players | games | observed | predicted | residual | edge-only Elo |
|---|---|---|---|---|---|---|---|
| t1.3p-adj-r4.001 | one-vs-two | ab:browser-3p@n1000 / ab:browser-3p@n2236 | 450 | 0.324 | 0.321 | +0.55 | -7 |
| t1.3p-adj-r4.002 | one-vs-two | ab:browser-3p@n2236 / ab:browser-3p@n1000 | 450 | 0.342 | 0.345 | -0.59 | 7 |
| t1.3p-adj-r4.003 | one-vs-two | ab:browser-3p@n2236 / ab:browser-3p@n5000 | 450 | 0.296 | 0.291 | +0.27 | -30 |
| t1.3p-adj-r4.004 | one-vs-two | ab:browser-3p@n5000 / ab:browser-3p@n2236 | 450 | 0.378 | 0.379 | -0.06 | 34 |
| t1.3p-adj-r4.005 | one-vs-two | ab:browser-3p@n5000 / ab:browser-3p@n10000 | 450 | 0.258 | 0.277 | -0.93 | -63 |
| t1.3p-adj-r4.006 | one-vs-two | ab:browser-3p@n10000 / ab:browser-3p@n5000 | 450 | 0.360 | 0.394 | -1.54 | 20 |
| t1.3p-adj-r4.007 | one-vs-two | ab:browser-3p@n10000 / ab:browser-3p@n20000 | 450 | 0.198 | 0.166 | +1.82 | -123 |
| t1.3p-adj-r4.008 | one-vs-two | ab:browser-3p@n20000 / ab:browser-3p@n10000 | 450 | 0.533 | 0.556 | -1.03 | 144 |
| t1.3p-adj-r4.009 | one-vs-two | ab:browser-3p@n20000 / ab:browser-3p@n44721 | 450 | 0.113 | 0.136 | -1.45 | -237 |
| t1.3p-adj-r4.010 | one-vs-two | ab:browser-3p@n44721 / ab:browser-3p@n20000 | 450 | 0.593 | 0.614 | -0.93 | 186 |
| t1.3p-adj-r4.011 | one-vs-two | ab:browser-3p@n44721 / ab:browser-3p@n100000 | 450 | 0.324 | 0.290 | +2.60 **!** | -7 |
| t1.3p-adj-r4.012 | one-vs-two | ab:browser-3p@n100000 / ab:browser-3p@n44721 | 450 | 0.340 | 0.380 | -3.01 **!** | 5 |
| t1.3p-adj-r4.013 | one-vs-two | net:shared-gen0@s50 / net:shared-gen0@s200 | 450 | 0.098 | 0.118 | -1.44 | -266 |
| t1.3p-adj-r4.014 | one-vs-two | net:shared-gen0@s200 / net:shared-gen0@s50 | 450 | 0.691 | 0.650 | +2.11 | 260 |
| t1.3p-adj-r4.015 | one-vs-two | net:shared-gen0@s200 / net:shared-gen0@s800 | 450 | 0.102 | 0.144 | -2.89 **!** | -257 |
| t1.3p-adj-r4.016 | one-vs-two | net:shared-gen0@s800 / net:shared-gen0@s200 | 450 | 0.669 | 0.598 | +3.06 **!** | 243 |
| t1.3p-adj-r4.023 | one-vs-two | net:shared-gen0@s200 / ab:browser-3p@n5000 | 450 | 0.893 | 0.916 | -1.49 | 490 |
| t1.3p-adj-r4.024 | one-vs-two | ab:browser-3p@n5000 / net:shared-gen0@s200 | 450 | 0.004 | 0.022 | -5.17 **!** | -820 |
| t1.3p-adj-r4.025 | one-vs-two | net:shared-gen0@s200 / ab:browser-3p@n20000 | 450 | 0.760 | 0.769 | -0.45 | 321 |
| t1.3p-adj-r4.026 | one-vs-two | ab:browser-3p@n20000 / net:shared-gen0@s200 | 450 | 0.078 | 0.070 | +0.61 | -309 |
| t1.3p-adj-r4.027 | one-vs-two | net:shared-gen0@s200 / ab:browser-3p@n44721 | 450 | 0.524 | 0.512 | +0.58 | 137 |
| t1.3p-adj-r4.028 | one-vs-two | ab:browser-3p@n44721 / net:shared-gen0@s200 | 450 | 0.129 | 0.192 | -3.49 **!** | -212 |
| t1.3p-adj-r4.017 | one-vs-two | net:shared-gen0@s50 / ab:browser-3p@n1000 | 450 | 0.780 | 0.790 | -0.55 | 340 |
| t1.3p-adj-r4.018 | one-vs-two | ab:browser-3p@n1000 / net:shared-gen0@s50 | 450 | 0.044 | 0.062 | -1.77 | -413 |
| t1.3p-adj-r4.019 | one-vs-two | net:shared-gen0@s50 / ab:browser-3p@n5000 | 450 | 0.729 | 0.745 | -0.76 | 292 |
| t1.3p-adj-r4.020 | one-vs-two | ab:browser-3p@n5000 / net:shared-gen0@s50 | 450 | 0.047 | 0.078 | -2.95 **!** | -404 |
| t1.3p-adj-r4.021 | one-vs-two | net:shared-gen0@s50 / ab:browser-3p@n10000 | 450 | 0.698 | 0.692 | +0.27 | 266 |
| t1.3p-adj-r4.022 | one-vs-two | ab:browser-3p@n10000 / net:shared-gen0@s50 | 450 | 0.064 | 0.100 | -3.06 **!** | -344 |
| t1.3p-adj-r4.029 | one-vs-two | net:shared-gen0@s800 / ab:browser-3p@n20000 | 450 | 0.862 | 0.909 | -2.71 **!** | 439 |
| t1.3p-adj-r4.030 | one-vs-two | ab:browser-3p@n20000 / net:shared-gen0@s800 | 450 | 0.016 | 0.024 | -1.53 | -600 |
| t1.3p-adj-r4.031 | one-vs-two | net:shared-gen0@s800 / ab:browser-3p@n100000 | 450 | 0.647 | 0.718 | -3.16 **!** | 225 |
| t1.3p-adj-r4.032 | one-vs-two | ab:browser-3p@n100000 / net:shared-gen0@s800 | 450 | 0.091 | 0.089 | +0.15 | -279 |

Observed and predicted: share of the first player (draws as 1/2 for two players, 1/3 for three). Residual: (observed - predicted) / cluster-robust SE; **!** marks |residual| > 2.5.

### 2. Goodness of fit

Deviance 159.8 on 85 df; parametric-bootstrap p = 0.005 (200 replicates, games simulated as independent: the within-pair correlation is not simulated).

### 3. Cycles

| a | b | c | (a-b) + (b-c) - (a-c) | SE | z |
|---|---|---|---|---|---|
| `ab:browser-3p@n10000` | `ab:browser-3p@n5000` | `net:shared-gen0@s50` | -1 | 35 | -0.03 |
| `ab:browser-3p@n20000` | `ab:browser-3p@n44721` | `net:shared-gen0@s200` | -71 | 28 | -2.53 |
| `ab:browser-3p@n20000` | `net:shared-gen0@s200` | `net:shared-gen0@s800` | -45 | 47 | -0.96 |
| `ab:browser-3p@n5000` | `net:shared-gen0@s200` | `net:shared-gen0@s50` | -44 | 67 | -0.65 |

### 4. Leave one edge out

| Edge | observed | predicted from the rest | z |
|---|---|---|---|
| t1.3p-adj-r4.001 | 0.324 | 0.320 | +0.27 |
| t1.3p-adj-r4.002 | 0.342 | 0.348 | -0.28 |
| t1.3p-adj-r4.003 | 0.296 | 0.288 | +0.32 |
| t1.3p-adj-r4.004 | 0.378 | 0.380 | -0.07 |
| t1.3p-adj-r4.005 | 0.258 | 0.286 | -1.10 |
| t1.3p-adj-r4.006 | 0.360 | 0.413 | -1.87 |
| t1.3p-adj-r4.007 | 0.198 | 0.156 | +2.09 |
| t1.3p-adj-r4.008 | 0.533 | 0.575 | -1.34 |
| t1.3p-adj-r4.009 | 0.113 | 0.141 | -1.59 |
| t1.3p-adj-r4.010 | 0.593 | 0.626 | -1.16 |
| t1.3p-adj-r4.011 | 0.324 | 0.272 | +2.59 |
| t1.3p-adj-r4.012 | 0.340 | 0.407 | -2.80 |
| t1.3p-adj-r4.013 | 0.098 | 0.123 | -1.57 |
| t1.3p-adj-r4.014 | 0.691 | 0.621 | +2.54 |
| t1.3p-adj-r4.015 | 0.102 | 0.156 | -3.09 |
| t1.3p-adj-r4.016 | 0.669 | 0.547 | +3.88 |
| t1.3p-adj-r4.023 | 0.893 | 0.921 | -1.69 |
| t1.3p-adj-r4.024 | 0.004 | 0.023 | -4.87 |
| t1.3p-adj-r4.025 | 0.760 | 0.773 | -0.53 |
| t1.3p-adj-r4.026 | 0.078 | 0.069 | +0.65 |
| t1.3p-adj-r4.027 | 0.524 | 0.505 | +0.70 |
| t1.3p-adj-r4.028 | 0.129 | 0.211 | -3.82 |
| t1.3p-adj-r4.017 | 0.780 | 0.798 | -0.73 |
| t1.3p-adj-r4.018 | 0.044 | 0.065 | -1.86 |
| t1.3p-adj-r4.019 | 0.729 | 0.752 | -0.92 |
| t1.3p-adj-r4.020 | 0.047 | 0.083 | -3.04 |
| t1.3p-adj-r4.021 | 0.698 | 0.689 | +0.33 |
| t1.3p-adj-r4.022 | 0.064 | 0.106 | -3.15 |
| t1.3p-adj-r4.029 | 0.862 | 0.920 | -3.12 |
| t1.3p-adj-r4.030 | 0.016 | 0.025 | -1.55 |
| t1.3p-adj-r4.031 | 0.647 | 0.773 | -4.28 |
| t1.3p-adj-r4.032 | 0.091 | 0.089 | +0.17 |

### 5. Sensitivity

- without the prior: moved by more than their interval: none
  - `ab:browser-3p@n1000`: 749 (-0)
  - `ab:browser-3p@n10000`: 839 (-0)
  - `ab:browser-3p@n100000`: 1238 (+1)
  - `ab:browser-3p@n20000`: 1000 (+0)
  - `ab:browser-3p@n2236`: 758 (-0)
  - `ab:browser-3p@n44721`: 1202 (+0)
  - `ab:browser-3p@n5000`: 793 (-0)
  - `net:shared-gen0@s200`: 1332 (+1)
  - `net:shared-gen0@s50`: 1102 (+0)
  - `net:shared-gen0@s800`: 1522 (+1)
- without saturated edges (8 dropped): moved by more than their interval: none
  - `ab:browser-3p@n1000`: 763 (+13)
  - `ab:browser-3p@n10000`: 850 (+11)
  - `ab:browser-3p@n100000`: 1230 (-7)
  - `ab:browser-3p@n20000`: 1000 (+0)
  - `ab:browser-3p@n2236`: 773 (+14)
  - `ab:browser-3p@n44721`: 1196 (-6)
  - `ab:browser-3p@n5000`: 809 (+15)
  - `net:shared-gen0@s200`: 1323 (-9)
  - `net:shared-gen0@s50`: 1095 (-7)
  - `net:shared-gen0@s800`: 1512 (-9)
- alternative anchor `ab:browser-3p@n1000` (level 1): +- against it: `ab:browser-3p@n10000` 26, `ab:browser-3p@n100000` 36, `ab:browser-3p@n20000` 31, `ab:browser-3p@n2236` 10, `ab:browser-3p@n44721` 32, `ab:browser-3p@n5000` 20, `net:shared-gen0@s200` 31, `net:shared-gen0@s50` 27, `net:shared-gen0@s800` 38

### 6. Old against new

No edge measured both in earlier runs and in this study.

### 7. Three players

Seat factors (seat 0 = 1): seat 1 0.835 [0.745, 0.935], seat 2 0.793 [0.697, 0.892].

Role swap (X against 2 x Y and Y against 2 x X, closed form 400 log10(2p/(1-p))):

| X | Y | X - Y from X as candidate | from Y as candidate | z |
|---|---|---|---|---|
| `ab:browser-3p@n1000` | `ab:browser-3p@n2236` | -7 | -7 | -0.70 |
| `ab:browser-3p@n1000` | `net:shared-gen0@s50` | -413 | -340 | -1.57 |
| `ab:browser-3p@n10000` | `ab:browser-3p@n20000` | -123 | -144 | +0.79 |
| `ab:browser-3p@n10000` | `ab:browser-3p@n5000` | +20 | +63 | -1.73 |
| `ab:browser-3p@n10000` | `net:shared-gen0@s50` | -344 | -266 | -2.12 |
| `ab:browser-3p@n100000` | `ab:browser-3p@n44721` | +5 | +7 | -0.24 |
| `ab:browser-3p@n100000` | `net:shared-gen0@s800` | -279 | -225 | -1.74 |
| `ab:browser-3p@n20000` | `ab:browser-3p@n44721` | -237 | -186 | -1.66 |
| `ab:browser-3p@n20000` | `net:shared-gen0@s200` | -309 | -321 | +0.29 |
| `ab:browser-3p@n20000` | `net:shared-gen0@s800` | -600 | -439 | -2.14 |
| `ab:browser-3p@n2236` | `ab:browser-3p@n5000` | -30 | -34 | +0.18 |
| `ab:browser-3p@n44721` | `net:shared-gen0@s200` | -212 | -137 | -2.42 |
| `ab:browser-3p@n5000` | `net:shared-gen0@s200` | -820 | -490 | -2.66 |
| `ab:browser-3p@n5000` | `net:shared-gen0@s50` | -404 | -292 | -2.43 |
| `net:shared-gen0@s200` | `net:shared-gen0@s50` | +260 | +266 | -0.18 |
| `net:shared-gen0@s200` | `net:shared-gen0@s800` | -257 | -243 | -0.46 |

Verdict: **one-dimensional** (role-swap or mixed-table |z| > 3, or mixed-table p < 0.01, means three-player ratings are reported per table type only). Kingmaking is not removable and applies to every three-player figure.


### 9. Cost

| Config | CPU-ms per move | CPU hours |
|---|---|---|
| `ab:browser-3p@n1000` | 1.0 | 0.01 |
| `ab:browser-3p@n10000` | 10.2 | 0.18 |
| `ab:browser-3p@n100000` | 90.9 | 0.97 |
| `ab:browser-3p@n20000` | 20.4 | 0.46 |
| `ab:browser-3p@n2236` | 2.0 | 0.02 |
| `ab:browser-3p@n44721` | 41.3 | 0.67 |
| `ab:browser-3p@n5000` | 4.9 | 0.11 |
| `net:shared-gen0@s200` | 115.5 | 3.12 |
| `net:shared-gen0@s50` | 30.5 | 0.68 |
| `net:shared-gen0@s800` | 469.1 | 7.54 |

Hours and kWh per block: see the energy report of the run (scripts/energy/report.py).

## 8. Bridge

1000 means "level 3 of this game type"; numbers of different columns are not comparable beyond that.

| Bridge | 2p-adj-r4 | 3p-adj-r4 |
|---|---|---|
| ab1000 | 604 +- 45 | 749 +- 31 |
| ab100000 | 1227 +- 33 | 1237 +- 24 |
| ab20000 | 1000 +- 0 | 1000 +- 0 |
| ab5000 | 838 +- 32 | 793 +- 27 |
| shared200 | 900 +- 27 | 1331 +- 18 |
| shared50 | 565 +- 40 | 1102 +- 23 |
| shared800 | 1187 +- 30 | 1521 +- 27 |

- 2p-adj-r4 against 3p-adj-r4: chi2 = 1616.5 on 6 df, p = 0, offset +0, slope 1.34, tau 234: **separate** (one offset rejected (p = 0); tau = 234 > 50)
