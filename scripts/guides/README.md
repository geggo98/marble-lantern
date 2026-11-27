# Net-hints study: calibration and counter-move test

**TL;DR:** This directory holds the tools that measure how often the strongest network and the classical analysis disagree
about a move (frequency) and whether the disagreement is justified (validity, by playing both alternatives to the end). A
game is one run of `web/tooling/nethints-sim.test.ts`; `run.py` runs many games in parallel and resumes after an
interruption; `analyze.py` turns the game files into the tables. The tools came out of the simulation of 2026-11-27 that fixed
the first thresholds (plan "Zughilfe-Stufe Mit Netz-Hinweisen", steps 3 and 6) and make it repeatable and extendable.

## Terms

- **Position:** a position of a simulated game with an ordinary classical score (no forced mate, no race phase) and more than
  one legal move. Only such positions are recorded, because only there can the mark appear in the app.
- **Favourite:** the move with the most root visits in the network search (800 simulations).
- **Classical best:** the move with the highest classical score (analysis with 4000 nodes, as the guides).
- **Band:** the classical loss against the best move: `top` up to 30 points, `good` up to 90, `fair` up to 180, `weak` up to
  300, `poor` above. These are the bands of `web/src/guides/bands.ts` under the names top, good, fair, weak, poor.
- **Net value of a move:** its root value when it has at least 16 visits, else its probe value. A probe is a search of its own
  (100 simulations) from the position after the move; the value for the mover is minus its root value (two players only).
- **Tip candidate:** the favourite is `fair`, `weak` or `poor`.
- **Trap candidate at delta:** the classical best move is not the favourite and its net value is at least delta below the
  favourite's. The driver records every candidate at delta 0.15; the other deltas are applied afterwards by `analyze.py`.
- **Mode:** a label for the opponent setup. `A`: network with 200 simulations against classical with 4000 nodes (the network
  mostly leads). `B`: network with 50 simulations against classical with 102400 nodes (more balanced games).
- **Counter-move test:** see "Counter-move test" below.

## How to run

All commands run from the repository root. `web/node_modules` must exist (`cd web && npm ci`).

```zsh
scripts/guides/jobs.py --variants r5,r4 --modes A,B --games 20 --out /tmp/jobs.txt
scripts/guides/run.py /tmp/jobs.txt --out /tmp/study --workers 6 --detach
scripts/guides/run.py --status --out /tmp/study
tail -f /tmp/study/run.log
scripts/guides/analyze.py /tmp/study --json /tmp/study/result.json
python3 -m unittest discover -s scripts/guides/test       # tests of the Python parts
```

- `jobs.py` writes one line `<variant> <mode> <game>` per game, game by game, so that a run that stops halfway leaves every
  group with about the same number of games. `--first K` extends an existing study from game K on.
- `run.py` starts one vitest process per game. A game is done when its file ends in a line with `end:true`; an unfinished
  file is replaced. A second start while one is running exits with 75, SIGINT and SIGTERM end the run with 130 and 143, and
  the summary line prints `ok`, `skip`, `error`, `cleanup` and the games left. A stopped run is restarted with the same command.
