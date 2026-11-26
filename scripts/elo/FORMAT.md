# Elo study: common edge format

**TL;DR:** Every match result that enters the rating fit is described by one line in `edges.jsonl` (the edge) plus, where it exists, one result file with one JSON line per opening pair or per opening. `scripts/elo/ingest.py` writes this format from existing results; the match drivers write the same format for new runs; `scripts/elo/fit.py` reads only this format. Source: section 5.1 of the Elo study plan (`/private/tmp/claude-501/-Users-stefan-tmp-stern-halma/f61dab78-2be1-4d4c-97d5-522ea1a41021/scratchpad/elo-study/plan.md`), made exact here. Where the plan and this file differ, this file wins; the differences are listed in the last section.

## Terms

- **Edge:** one match between player configurations in one game type. It is the unit of the comparison graph.
- **Scale:** one game type, written `<players>p-<jump>-r<rows>`, for example `2p-adj-r5`. Ratings of different scales are never mixed in one fit.
- **Config:** what receives a rating, identified by a config id (see "Config ids").
- **Unit:** one opening pair (two players, colours swapped, two games) or one opening (three players, the candidate in all seats). The fit resamples by unit.
- **Pairs file:** the per-pair result file of `selfplay/src/main/java/sternhalma/selfplay/MctsMatch.java`, named `pairs-<hash>.jsonl`. The hash is the first 12 hex digits of the SHA-256 of `subject|opponent|seed|openingPlies|ladderSpec|own|rows<N>` as `MctsMatch.java` builds it (line 262 to 267).
- **Gate file:** a result file of a training-loop gate (promotion test). Gate files are never ingested (see "Refused input").

## Layout of an output directory

```
<out>/edges.jsonl                      one line per edge
<out>/configs.tsv                      one row per config
<out>/<scale>/<edge-id>/pairs-<hash>.jsonl      two-player edges, if the file exists
<out>/<scale>/<edge-id>/openings-<hash>.jsonl   three-player one-against-two edges
<out>/<scale>/<edge-id>/tables-<hash>.jsonl     three-player mixed-table edges
```

All files are UTF-8, lines end in `\n`, every line is one JSON object (JSONL), `configs.tsv` is tab-separated with a header line. Paths inside `edges.jsonl` are relative to `<out>`.

## `edges.jsonl`

One object per line, one line per edge. Fields:

| Field | Type | Meaning |
|---|---|---|
| `id` | string | unique within the file; the name of the edge directory. Only `[A-Za-z0-9._-]`. |
| `scale` | string | scale id, for example `2p-adj-r5` |
| `kind` | string | `pair` (two players), `one-vs-two` (three players, candidate against two identical opponents) or `table` (three different players) |
| `players` | array of strings | config ids. `pair`: `[subject, opponent]`. `one-vs-two`: `[candidate, opponent]`. `table`: the three players. |
| `specs` | object | config id to the full spec text the match tool received for that player |
| `nets` | object | config id to `{"path": string, "sha256Prefix": string}` for network players; absent for classical players. `sha256Prefix` is the 16 hex digits that `models/index.md` lists. |
| `seed` | integer | the opening seed |
| `openingPlies` | integer | random plies before the game starts (4 for two players, 6 for three) |
| `unitsPlanned` | integer | the number of units the match was planned for. A complete result has exactly this many units. |
| `totals` | object | game counts from the first player's view: `{"wins": int, "draws": int, "losses": int}`. Always present. For `pair`, `wins + draws + losses = 2 * unitsPlanned`. |
| `file` | string or null | path of the per-unit result file, or `null` if only the totals exist |
| `unitsPresent` | integer | the number of units in `file`; 0 if `file` is `null` |
| `commit` | string or null | git commit of the code that played the games; `null` if unknown |
| `threads` | integer or null | threads of the run; `null` if unknown |
| `source` | object | `{"kind": "new"}` for a run of this study. For existing data: `{"kind": "existing", "origin": "<path>:<line>"}`, the repository file and line of the cited summary line. |

