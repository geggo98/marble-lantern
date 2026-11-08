# Testing

**TL;DR:** Every playing strategy must pass two contract properties. They run against all strategies in `Strategies.registered()`, so a new evaluator is tested without extra work. The properties use jqwik with fixed seeds. A failure prints everything needed to rebuild the case.

## The strategy contract

The abstract class `StrategyContract` (in `search/src/testFixtures`) holds the two properties. A module that adds a strategy extends it and returns its own strategy list. The module `search` does this in `RegisteredStrategiesContractTest`.

| Property | What must hold | If it fails |
|---|---|---|
| `strategyBeatsARandomPlayer` | The strategy plays a seeded random player, in either colour, from the default start or after up to 4 random plies. The random player must lose every game. The strategy must not stall or oscillate. A won game must last at least a minimum number of plies. | Something is generally broken: the strategy, the engine or the game loop. |
| `selfPlayIsCleanFromAnyLegalPosition` | Two instances of the strategy play each other from many legal positions. No move is illegal, nothing throws, each player keeps its pieces, the incremental hash equals a rebuilt hash, and the game ends within the move limit with values that sum to zero. | The strategy or the engine breaks an invariant. |

Start positions come from `PositionGenerator`: the default start (often), random plies, greedy play that reaches late-game positions, and positions one move before the end of a game. Four rule variants are covered: the default, `leaveGoal = ALLOWED`, repetition as draw with `DRAW`, and no blockade rule with legal suicide moves and half the move limit. Strategies that support three players are also played with three players.

`GameRunner` rejects an illegal move with an `IllegalMoveException`. Every game played anywhere in the project therefore checks the legality of each move.

## The minimum game length

A won game cannot be arbitrarily short. The floor depends on the rules:

- **Without the blockade rule** (`NONE`): 19 plies. This is proven. The goal must hold 10 own pieces, so the winner vacates all 10 start cells, and every move vacates one cell.
- **With Definition 1** (`OPPONENT_FILLS`, the default): 8 plies. This is a sanity floor chosen by the project, not a theorem. Opponent pieces in the goal count as filling it, so a passive opponent lets the winner fill a single hole. Measured against a random player (800 games per strategy): at least 28 plies from the default start, at least 15 plies from random openings.

The first version of the check used 19 plies for both cases and failed on a legal 15-ply game. The proof does not hold under Definition 1.

## Why there is no maximum game length

A fixed maximum would only encode the strength of today's strategies. Measured against a random player (600 games per strategy, default start):

| Strategy | Games won | Mean plies | 99th percentile | Longest |
|---|---|---|---|---|
| distance, 10000 nodes | 100% | 76 | 115 | 129 |
| distance, 1000 nodes | 100% | 105 | 154 | 176 |
| rows, 1000 nodes | 100% | 93 | 142 | 154 |
| distance, 100 nodes | 70% | 116 | 193 | 198 |

Any limit between 130 and 200 plies would break at the next change of the search budget, and the rules already cap a game at 200 plies. The contract checks for the failure that a maximum length is meant to catch, stalling, in a way that does not depend on strength:

- **Progress:** the strategy may play at most 20 own moves in a row without a new best distance sum. Competent strategies reach 11 at most (99th percentile 7); a 100-node search reaches 56 and a random player has a median of 26.
- **Oscillation:** one position may occur at most 3 times in a game. Competent strategies reach 2.

Both limits are project values chosen from these measurements. They apply to games against a random player. Self-play from arbitrary positions can legitimately need longer regrouping and is only bound by the move limit.

## Checking the checks

`StrategyContractMetaTest` feeds deliberately broken strategies to the checks and expects each check to fail: a cheater (illegal move), a crasher (exception), a runaway (legal moves, never wins), a random strategy that claims to beat random players, and a player that changes the position it is given. It also tests that the position generator is varied and that positions "just before the end" really are one move from the end.

## Search-tree fingerprints

Two tests pin what the alpha-beta players search, so that a speed optimisation cannot change play unnoticed:

| Test | Pins | Recorded with |
|---|---|---|
| `DefaultBehaviourTest` | `ab:eval=distance` with default options: 8 searches and one game | commit 78eb955 |
| `BestSpecFingerprintTest` | R2 (`eval=linear-r1`, the frozen round-1 weights) and `Strategies.BEST_SPEC`, each at 20000 nodes: 64 reference positions (node-limited search and an exact depth-4 search) and one self-play game each at 1000 and 5000 nodes | commit cb4b750 plus E00 |

An identical-tree change (round-2 experiments E01, E02, E13) must keep every constant. A tree-changing feature is off by default and keeps them too while it is off. A change of the `linear` weights (E04) re-records only the `BEST_*` constants. Every re-record needs a written reason in the commit message and in docs/search-and-evaluation.md ("Round 2 yardstick"). On a failure, `./gradlew :search:fingerprint --args='<spec>'` prints one line per position on each build; the first differing line shows where the trees part.

Two equivalence tests pin the parts of the tree that the fingerprint samples only sparsely (added by E02):

| Test | Compares | Positions |
|---|---|---|
| `MoveOrderEquivalenceTest` (engine) | `Position.generate` against `LegacyMoveGenerator`, a verbatim copy of the pre-E02 generator: the same moves in the same order (array equality); also the piece lists and goal counters against a recount, after every make and unmake on a sample, and the move set against `ReferenceGenerator` on every 64th position | over 1.28 M from random and greedy playouts and from constructed positions with an almost full goal, for the four rule variants above plus blockade `NONE` with suicide moves illegal, suicide moves legal, and two three-player variants. The test fails if the Definition-2 test rejected fewer than 1000 moves, so the suicide pre-check is checked where it matters. |
| `LinearEvaluatorEquivalenceTest` (search) | `LinearEvaluator` against `LinearEvaluatorReference`, the pre-E02 code: raw IEEE bits of the 12 features and the integer score for the live, the R1 and three random weight sets | over 1.1 M, both leaveGoal values, plus three-player positions |

Both reference classes are old code kept on purpose; do not optimise them. A change that alters the move order or a value on purpose must say so and re-record the fingerprint (see above).

`BenchTest` checks the speed tool: the same spec in a second, isolated copy of the build searches identical trees, different specs are reported as different trees, and the load rule refuses above the core count. Its test `theFrozenBuildSearchesTheSameTreesAsR2` compares R2 with the frozen pre-round jars in `.reference/`; it is skipped on machines without them.

`LinearRefitTest` covers the refit data tool of E04: the passed filter at its row boundary, atomic shards, resume, cleanup and the lock (exit code 75), samples and labels against an independent replay, and the frozen `linear-r2` weights. The test resource `e04-heldout-games.txt` holds 100 held-out BEST games (54 KB). On these games `linear-r2` must have a lower log loss than `linear-r1`, and one game must replay move for move.

Futility pruning (E06) is not exact, so the exactness tests of `SearchOptionsTest` leave it off. Two tests cover it instead. `WinInOneTest` checks that a winning gain-1 goal entry and the only defence against the opponent's win in 1 are never cut. It checks this at the root and at an interior node with a chosen window (`AlphaBeta.searchNode`); each case has a control position in which the same call does prune. `FutilityTest` checks the spec keys, the exclusions, that the root is never pruned, and the bound property on 200 self-play positions. The registry entry `Strategies.FUTILITY_SPEC` puts the pruning through the strategy contract as well. `DeltaProbe` (test sources) is the measuring tool for the margins, not a test.

The contract covers the frozen aliases `linear-r1` and `linear-r2` automatically, because every name in `Evaluators.names()` is registered; `BestSpecFingerprintTest` asserts that.

The `bestGain` feature of E07 (`eval=linear-r3`) is covered by two tests:

| Test | What it checks |
|---|---|
| `LinearEvaluatorGainTest` | The component search equals a definition taken from the engine's own move list: the largest gain of a legal move whose piece is outside the goal, floored and capped. This is checked on 60 000 player-positions from noisy greedy playouts, scattered set-ups and set-ups packed around the centre, for both `leaveGoal` values with suicide moves legal. The set must exercise the jump search: jumps decide in more than 10 % of the cases, the cap is reached in more than 2 %, and every value from 0 to 6 occurs. With the suicide rule on, the feature is never below the legal moves. Also checked: a constructed ladder of 1 to 3 jumps with its known gain; the floor and the premise of the goal skip (goal cells have distance 0); antisymmetry in mode `both` and the mover's own gain in mode `mover`; the mirror image; bit-identical first 12 features; and a zero gain weight scoring as the 12-feature evaluator. The weight files are tested too: versioned and unversioned files, the rejection of malformed ones, and the registration of `linear-r3`, of `Strategies.GAIN_SPEC` and of an unchanged `BEST_SPEC`. |
| `LinearRefitGainTest` | 13-feature samples carry the 12 features unchanged and the gain of an independently replayed position. Labels come from the first S1 verdict: a recorded result flipped on purpose is relabelled in every game with a verdict, and kept in games without one. The fit recovers known weights with the gain column after the tempo intercept. One stored game of the data set replays move for move. On the committed held-out games (`e07-heldout-games.txt`, 120 games, 67 KB), `linear-r3` has a lower log loss than `linear-r2`, and a lower one than its own weights without the gain. |

A mutation check of the component search: tables without the allowed-cell filter, the step table without it, a stamp that never renews, and the gain difference with the wrong sign were all killed. Two survivors are equivalent: "the moving piece's own cell is a hurdle" (impossible by parity, so the check was removed) and the bound "no gain above the piece's distance" (an optimisation only). `GainCost` (test sources, a `main`) measures the cost of one evaluation; it is a tool, not a test.