- A single game without the driver:
  `cd web && NETHINTS_SIM=1 NETHINTS_VARIANT=r5 NETHINTS_GAME=0 NETHINTS_OUT=<dir> npx vitest run tooling/nethints-sim.test.ts`.
  Environment: `NETHINTS_MODE` (default `A`), `NETHINTS_OPPONENT_NODES` and `NETHINTS_NET_SIMS` (override the mode's setup),
  `NETHINTS_MAX_PLIES` (smoke tests only).
- Without `NETHINTS_SIM=1` the vitest file is skipped, so the normal `vitest run` never starts a game.

## Output files and format

`<out>/<variant>-<mode>-<game>.jsonl`, `variant` is `r5` (5 rows) or `r4` (4 rows), both two players with adjacent jumps. The
file is written once, at the end of the game, under a temporary name and then renamed, so a file that exists is complete or
(after a crash of the machine) cut off; the last line decides, see below. Every line is one JSON object.

| Line | Marker | Content |
|---|---|---|
| header (first) | `header: true` | `version`, `variant`, `mode`, `game`, `oppNodes`, `netSims`, `advisorSims` (800), `probeSims` (100), `model`, `analysisNodes` (4000), `minVisits` (16), `counterBudgets`, `counterCap`, `candidateDelta` (0.15), `maxPlies` |
| position | has `ply` and `cls` | `ply`; `mover` (seat 0 or 1); `n` legal moves; `h` the move history before the position (integers, replayable with `CppCore`); `cls` `{moves, scores}`, the classical analysis; `net` `{moves, visits, values, value, ms}`, the network root (`value` is the value for the mover, `values[i]` the root value of move i, 0 for unvisited moves); `probe` a list of `[move, value for the mover]`; `probeMs` |
| counter | `counter: true` | `ply`; `h`; `fav`; `cbest`; `tip`, `trap` (candidate at delta 0.15); `results` `{<move>: {<budget>: score}}` for `fav` and `cbest`; `counterMs` |
| end (last) | `end: true` | `plies`, `outcome` (`{kind, winner}`), `netSeat`, `mode` |

Moves are the integer move codes of the engines. A counter line belongs to the position line with the same `ply`. A tip or trap
candidate always has a counter line; a position without a candidate has none.

**Probes:** the favourite and every classical `top` or `good` move with fewer than 16 root visits get a probe. The classical
best move is always `top`, so its net value is always defined.

**Score** of an alternative: 1 if the player who made the alternative move wins the playout, 0 if that player loses, 0.5 for a
draw or when the playout reaches the cap. The score of an alternative in `analyze.py` is the mean over the three budgets.

## Counter-move test

Origin: plan step 6. The simulation of 2026-11-27 measured only how often network and classical analysis disagree, not who is
right. For each candidate position the driver plays the position after the favourite and after the classical best move to the
end, with classical players of 3000, 4000 and 5000 nodes (both seats get the same budget in one playout), at most 300 plies
per playout. `analyze.py` then compares the two alternatives:

- tip: score of the favourite minus score of the classical best move, over the tip positions. The tip is right if the value is
  above 0.
- trap: score of the classical best move (the trap) minus score of the favourite, over the trap positions of the delta. The
  trap is right if the value is below 0.

The interval is a percentile bootstrap (95 %, 2000 resamples, seeded) over positions; `--cluster game` resamples whole games
instead. Reason for the default: the plan asks for a cluster bootstrap over positions. Limit: positions of one game are
correlated, so the position interval is narrower than the game interval; read both for the final decision.

## Tables of `analyze.py`

(a) Frequency, per variant and mode and pooled (`<variant>-all`, `all-all`): positions, tip rate and trap rate for the deltas
0.15, 0.20, 0.25, 0.30 and 0.40, for all positions and for balanced ones (`|net value| < 0.5`). (b) Validity: the two
differences above with the interval and the number of positions without counter line (should be 0). `--json PATH` writes
everything, `--deltas` changes the deltas (the driver records candidates at 0.15, so smaller deltas are incomplete).

## Rules of this tool

- **A file counts only with its end line.** Origin: rule `long-running-scripts.md` of the owner (an aborted write leaves a file
  that looks finished). Reason: the driver and `analyze.py` take the state from the work product alone. Limit: the end line
  proves that the game finished, not that every line before it is intact; a file edited by hand is not checked.
- **The rate counts real work only.** Origin: the same rule, measured 2026-10-01. Reason: skipped games would make the ETA
  after a restart far too short. Limit: with few finished games the ETA swings strongly.
- **Playouts run on their own `CppCore`.** Origin: found while building the driver. Reason: `newGame` clears the players of the
  engine, so playouts on the engine of the simulated game would wipe the transposition tables of the opponent. Limit: costs
  one more copy of the 12 MB table in memory per worker.
- **Candidates are recorded at delta 0.15, below the decision delta.** Origin: plan step 6 sweeps delta afterwards. Reason: one
  run then serves every delta from 0.15 up. Limit: the probe noise is 0.07 to 0.08 at the 90th percentile (simulation of
  2026-11-27), so a delta below 0.15 measures mostly noise and is not available.

## Runtime and limits

- **Runtime:** the simulation of 2026-11-27 needed about 2 minutes for a 5-row game with probes, 6 in parallel on an Apple M3
  Max. The smoke test of this driver (variant `r5`, mode `A`, game 0, 113 plies, 105 recorded positions) took 2 minutes 6
  seconds including 3 counter-move pairs (0.1 to 3.4 seconds each). A mode `B` game costs more (102400 nodes per classical move).
- **Memory:** each game loads three network engines and two classical engines; check the memory with 6 workers before raising it.
- **Origin of the data:** the numbers in the plan come from the simulation of 2026-11-27 (20 games, 2763 positions). Its scripts
  were the start of this directory; its raw data is not in the repository.
- **Playouts are not independent.** The three budgets give three different games from one position, but all are played by the
  same family of classical players, so the three results of one alternative are correlated. The bootstrap therefore resamples
  positions, never single playouts. Whether the playouts are deterministic for a fixed budget was not checked.
- **A cap counts as a draw** (0.5). A cap of 300 plies may hide an alternative that is slower but not worse.
- **Two players only.** The probe uses "value of the mover = minus value of the opponent", which fails with three players.
- **One kind of strength.** Classical players judge the alternatives, so the test measures agreement with classical play at
  3000 to 5000 nodes, not with a stronger judge. If the net is right in a way classical play cannot exploit, the test misses it.
- **Expiry:** the Python tools stay as long as the study is repeated; `web/tooling/nethints-sim.test.ts` becomes obsolete when
  the app-side advisor (`web/src/guides/net-advisor.ts`) offers the same root and probe data to a test harness.
