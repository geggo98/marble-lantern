# Records of the Elo study

**TL;DR:** Copies of the match records of the Elo study that [docs/elo.md](../../../docs/elo.md) evaluates: the raw per-pair and per-opening results of Tier 1 (82 new matches, 2026-11-26 to 2026-11-27) and of Tier 0 (19 earlier matches), the driver log, the measured cost per move and the energy report. The originals are in the git-ignored `data/elo/` and in a scratch directory of the evaluation on the training machine. With these files and the two fit scripts, the published ratings in [`../../ratings/ratings.json`](../../ratings/ratings.json) can be recomputed (commands below).

The terms Tier 0, Tier 1, edge, scale, config and anchor are defined in [docs/elo.md, Terms](../../../docs/elo.md#terms).

## Files

| File | Original | What it holds |
|---|---|---|
| `tier1/edges.jsonl` | `data/elo/tier1/edges.jsonl` | one line per Tier 1 edge (82 lines) in the format of [`scripts/elo/FORMAT.md`](../../../scripts/elo/FORMAT.md); the repository path inside the spec strings is replaced by `<repository>` |
| `tier1/configs.tsv` | `data/elo/tier1/configs.tsv` | the configs of Tier 1: spec, network, SHA-256 prefix, budget, app level, bridge id |
| `tier1/<scale>/<edge>/pairs-<hash>.jsonl.gz`, `openings-<hash>.jsonl.gz` | the same path under `data/elo/tier1/`, uncompressed | the raw results: one JSON line per opening pair (two players) or per opening (three players), compressed with `gzip -n -9` |
| `tier0/edges.jsonl`, `tier0/configs.tsv`, `tier0/2p-adj-r5/<edge>/pairs-<hash>.jsonl.gz` | the output directory of `scripts/elo/ingest.py` for the earlier 5-row matches | Tier 0: the matches E1, E2, E3, H2H, equal-time and the ladder check of the two 5-row runs ([training, five-row expert net](../../../docs/training.md#five-row-expert-net) and [five-row LARGE net](../../../docs/training.md#five-row-large-net)); 13 of the 19 edges have a pairs file, 6 have totals only |
| `tier0/bt-existing-edges.jsonl` | `tier1/existing-edges.jsonl` of the evaluation, with relative paths | the 4 Tier 0 edges that the Bradley-Terry fit of Tier 1 used (S9 against S0, L10 against L0, L10 against S9, L10 against S9 at 1,000 simulations) |
| `pairs-r5.tsv` | written for the evaluation | the 10 pairs of 2p-adj-r5 configs whose pooled difference `ratings.json` lists |
| `run-tier1.log` | `data/elo/run-tier1.log` | the stderr of the driver `scripts/elo/run.py` over the whole Tier 1 run, including the output of the match tools; the repository path is replaced by `<repository>` |
| `costs.json` | `data/elo/costs.json` | the measured CPU milliseconds per move of every config and the mean plies per game of each scale, written by the driver on 2026-11-26 at 22:07 UTC, before the Tier 1 run started; the driver's cost model reads it |
| `energy-elo-tier1.report.md`, `.json` | `data/energy/` | wall energy per scale of the Tier 1 run (`scripts/energy/report.py`, name `elo-tier1`) |

The design of Tier 1, one row per edge with the planned number of units, is [`scripts/elo/design-tier1.tsv`](../../../scripts/elo/design-tier1.tsv); the config registry is [`scripts/elo/configs.tsv`](../../../scripts/elo/configs.tsv). Both were committed before the run and were not changed by it.

Total size: about 1.1 MB, so the raw results are in plain git. Uncompressed they are about 3.6 MB.

`fit.py` and `fit_re.py` read the gzip'd result files directly: when the path in `edges.jsonl` does not exist, they read the same path with `.gz` appended.

## Recomputing the ratings

Run from the repository root. Both scripts use the Python standard library only.

**Published ratings** (random-effects fit, [`scripts/elo/fit_re.py`](../../../scripts/elo/fit_re.py)); about one second:

```zsh
scripts/elo/fit_re.py --configs models/runs/elo-study/tier1/configs.tsv \
  --pairs models/runs/elo-study/pairs-r5.tsv --bt models/ratings/tier1-bt-ratings.json \
  --date 2026-11-27 --out /tmp/re.json \
  models/runs/elo-study/tier1/edges.jsonl models/runs/elo-study/tier0/edges.jsonl
```

Checked on 2026-11-27: every rating, half-width, tau, tau interval, pairwise difference, energy-table row and level of `models/ratings/ratings.json` is reproduced; the largest difference before rounding is 0.5 Elo. The test `StudyRecords` in [`scripts/elo/test/test_fit_re.py`](../../../scripts/elo/test/test_fit_re.py) repeats this check. The editorial parts of `ratings.json` (compare rule, caveats, the human table, the Tier 2 advice and the bridge test) are not written by the script.

**Bradley-Terry fit of Tier 1** ([`scripts/elo/fit.py`](../../../scripts/elo/fit.py), 2,000 bootstrap replicates); about one minute:

```zsh
scripts/elo/fit.py --configs models/runs/elo-study/tier1/configs.tsv --seed 1 --date 2026-11-27 --commit 248d75e \
  --out /tmp/bt --work /tmp/bt/work \
  models/runs/elo-study/tier1/edges.jsonl models/runs/elo-study/tier0/bt-existing-edges.jsonl
```

Checked on 2026-11-27: `ratings.json` is byte-identical to [`../../ratings/tier1-bt-ratings.json`](../../ratings/tier1-bt-ratings.json). The report differs from the one the evaluation read in one row: the leave-one-out row of edge `t1.2p-adj-r5.001` now says "not redundant: only edge of ab:browser-r5@n1000" instead of a meaningless prediction, because `fit.py` was fixed after the evaluation. [`../../ratings/tier1-bt-fit-report.md`](../../ratings/tier1-bt-fit-report.md) is the report of this rerun.

## Rerunning the matches

The match run needs the network files under `data/net5/` and `data/shared/` (git-ignored; archived in [`../../nets/`](../../index.md)) and the Java build of the repository. The driver reads the design and the registry above and writes `data/elo/tier1/`:

```zsh
+errlog -- scripts/elo/run.py --tier 1
```

`+errlog` is a wrapper of the training machine that writes the stderr of a command to a log file and returns the command's exit code; without it, run `scripts/elo/run.py --tier 1` directly. The driver measures energy unless `--no-energy` is given; its docstring explains the energy sampler and the detached form for an overnight run. The exact command line of the recorded run is not in the log. The run took 11 h 18 min of wall time on the training machine and 0.44 kWh. Every match is seeded (`seed` in `edges.jsonl`), so a rerun plays the same openings; whether it also reproduces every game was not checked.

The Tier 0 directory came from `scripts/elo/ingest.py`, which reads the match summaries in [`../net5/`](../net5/README.md) and [`../net5-large/`](../net5-large/README.md) and copies the per-pair files from `data/net5/` when they exist.