Rules for the reader:

1. If `file` is not `null`, `unitsPresent` must equal `unitsPlanned`, and the sums over the lines of `file` must equal `totals`. `ingest.py` checks this when it copies the file. `fit.py` must check it again.
2. If `file` is `null`, the edge enters the fit through `totals` only. It then has no opening index, so the cluster bootstrap treats its games as independent pairs and the report flags the edge. Origin of the case: the Arena tool and the E3 run of the small 5-row network wrote no per-pair file.
3. A scale whose graph has no edge, or is not connected, is an error, not an empty fit.

## Per-unit result files

### Two players: `pairs-<hash>.jsonl`

The line `MctsMatch` writes, copied unchanged. Fields used by the fit:

| Field | Meaning |
|---|---|
| `pair` | opening index, 0 to `unitsPlanned - 1`. Lines may come in any order; each index appears once. |
| `wins`, `draws`, `losses` | the two games of the pair, from the subject's view; they sum to 2 |
| `score` | the pair score of the subject, `(wins + draws / 2) / 2`, or a fractional value when a game ended at the move limit and was scored by distance |

All other fields (`plies`, `s_cpu_ns`, `s_moves`, `s_sims`, `s_race_moves`, `o_cpu_ns`, `o_moves`, `o_nodes`) are cost counters. The fit ignores them; the cost report reads them.

### Three players

`openings-<hash>.jsonl` and `tables-<hash>.jsonl` are written by `ThreeSeatMatch` and by the table mode of task T2. Their fields are given in section 5.1 of the plan. No existing result is in these files, so `ingest.py` does not write them.

## Config ids

- Classical: `ab:<spec-name>@n<nodes>`, for example `ab:browser-r5@n20000`. `<spec-name>` names a spec constant of `Strategies.java` in lower case without the `_SPEC` suffix: `browser-r5` is `BROWSER_ROWS5_SPEC`.
- Network: `net:<net-id>@s<simulations>`, for example `net:r5-small-gen9@s200`. `<net-id>` is `r<rows>-<size>-gen<N>`.

## `configs.tsv`

Header and columns, in this order: `id`, `scale`, `engine` (`ab` or `net`), `spec` (full spec text), `net` (net id or empty), `sha256Prefix` (or empty), `budget` (nodes or simulations), `basis` (comma-separated tags: `sims200` for a network at 200 simulations, `time` for the equal-time configs, SMALL at 1,000 and LARGE at 200 simulations; empty for classical), `level` (app level 1 to 5 for 1,000, 5,000, 20,000, 100,000, 500,000 nodes, otherwise empty), `bridge` (empty in ingested data; the driver fills it).

## Refused input

`ingest.py` refuses, with exit code 2 and a message that names the file:

- a gate file or anything that belongs to a loop generation: a path with a component `gate`, `decision`, `decisions.jsonl`, `metrics.jsonl`, or `gen-<NNNN>`. Origin: in loop run 4 five promotions summed to about +140 Elo by their gate scores, while the direct match gave +78 ± 19 (`docs/training.md`, loop run 4, "Findings"). A gate result is stored because it passed a threshold, so it is biased. Gate files of rejected candidates are selected the other way and are excluded for the same reason.
- a file in which no summary line is recognised. A filter that sees nothing must fail; it must not report an empty result.
- a summary line whose subject is not the rated configuration (`batch` other than 1), whose counts do not add up (`wins + draws + losses` is not `2 * pairs`), or whose printed score disagrees with its counts by more than 0.0006 (the printed score has three decimals).

## Differences from section 5.1 of the plan

- `totals` and `unitsPresent` are new. The plan assumes a result file for every edge; the existing data has none for some edges.
- `source` of existing data carries the origin as a `path:line` string.
- The plan lists `nets` as "path and SHA-256". The existing records give a 16-digit prefix only, so the field is `sha256Prefix`.
