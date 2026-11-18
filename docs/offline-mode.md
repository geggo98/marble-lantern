# Offline mode: the race tables in the browser

**TL;DR:** The offline browser build ships the classical player `Strategies.BROWSER_SPEC` and the small race table `rows2.bin` only: 2.45 MB as gzip on the wire, 13.1 MB in memory. The large table `rows1.bin` (254 MB) stays out. Measured at an equal node budget, this costs about 56 Elo against `BEST_SPEC`, which is less than one doubling of the node budget buys back (BROWSER_SPEC at 40,000 nodes scores about +89 Elo against `BEST_SPEC` at 20,000). The network player loses nothing, because it never used `rows1.bin`. An optional later "strength pack" (the part of `rows1.bin` with at most two stones in row 1, mirror-folded, about 17 MB gzip or 10 MB zstd) restores full strength in the measurements; it is a design sketch, not built. Not worth doing: a pack for at most one stone in row 1 (still -18 Elo), estimators in place of the table, and loading a subset of 64 KB blocks.

All measurements were made on 2026-11-17 and 2026-11-18 on the branch `exp/offline`, on an Apple M3 Max. Sizes in MB are 10^6 bytes; where older documents ([endgame and opening](endgame-and-opening.md), [C++ comparison](cpp-comparison.md), [C++ comparison plan](cpp-comparison-plan.md)) say "242 MB" for `rows1.bin`, they mean 242 MiB, the same file.

## Contents