The contract covers the frozen alias `linear-r1` automatically, because every name in `Evaluators.names()` is registered; `BestSpecFingerprintTest` asserts that. `Strategies.registered()` also lists `BEST_SPEC` with `race=1` and with `race=2`, so the race module ([endgame and opening](endgame-and-opening.md#the-race-module)) plays under the contract in every rule variant; `PlayerSpecRaceTest` asserts that and that `BEST_SPEC` itself is unchanged.

## Tests of the race module (round 2, E03)

The soundness of the race criterion S1 rests on a written proof (javadoc of `RaceAdjudication`) whose premises and whose whole are tested, not on a sample of games:

| Test | What it checks |
|---|---|
| `GeometryRowAxisTest` | Exhaustively for both players: a step changes the row by at most 1, a jump by exactly 0 or 2 with the hurdle one row short; every allowed cell below the tip has an allowed neighbour on the next row (the loser can always move); fewer than 10 cells lie behind a goal's front row; jumps keep the parity class, steps change it. |
| `FrontAdvanceLemmaTest` | ≥ 1 000 000 positions (half of them with the winner's pieces exactly three and four rows beyond the loser's front and the loser packed for jumps), every legal loser move from the engine: the front never advances by more than one row. With a gap of two rows the same test finds violations, and a hand-built chain shows the jump over the winner's piece. |
| `LowerBoundAdmissibilityTest` | The parity-assignment bound of the loser's moves is at most the table count on all 13,123,110 rows ≥ 2 configurations (tight on 10.0 %), and at most the exact unrestricted solitaire distance (iterative deepening with the heuristic "pieces outside the goal", 2 M nodes per configuration; the configurations that hit the cap are skipped, about half) on 10 000 sampled late-race configurations (tight on 16 %). The two hand cases show a tight and a loose bound. |
| `SolitaireTest` | The solitaire generator equals the engine's move list on 16 000 one-player positions; its row-restricted variant equals an independent search and every path it reports is re-derived from `Geometry`; the beam finds the proven 27-move solitaire from the start [Bell2009] and never fewer; the lower bound equals a brute-force matching. |
| `RaceAdjudicationTest` | Preconditions (three players, `REPETITION_IS_DRAW`, not passed, a piece short), parity of the tempo test and its strictness (a plan one move longer than the loser's bound, with the winner to move, is not adjudicated although the position is lost for the side to move), the move limit, conservative silence where the lemma does not apply although the race is won, both `leaveGoal` and both `blockadeRule` values, and that the plan verifier rejects tampered plans. |
| `ExhaustiveAdversaryTest` | From the first S1 position of 220 self-play games: the loser plays every legal move at every ply (transposition set on its configuration, node cap 10 M) while the winner plays its plan, for plans with at most 4 loser moves; for longer plans and for all positions five complete lines (the recorded game, a front-maximiser, the chaser, a random loser, BEST at 2000 nodes). Every leaf is a win of the predicted winner on or before the predicted ply. |
| `RaceMoveTest` | Race play returns legal moves only (table and plan modes, all contract rule variants), lowers the table count, finishes a covered race within the count, respects `leaveGoal`, uses the table for both players and plays equally well on the player-flipped mirror position (`RaceMove.tableMoves()`), and falls through for three players, a missing piece or (mode 2) a lost race. |
| `RankingTest`, `RaceTableTest` (module `tablebase`) | Region sizes without the side corners; rank/unrank round trips and the builder's merge-based predecessor rank against a sort on 200 000 cases; the rows ≥ 4 table equals a forward brute force for both `leaveGoal` rules; the rows ≥ 3 table is layer-consistent on every configuration, which proves it exact; the shared rows ≥ 2 table is layer-consistent on 20 000 samples and has the recorded SHA-256, and a fresh build by the generalised builder reproduces it byte for byte with the documented layer sizes; the builder is deterministic across thread counts; the file format detects corruption, writes and hashes header and body in parts, and removes the temporaries of interrupted writes; a Git LFS pointer file is recognised and never used as a table, a stale file is ignored, an uncommitted region is looked for in the cache only and is not built unless allowed, and a rebuilt table whose checksum differs from its constant is refused. |
| `RowsOneTableTest` (module `tablebase`), `RaceTableRows1Test` (search) | The optional rows ≥ 1 table (E09). **Skipped with a message when the table is not in the cache**; the tests never build it (`./gradlew --no-build-cache :tablebase:buildTable -Prows=1`, a few minutes). With the table: recorded checksum and layer sizes; on all 13,123,110 rows ≥ 2 states the rows ≥ 1 entry is never larger and never unknown (and strictly smaller on a small share); layer consistency with the engine's moves on 200 000 samples; a forward breadth-first search finds exactly the table distance on 300 sampled configurations; race play with the table returns legal moves only, lowers the count by one per move, covers positions the default table does not, is symmetric under the player flip and wins in exactly the count against an opponent outside the region; the parity bound never exceeds a count on 2 M samples; the beam ordered by the table finds the 27-move solitaire and never fewer; `racerows=1` is parsed, named in the player and refused without race play. |

`RaceReplay` (test sources, a `main`) is the measurement tool of E03: it plays self-play games, runs S1 at every ply and, with `--attack`, attacks every verdict as `ExhaustiveAdversaryTest` does (`--exhaustive N` raises the number of loser moves up to which the attack is exhaustive, default 4; `--plans` prints the plans). A game that ends otherwise than the verdict predicted is listed with the plan and the moves the players made, because such a game is a conversion failure of the player who continued it, not a label error, as long as the attack lines (among them the recorded loser moves against the plan) all end in the predicted win. The results are in [endgame and opening](endgame-and-opening.md#measurements-e03).

Two further measurement tools belong to E09: `TableValidation` (module `tablebase`, Gradle task `:tablebase:validateTable --args='--rows 1 --samples 0 --compare --bfs 3000 --threads 8'`) checks a table against the engine's move generator, exhaustively (`--samples 0`, every entry) or on samples, compares it with the rows ≥ 2 table and runs forward breadth-first searches; `RaceCoverage` (search test sources, a `main`) plays self-play games and reports, per table, how many passed plies are covered and what race play would do with each table, and the players' own table, plan and search counts; with `--out <file>` it appends one line per game and skips games already in the file, so a 1000-game run can be split into chunks and resumed. The test JVMs run with `maxHeapSize = 2g` (the convention plugin), because the rows ≥ 1 table is a 254 MB array, and pass `-Psternhalma.tablebase.dir` through as a system property.

A mutation check of the soundness-critical code (eleven hand-written mutants: weakened tempo test, front gap of two rows, landing row off by one, `passed` without the piece count, the plan move unchecked for legality, the side to move inverted, `leaveGoal` ignored, a ranking off-by-one, the player-1 mirror forgotten, the lower bound without the matching, the rule preconditions dropped) killed nine; the two survivors are equivalent mutants, explained in the same section.

## Adding a strategy

Register it in `Strategies.registered()` with the player counts it supports and whether it must beat a random player. A strategy in another module extends `StrategyContract`. Add `testImplementation(testFixtures(project(":search")))` to that module's build file.

## Seeds

The properties use fixed jqwik seeds, so every run explores the same cases and a failure is reproducible. The failure message names the strategy, the check, the seed and the start position. The jqwik failure database (`.jqwik-database`) is not committed.

## Measurement hygiene: timing numbers need a healthy machine

Elo from node-limited arena matches does not depend on the machine. Everything measured in time does: nodes per CPU second, CPU time per move, games per second, build times, and the equal-cost node counts that the arena derives from the nodes-per-second ratio. A hot or shared machine lowers the clock and makes these numbers unreliable. On 2026-11-07 the same search ran at 2.31 million nodes per CPU second at one moment and at 2.07 million a few hours later (down 11%), drifted by 13% within one minute, and the self-ratio of the same code against its own copy read 0.96 to 0.99 instead of 1.00.

Before any time-based measurement that a decision depends on:

1. Run `./gradlew :search:bench --args='<spec> --copy'`. Accept the machine only if the self-ratio lies within 0.98 to 1.02 in all repetitions, and if the absolute nodes per CPU second are within 10% of the last idle reference (2.3 million for the round-2 R2 spec).
2. Check that the machine is not shared with heavy jobs (the 1-minute load should stay below the number of performance cores, which is 12 on this machine).
3. If either check fails, wait for the machine to cool down or stop other jobs, and measure again. Do not use `--ignore-load` for numbers that decide anything; report such numbers as indicative only.

Speed ratios between two builds that Bench interleaves are more robust than absolute numbers, because both builds see the same clock, but they still carry about ±4% of noise on a throttled machine.

## Tests of the self-play data tool (round 2, E05)

| Test | What it checks |
|---|---|
| `AdjudicationTest` | Layer 2 needs consecutive own moves of one sign and resets on a smaller score, a sign change, race play and race-band scores (which are counted); mate scores count; no layer 2 label in a passed position, also not for scores of +5000 and more; S1 equals `RaceAdjudication`; layer 3 needs consecutive plies for one side and ordinary values; three players are refused. |
| `MarginRegressionTest` | The live layer 2, fed with the stored scores of 65 control games of the acceptance run, labels exactly as recorded: 30 correct firings and the one known move-limit game; the set holds 35 games that a margin of 400 on one move mislabels, so a looser default fails it. |
| `SelfPlayGameTest` | Reused searchers play the same moves and scores as fresh players and as plain self-play, also with race play and with the same game twice in a row; a production game is a prefix of its control game with the same label and samples; labels follow the policy (S1 label is the verdict, margin labels never in passed positions and backed by two stored scores beyond the margin, the offline margin equals the live one); every sample expands to `Encoder.encode` of its position, its moves map back to the legal moves, the policy target sums to 1, weights and flags follow the opening and the label. |
| `JobTest` | An aborted run resumes to the same games; a rerun skips everything and rewrites nothing; leftover temporary files and corrupt shards are cleaned up and redone; a shard of other settings stops the run with 1; a held lock gives 75 and `--status` reports it. |
| `SignalExitTest` | Real child JVMs: a second run on the same directory exits with 75, `--status` names the running pid, SIGINT ends with 130 and SIGTERM with 143 after the summary, no half-written shard remains, and a restart skips the finished shards. |
| `SampleFileTest`, `ClopperPearsonTest`, `OpeningSamplerTest` | The typed safetensors layout (alignment, offsets, rejection of corrupt files, a Python standard-library reader); the bound against its closed form and the exact binomial sum, and the plan's minimum firing counts; reproducible, legal, varied openings, greedy softmax at a low temperature, and the mirror-canonical key. |

`ScoresTest` (module `search`) checks the score bands and `AlphaBetaPlayer.lastResult` and `clearSearch`. A mutation check of the tool (searcher tables not cleared, samples in the wrong orientation, the passed-position guard of layer 2 dropped, race-band scores counted as ordinary) failed at least one of these tests each time.