- [Terms](#terms)
- [Why rows1.bin cannot ship](#why-rows1bin-cannot-ship)
- [The offline players](#the-offline-players)
- [The table package](#the-table-package)
- [Measurements](#measurements)
- [Theses and verdicts](#theses-and-verdicts)
- [Design sketch: an optional strength pack](#design-sketch-an-optional-strength-pack)
- [What the browser ports need](#what-the-browser-ports-need)
- [Open questions](#open-questions)
- [How to reproduce](#how-to-reproduce)

## Terms

- **`BEST_SPEC`:** the strongest classical player of the project (alpha-beta search), defined in `search/src/main/java/sternhalma/search/Strategies.java` and frozen as reference R3 at the end of round 2, the second round of experiments ([round 2 plan](baseline-round2-plan.md), [reference table](search-and-evaluation.md#integration-of-round-2-measured-2026-11-08)). The option keys of its spec are explained in [search enhancements](search-and-evaluation.md#search-enhancements-measured-2026-11-07); `fut`, `futk` and `fut2` (futility pruning at depth 1 and 2, ordered by distance gain) in [round 2 plan, E06](baseline-round2-plan.md#e06-futility-pruning-at-depth-1-and-2-ordered-by-distance-gain-fut-futk-fut2-with-win-move-limit-and-race-band-exclusions).
- **Row.** The two-player board has rows -8 to 8 in a player's own orientation; the player starts on rows -8 to -5 and its goal corner is rows 5 to 8 (`tablebase/src/main/java/sternhalma/tablebase/Rows.java`). Row 1 is the first row past the centre line and has 8 cells.
- **Race table.** For every configuration of one player's ten pieces inside a region, the exact number of moves to fill the goal when no other pieces are on the board and every configuration on the way stays inside the region, one byte per configuration (`RaceTable.java`, `RaceCounts.java`, both in `tablebase/src/main/java/sternhalma/tablebase/`). Configurations are numbered without keys by the combinatorial number system (`Ranking.java` in the same directory).
- **rows2** (`tablebase/tables/rows2.bin`): region rows 2 to 8, 28 cells, 13,123,110 configurations, 13,123,286 bytes with header.
- **rows1** (`tablebase/tables/rows1.bin`, Git LFS): region rows 1 to 8, 36 cells, 254,186,856 configurations, 254,187,064 bytes.
- **k:** the number of the side's own stones in row 1 when a rows1 entry is read. A configuration with k = 0 lies inside rows2's region.
- **Leaf evaluator:** the evaluation at the leaves of the alpha-beta search. `racelin1-r3` and `racelin-r3` add race arithmetic over a table to the linear evaluation `linear-r3`, whose weights were fitted in round 2 ([bestGain](search-and-evaluation.md#bestgain-e07-evallinear-r3-measured-2026-11-08); registry in `search/src/main/java/sternhalma/search/Evaluators.java`).
- **Race play:** in a passed position (no contact left), a player with `race=1` plays the move whose resulting configuration has the smallest table count, without searching. `racerows=1` or `racerows=2` selects the table. If the table does not cover the configuration, race play follows a solitaire plan from a beam search of width 200 (the "beam plan"; [race play](endgame-and-opening.md#race-play-race1-race2)).
- **Race decision:** for two configurations a and b, whether `count(a) ≤ count(b)`, that is, which of two race positions is ahead. An estimator "agrees" when it gives the same answer as rows1 for a random pair of reachable configurations (`EstimatorReport`, see [Method](#method)).
- **Node-limited arena:** `sternhalma.search.Arena` plays opening pairs (each opening once with each colour) between player A and player B, both with a fixed node budget per move, and prints Elo of A minus B with a 95 % confidence interval ([search and evaluation](search-and-evaluation.md#arena-cpu-column-cost-adjustment-sprt)).
- **Elo and CI below:** "Elo A-B ± x" is the arena's result and its 95 % confidence interval. The two seeds 33 and 34 are the project's report seeds (seeds 31 and 32 are for tuning, see [round 2 plan](baseline-round2-plan.md)); "pooled" is the mean of the two.

## Why rows1.bin cannot ship

`rows1.bin` is 254 MB raw and still 24 MB (xz) to 51 MB (gzip) compressed (see [compressed sizes](#compressed-sizes)). A browser page would download it once and then hold 254 MB in memory per tab. On mobile browsers that is likely to fail, and on desktop it is a large first download for an optional engine. `rows2.bin` is 13.1 MB raw and 2.45 MB as gzip.

Two parts of `BEST_SPEC` use rows1 (`search/src/main/java/sternhalma/search/Strategies.java`):

```
BEST_SPEC = "ab:eval=racelin1-r3," + OPTIONS + ",fut=1,futk=20,fut2=1,race=1,racerows=1"
```

1. The leaf evaluator `racelin1-r3` reads rows1 (registered in `Evaluators.java` as `putRace("racelin1-r3", "linear-r3", 1)`).
2. Race play reads rows1 because of `racerows=1`.

The network player (MCTS) never reads rows1: `MctsOptions.defaults()` (`search/src/main/java/sternhalma/search/MctsOptions.java`) sets `racerows=2`, and all network results of the project were measured with it.

Round 2 of the experiments ([round 2 plan](baseline-round2-plan.md)) measured why rows1 matters at all: race arithmetic in the leaf evaluator gave about +45 Elo with rows1 and nothing with rows2, because rows2's region covers almost no leaf of a search that still has contact ([endgame and opening](endgame-and-opening.md#the-race-module), experiment E08).

## The offline players

**Classical player.** `Strategies.BROWSER_SPEC`:

```
BROWSER_SPEC = "ab:eval=racelin-r3," + OPTIONS + ",fut=1,futk=20,fut2=1,race=1,racerows=2"
OPTIONS      = "pvs=1,killers=1,history=1,lmr=2,asp=1,ttrep=1,mate=1,partial=1"
```

It is `BEST_SPEC` with two changes: the evaluator `racelin-r3`, which has the same arithmetic and weights as `racelin1-r3` but reads rows2 (`putRace("racelin-r3", "linear-r3", 2)`), and race play with `racerows=2`. The weights are identical, so the measured loss is the table alone.

Guards:

- `RaceTable.rows1Requests()` counts every request for the rows1 table in a JVM, whether or not it is loaded.
- `search/src/test/java/sternhalma/search/BrowserSpecTest.java` checks that creating the player, searching and playing race moves leave that counter unchanged, and that a `BEST_SPEC` player does raise it (so the guard can fail). It also pins the search tree of `BROWSER_SPEC` at 20,000 nodes by checksum.
- The C++ port has the same spec and the same counter (`RaceEvaluator::rows1_requests()` in `cpp/src/search/race_eval.cpp`). The tests `browser_spec_parses_and_uses_the_rows2_table_only` and `fingerprint_browser_matches_java_and_never_asks_for_rows1` in `cpp/tests/search_test.cpp` compare the C++ tree bit for bit with the Java golden file `fingerprint-browser.txt` (written into the git-ignored `cpp/golden/` by `./gradlew --no-build-cache :search:goldenExport`) and check the counter.

**Network player.** The MCTS player with its default `racerows=2` needs no change. The probe runs below used `mcts:sims=200,race=1,racerows=2`.

## The table package

`scripts/offline/pack-tables.py` packs `rows2.bin` for the browser build:

```
scripts/offline/pack-tables.py tablebase/tables/rows2.bin [--out DIR]   # default DIR: build/offline
scripts/offline/pack-tables.py --verify tablebase/tables/rows2.bin
```

- It checks the SHA-256 of the input against the constant `RaceTable.SHA256` and refuses a different file (exit 1).
- It writes `rows2.bin.gz` (gzip level 9, mtime 0, so the same input gives the same bytes) and the manifest `rows2.manifest.json`:

  ```json
  {"v":1,"alg":"gzip","rawBytes":13123286,"sha256":"<sha256 of rows2.bin>","gzBytes":2453293,"file":"rows2.bin.gz"}
  ```

- `--verify` decompresses the file and checks `gzBytes`, `rawBytes` and the SHA-256 against the manifest. Both files are written under a temporary name and renamed.

Rules behind the format:

- **gzip, not zstd or xz.** Origin: measured on 2026-09-09 in Chrome 152, `CompressionStream` and `DecompressionStream` accept only `gzip`, `deflate` and `deflate-raw`; `brotli` and `zstd` throw a `TypeError`. Reason: the page decompresses with the browser's built-in stream, so the format must be one that the page can decode without extra code. Limit: zstd would make the table about half the size (1.27 MB instead of 2.45 MB for rows2), but needs a WebAssembly decoder in the page. Expiry: when the target browsers support `zstd` in `DecompressionStream`.
- **Length and checksum in a separate manifest, taken from the source.** Reason: gzip's own CRC32 and length detect a download cut short, but not a source that was already truncated before compression; only the SHA-256 of the raw table catches that, and it must be known before the data arrives. The manifest lists its keys in a fixed order with the metadata first, so a truncated manifest is detectable. Limit: `crypto.subtle.digest` exists only in a secure context (HTTPS or localhost); elsewhere only the length checks remain.

| Package | Raw | gzip -9 | Memory in the page |
|---|---|---|---|
| `rows2.bin` (ships now) | 13,123,286 B | 2,453,293 B (about 19 %) | 13.1 MB |

## Measurements

### Method

**Arena.** Player A is `BEST_SPEC` with a changed race table, player B is `BEST_SPEC` with the exact tables. Both sides search 20,000 nodes per move (about 12.5 CPU-ms per move on the M3 Max), 4 game threads, 300 opening pairs per seed, seeds 33 and 34. A variant changes the table that a role gets through the seam `search/src/main/java/sternhalma/search/RaceTableSource.java`. The variant texts (also accepted by `Arena --a-race-eval`, `--a-race-play`):

| Variant | What a rows1 lookup returns |
|---|---|
| `exact` | the rows1 entry (default) |
| `rows2` | the rows2 entry; for k ≥ 1 rows2 does not cover the configuration and the role falls back (the evaluator keeps its base value, race play uses its beam plan) |
| `maxk:N[:estimate\|rows2]` | the exact rows1 entry if k ≤ N, else the push estimator (default) or the rows2 fallback; `tablebase/src/main/java/sternhalma/tablebase/MaxStonesCounts.java` simulates a table that stores only k ≤ N |
| `estimate[:c0,...,c8]` | the push estimator (`PushEstimator.java` in `tablebase/src/main/java/sternhalma/tablebase/`): push each row-1 stone to the nearest free cell of row 2 and count one move for it, add the rows2 count of the pushed configuration, add a correction per k; rows1 is not read |
| `estimate2` | the push estimator with one fitted correction per 8-bit occupancy pattern of row 1 (`PatternEstimator.java`, same directory, 256 bytes) |
| `blocks:FILE` | rows1 inside the listed 64 KB blocks, else as `rows2` |

**Rank log.** `selfplay/src/main/java/sternhalma/selfplay/RankProbe.java` plays games and logs every rows1 read with its role (leaf evaluator or race play), k and rank. `ProbeReport.java` in the same directory analyses the logs. Sources: `best` (both sides `BEST_SPEC` at 20,000 nodes, real reads), `net` (both sides the MCTS player with the network `data/loop4/gen-0011/candidate.safetensors`, the LARGE network of generation 11 of [loop run 4](training.md#loop-run-4-measured-2026-11-14); `data/` is git-ignored, so a new checkout or worktree must copy it from the checkout where the loop ran; a `BEST_SPEC` evaluator and race play are run at every position as a shadow probe, their move is ignored), `random` (uniform random moves after the opening, shadow probe). 200 games per source, seed 33. rows1 has 3,879 blocks of 64 KB.

**Estimator error.** `tablebase/src/main/java/sternhalma/tablebase/EstimatorReport.java` compares estimators with rows1 on uniformly random configurations: corrections fitted on 5,000,000 states (seed 1), judged on 5,000,000 others (seed 2).

**Compressed sizes.** `tablebase/src/test/java/sternhalma/tablebase/LayoutLTool.java` rewrites rows1 in "layout L": grouped by k, then by the 8-bit row-1 pattern, each pattern with a sub-table over the 28 cells of rows 2 to 8. It also writes a mirror-folded copy (see below). The files were compressed whole with `gzip -9`, `zstd -19` and `xz -6`, and in 64 KB blocks with deflate level 9.

### Arena results

| A (variant against BEST_SPEC) | Exact share of rows1 lookups | Seed 33 | Seed 34 | Pooled |
|---|---|---|---|---|
| `BROWSER_SPEC` (rows2 for evaluator and race play) | 45 % (k = 0) | -62.6 ± 17.2 | -49.0 ± 14.7 | about -56 |
| evaluator on rows2, race play on rows1 | | -60.2 ± 17.0 | -46.6 ± 14.8 | about -53 |
| race play on rows2, evaluator on rows1 | | -1.2 ± 2.3 | -3.5 ± 3.9 | about -2 |
| `maxk:1` (estimator fallback) | 85 % | -18.5 ± 9.4 | -17.4 ± 9.7 | about -18 |
| `maxk:1:rows2` (rows2 fallback) | 85 % | -19.7 ± 10.2 | -17.4 ± 9.2 | about -19 |
| `maxk:2` | 98 % | +0.0 ± 3.2 | -2.3 ± 3.2 | about -1 |
| `estimate` (per-k median correction) | 0 % (estimated) | -54.9 ± 14.7 | -68.0 ± 16.5 | about -61 |
| `estimate2` (per-pattern correction) | 0 % (estimated) | -81.4 ± 17.8 | -96.2 ± 19.2 | about -89 |
| `BROWSER_SPEC` at 40,000 nodes vs `BEST_SPEC` at 20,000 (200 pairs) | 45 % | +95.3 ± 31.9 | +83.2 ± 32.8 | about +89 |
| self-check: `BEST_SPEC` vs `BEST_SPEC` (100 pairs, seed 33) | 100 % | 0.0 (score exactly 0.5000) | | |

The "exact share" column is the share of rows1 lookups in `BEST_SPEC` self-play that the variant answers exactly, from the rank log (k = 0: 44.8 %; k ≤ 1: 85.3 %; k ≤ 2: 98.1 %).

### Rank log results

| | `best` | `net` (shadow probe) | `random` |
|---|---|---|---|
| Plies | 19,965 | 18,640 | 40,000 |
| rows1 lookups | 12,177,214 | 3,856,548 | 0 |
| Leaf evaluator / race play | 52.0 % / 48.0 % | 0.1 % / 99.9 % | |
| k = 0 / 1 / 2 / 3 / ≥ 4 | 44.8 / 40.5 / 12.8 / 1.7 / 0.2 % | 43.7 / 42.2 / 12.7 / 1.3 / 0.06 % | |
| Cumulative k ≤ 1 / k ≤ 2 | 85.3 % / 98.1 % | 85.9 % / 98.6 % | |
| Leaf lookups with k ≥ 3 | 1.74 % | 0.05 % | |
| Distinct 64 KB blocks touched | 2,678 of 3,879 (69 %) | 1,958 of 3,879 (50 %) | |
| Blocks for 50 / 90 / 95 / 99 / 99.9 % of lookups | 44 / 293 / 464 / 947 / 1,650 | 29 / 225 / 371 / 772 / 1,332 | |

Findings:

- **Random games never reach a race.** 200 random games (40,000 plies) produced no rows1 lookup at all, so random games cannot select blocks.
- **The block set keeps growing.** Distinct blocks touched by `best` after 10, 25, 50, 100, 150 and 200 games: 1,364, 1,613, 1,837, 2,214, 2,446, 2,678. The curve does not flatten.
- **Block subsets miss in many positions.** A hold-out test chose the top 20 % of blocks (776) from the even-numbered `best` games and tested them on the odd-numbered ones: 97.1 % of lookups hit, but 32.8 % of positions had at least one miss. The best set covering 99 % of the even games (850 blocks) left 31.3 % of positions with a miss.
- **rows2 equals rows1 for k = 0** in 99.98 % of the `best` lookups. Over the whole table, the two differ in 10,558 of 13,123,110 entries (0.08 %), always with rows1 one move shorter.
- **The push estimator is exact for k = 1 in 43.3 %** of `best` lookups and within one move in about 99 %. For k = 2 it is exact in 10.7 %.
- About 610 rows1 lookups per ply in `best` games (12,177,214 / 19,965), of which about 317 are leaf lookups. This is a derived figure from the counts above.

### Estimator error on random states

| Estimator | Exact | Within 1 | Mean abs. error | Race decision `a ≤ b` agrees with rows1 |
|---|---|---|---|---|
| push estimator, no correction | 28 % | 63 % | 1.25 | 85.4 % |
| push estimator, per-k median correction (`estimate`) | 49.1 % | 93.1 % | 0.58 | 85.0 % |
| per-pattern correction (`estimate2`) | 51.6 % | 95.2 % | | 86.7 % |

The fitted per-k corrections are `estimate:0,0,-1,-2,-2,-3,-4,-5,-5`. The plain estimator is exact for k = 1 in 56.9 % of random states, against 43.3 % in `best` games: random states understate the error that the engine meets.

### Compressed sizes

Layout L for rows1, MB = 10^6 bytes, whole stream unless stated:

| Part | Raw | gzip -9 | zstd -19 | xz -6 | gzip -9 in 64 KB blocks |
|---|---|---|---|---|---|
| rows1 as stored today (dense) | 254.19 | 50.84 | 27.38 | 24.43 | 53.62 |
| k = 0 | 13.12 | 2.45 | 1.27 | 1.13 | 2.59 |
| k ≤ 1 | 68.38 | 12.97 | 7.05 | 6.22 | 13.67 |
| k ≤ 2 | 155.41 | 29.26 | 16.81 | 14.79 | 30.78 |
| all k | 254.19 | 46.98 | 29.00 | 25.55 | 49.34 |
| k ≤ 1, mirror-folded | 40.75 | 7.74 | 4.21 | 3.71 | |
| k ≤ 2, mirror-folded | 90.48 | 17.01 | 9.83 | 8.63 | 17.88 |
| all k, mirror-folded | 141.04 | 26.04 | 16.10 | 14.16 | 27.33 |

- The k = 0 part differs from `rows2.bin` in 10,558 of 13,123,110 bytes (0.08 %), always by rows1 = rows2 - 1; a pack never needs to ship it twice.
- Layout L helps gzip by 8 % and costs zstd and xz 4 to 6 % against the dense file.
- **Mirror fold.** The board is symmetric under left-right reflection (`Geometry.mirror` in `engine/src/main/java/sternhalma/engine/Geometry.java`); a check found no mismatching entry in 1,000,000 samples. The fold keeps only row-1 patterns p with p ≤ mirror(p) and keeps palindromic patterns whole, so the folded sizes are an upper bound.

### Reading

The loss follows the share of exact lookups monotonically: 45 % exact (rows2) costs about 56 Elo, 85 % (k ≤ 1) about 18, 98 % (k ≤ 2) about 0. Almost all of it sits in the leaf evaluator; race play on rows2 costs at most about 4 Elo. Estimators do not help: the plain rows2 fallback (the evaluator keeps its base value) plays as well as the estimator (`maxk:1` against `maxk:1:rows2`), and the estimator that fits random states better (`estimate2`) plays worse. A wrong race value in a leaf seems to hurt more than no race value.

The loss of about 56 Elo is smaller than what one doubling of the node budget buys: `BROWSER_SPEC` at 40,000 nodes beats `BEST_SPEC` at 20,000 by about 89 Elo. By difference of the two runs, one doubling is worth about 145 Elo to `BROWSER_SPEC` here; this is derived from two separate matches, not measured in one.

### Traps and limits

- **Node-limited Elo hides the cost of a lookup.** Both sides search the same number of nodes, so a slower table read costs nothing in these numbers. In WebAssembly with a table in OPFS (the browser's origin-private file system) or behind a worker, a read may cost far more than an array access in the JVM. A time-limited match in the browser is needed before the strength pack ships.
- **12.5 ms per move is far below a human time budget.** With seconds per move the search reaches different positions; whether the rows2 loss shrinks or grows there is not measured.
- **Race play and evaluator differ.** Race play reads the table only at the root of a passed position, so it barely matters which table it uses (≤ 4 Elo). The leaf evaluator reads it inside the search, so it carries the whole loss.
- **Random states understate the problem.** Estimator errors on uniformly random configurations look better than in engine games (k = 1 exact: 56.9 % against 43.3 %). Fit or judge estimators on logged engine lookups, not on random states.
- **Weights are not a confounder.** `racelin-r3` and `racelin1-r3` both wrap `linear-r3`; the comparison of `BROWSER_SPEC` with `BEST_SPEC` measures the table only.
- **CPU column under load.** In the `estimate2` run with seed 33 both sides show about 14.1 CPU-ms per move; this is load on the machine, not a cost of the variant. The node-limited Elo does not depend on it.

## Theses and verdicts

The project owner stated theses a to d before the measurements; they are paraphrased here from a German original that is not in the repository. The agents who planned and analysed the measurements added e to k and set all thresholds before the runs. Options 1 to 4 are the ways of delivering rows1 to the browser that were considered before the measurements.

| | Thesis and test | Verdict |
|---|---|---|
| a | rows1 hardly influences playing strength. Confirmed if `BEST_SPEC` with rows2 in both roles loses ≤ 15 Elo; refuted at ≥ 30. | **Refuted for the leaf evaluator** (about -56 Elo); **confirmed for race play** (≤ 4 Elo). |
| b | rows1 values can be estimated in many cases without much strength loss. Test: an estimator of ≤ 5 MB replaces rows1 at the leaves with ≤ 15 Elo loss and ≥ 97 % agreement on the race decision. A kNN estimator was advised against (it needs a stored neighbour set and a search per leaf); a push estimator with corrections was tested instead. | **Refuted.** Estimators lose 55 to 96 Elo and agree on the race decision in 85 to 87 %; no better than no value. |
| c | Late in the game only few endgames matter, so rows1 can be split into blocks and only those loaded. Confirmed if ≤ 10 % of the 3,879 blocks cover ≥ 99 % of engine lookups; refuted above 40 %. | **Refuted.** 99 % of lookups need 947 blocks (24 %); 69 % of all blocks are touched, and the set still grows. |
| d | These blocks can be found from many random legal games. Confirmed if a block set from random games covers ≥ 95 % of lookups in held-out `BEST_SPEC` games; refuted below 80 %. | **Untestable.** Random games produce no rows1 lookup. |
| e | Decomposition by row-1 occupancy: states without a stone in row 1 equal rows2. Confirmed if ≥ 98 % of lookups have k ≤ 1 (≥ 99.5 % k ≤ 2); refuted if k ≥ 3 exceeds 5 % of leaf lookups. | **Refuted as stated** (k ≤ 1: 85 %), **but the decomposition survives**: k ≤ 2 covers 98 %, leaf lookups with k ≥ 3 are 1.7 %. |
| f | Most rows1 lookups differ from the fallback by 0 or 1 move. Confirmed if ≥ 90 % of leaf lookups not covered by rows2 are within 1 and the race decision agrees in ≥ 97 %; refuted below 93 % agreement. | **Refuted.** Race decision agreement 85 %. |
| g | Random games represent engine games badly: a block set from random games hits ≥ 15 points less on `BEST_SPEC` games than one of equal size from `BEST_SPEC` games. | **Threshold not testable** (random games give no blocks); block subsets fail anyway: 31 to 33 % of positions have a miss, and the set keeps growing. |
| h | In a dedicated worker, OPFS `FileSystemSyncAccessHandle.read()` is synchronous, so download size and storage quota are the real limits, not latency. Test: 20,000 random reads per move from a 242 MiB OPFS file in Chrome, Safari and Firefox add < 20 % move time. | **Open.** From API documentation, not measured. |
| i | Race play reads the table only at the root, so only the leaf evaluator needs synchronous table access. | **Confirmed.** |
| j | rows1 minus a cheap predictor (such as the push estimator) compresses far better than the raw bytes (target < 15 MB in zlib blocks). | **Open, pessimistic.** The order-0 entropy of the residual is about 44 MB. |
| k | Left-right mirror symmetry halves the table. | **Confirmed.** All k: 141 MB folded against 254 MB. |

| Option | Content | Outcome |
|---|---|---|
| 1 | Download rows1 once (24 to 51 MB compressed), cache in OPFS or IndexedDB, inflate to 254 MB; desktop only | not chosen; superseded by the strength pack sketch |
| 2 | `rows2.bin` only (2.45 MB gzip) | **chosen by the owner; ships now** |
| 3 | Lazy loading of rows1 blocks with an "unknown" fallback | dead (theses c, d, g) |
| 4 | A rank order that keeps neighbouring states close; realised as layout L with sub-tables by k | survives as the strength pack (k ≤ 2) |

## Design sketch: an optional strength pack

Not built. The idea is a separate download that the user can choose after the base build works.

- **Content:** the rows1 entries with k = 1 and k = 2, in layout L, mirror-folded. The k = 0 part comes from `rows2.bin` with the correction rows1 = rows2 - 1 at the 10,558 differing entries (or shipped as a small patch list). Lookups with k ≥ 3 fall back to the rows2 behaviour.
- **Size:** about 90 MB raw in memory (the k ≤ 2 folded figure, which includes k = 0), about 17 MB as gzip or about 10 MB as zstd on the wire.
- **Expected strength:** `maxk:2` measured 0 ± 3.2 and -2.3 ± 3.2 Elo against `BEST_SPEC` at equal nodes.

Not decided and not measured:

- **Lookup cost** in WebAssembly, from memory, from OPFS through a synchronous access handle, or in a worker (thesis h). Node-limited Elo cannot see it.
- **Memory on mobile browsers** for 90 MB, in particular Safari on iOS.
- **zstd decoder:** the 10 MB figure needs a WebAssembly zstd decoder in the page; with the built-in `DecompressionStream` the pack is gzip at about 17 MB.
- **Residual coding:** storing rows1 as a small residual (for example 3 bits) against the push estimator is not verified; the order-0 entropy estimate (thesis j) is pessimistic.
- **Index arithmetic** for layout L and the fold in the engine: the measurement tool exists, the lookup code does not.

## What the browser ports need

Two browser ports are planned: the Java engine compiled with TeaVM, and the C++ port compiled to WebAssembly (see [C++ comparison](cpp-comparison.md#reuse-in-the-browser-planned-not-done)). Both need the following from this work.

- **A table loading API that takes bytes.** Java reads tables from a `Path` (`TableFile.read`, `RaceTable.rows2()` in `tablebase/src/main/java/sternhalma/tablebase/`, with the directory from `-Dsternhalma.tablebase.dir`) and rebuilds a missing table; C++ reads with `std::ifstream` (`cpp/src/tablebase/race_table.cpp`). A browser has the decompressed `ArrayBuffer` from the package. The port needs a constructor from a byte array that checks the SHA-256 from the manifest, and no rebuild path.
- **No memory mapping.** The table is a plain array in memory; 13.1 MB for rows2.
- **Synchronous lookups.** The leaf evaluator reads the table inside the search, hundreds of times per ply. Lookups must be plain array reads, not promises. Race play could tolerate an asynchronous read at the root (thesis i), but the evaluator decides the design.
- **The rows1 request counter as a guard.** `RaceTable.rows1Requests()` (Java) and `RaceEvaluator::rows1_requests()` (C++) must stay unchanged in a browser build; a test in each port should play a game with `BROWSER_SPEC` and assert it, as `BrowserSpecTest` does.
- **Bit-identical search.** The C++ port knows `racelin-r3` and `BROWSER_SPEC` (`cpp/src/search/player_spec.cpp`) and reproduces the Java search tree from the golden file `fingerprint-browser.txt`, which `./gradlew --no-build-cache :search:goldenExport` writes into `cpp/golden/` (git-ignored). A browser build should pass the same comparison.

## Open questions

- Lookup cost of the strength pack in WebAssembly, OPFS and workers; a time-limited match in the browser (thesis h).
- Memory limits on mobile browsers for a 90 MB table.
- Whether a residual coding beats plain zstd on the k ≤ 2 pack (thesis j).
- The rows2 loss at a human time budget (seconds per move), not at 20,000 nodes.
- A network-player arena with rows1 against rows2 race play was not run. It is moot for shipping: the network player always used rows2.

## How to reproduce

All commands run from the repository root inside `devenv shell`. `$TABLES` is a directory that holds `rows1.bin` and `rows2.bin` with LFS content (for example `tablebase/tables`). Arena and probe runs use 4 game threads. Measurement sessions on the Mac also respect the 1-minute load limit of 4.0 ([C++ comparison plan, Amendment 2](cpp-comparison-plan.md#amendment-2-2026-11-15-lead-mac-load-limit)); node-limited Elo does not depend on the load, the CPU-ms figures do.

Build the classes and set the class path:

```zsh
./gradlew --no-build-cache -q :search:classes :selfplay:classes :tablebase:testClasses
cp=engine/build/classes/java/main:tablebase/build/classes/java/main:search/build/classes/java/main
```

**Arena, spec form** (`BROWSER_SPEC`, evaluator only, race play only, 40,000 nodes, self-check). Each run of 300 pairs takes about 3 minutes:

```zsh
O=pvs=1,killers=1,history=1,lmr=2,asp=1,ttrep=1,mate=1,partial=1,fut=1,futk=20,fut2=1,race=1
BEST="ab:eval=racelin1-r3,$O,racerows=1,nodes=20000"
arena() { java -Xmx4g --add-modules jdk.incubator.vector -Dsternhalma.tablebase.dir=$TABLES -cp $cp \
  sternhalma.search.Arena --a "$1" --b "$BEST" --pairs $2 --seed $3 --threads 4 }
for s in 33 34; do
  arena "ab:eval=racelin-r3,$O,racerows=2,nodes=20000" 300 $s    # BROWSER_SPEC
  arena "ab:eval=racelin-r3,$O,racerows=1,nodes=20000" 300 $s    # evaluator on rows2 only
  arena "ab:eval=racelin1-r3,$O,racerows=2,nodes=20000" 300 $s   # race play on rows2 only
  arena "ab:eval=racelin-r3,$O,racerows=2,nodes=40000" 200 $s    # BROWSER_SPEC at 40,000 nodes
done
arena "$BEST" 100 33                                             # self-check, expect 0.5000
```

**Arena, variant form** (`maxk`, `estimate`, `estimate2`). The script checks that its copy of `BEST_SPEC` matches `Strategies.java`, writes `build/offline/arena/NAME-sSEED-pPAIRS.txt` and skips a run whose result exists:

```zsh
for s in 33 34; do
  scripts/offline/run-arena-variants.sh --table-dir $TABLES maxk1    maxk:1       300 $s
  scripts/offline/run-arena-variants.sh --table-dir $TABLES maxk1lin maxk:1:rows2 300 $s
  scripts/offline/run-arena-variants.sh --table-dir $TABLES maxk2    maxk:2       300 $s
  scripts/offline/run-arena-variants.sh --table-dir $TABLES estimate estimate:0,0,-1,-2,-2,-3,-4,-5,-5 300 $s
  scripts/offline/run-arena-variants.sh --table-dir $TABLES estimate2 estimate2   300 $s
done
```

The `estimate` variant needs the fitted corrections from the estimator report below; the values shown are the ones measured.

**Rank log.** Starts a smoke run, prints the estimated run time, then runs detached and resumable. `--status` shows progress, `--report` prints the analysis:

```zsh
scripts/offline/run-probe.sh --table-dir $TABLES --net data/loop4/gen-0011/candidate.safetensors
scripts/offline/run-probe.sh --status --table-dir $TABLES
scripts/offline/run-probe.sh --report --table-dir $TABLES
```

**Estimator report:**

```zsh
java -Xmx4g -Dsternhalma.tablebase.dir=$TABLES -cp $cp sternhalma.tablebase.EstimatorReport \
  --samples 5000000 --fit-samples 5000000 --seed 1
```

**Compressed sizes.** `LayoutLTool` writes `k0.bin`, `k1.bin`, `k2.bin`, `k3plus.bin` and the folded `*.fold.bin`, then the files are compressed whole:

```zsh
java -Xmx3g -cp $cp:tablebase/build/classes/java/test sternhalma.tablebase.LayoutLTool \
  $TABLES/rows1.bin $TABLES/rows2.bin build/offline/layoutL
cd build/offline/layoutL
cat k0.fold.bin k1.fold.bin k2.fold.bin | gzip -9 | wc -c
cat k0.fold.bin k1.fold.bin k2.fold.bin | zstd -19 -T1 -q | wc -c
cat k0.fold.bin k1.fold.bin k2.fold.bin | xz -6 -T1 | wc -c
```

**Table package:**

```zsh
scripts/offline/pack-tables.py tablebase/tables/rows2.bin
scripts/offline/pack-tables.py --verify tablebase/tables/rows2.bin
```

**Guards:**

```zsh
./gradlew --no-build-cache :search:test --tests sternhalma.search.BrowserSpecTest
```
