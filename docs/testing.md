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

## Adding a strategy

Register it in `Strategies.registered()` with the player counts it supports and whether it must beat a random player. A strategy in another module extends `StrategyContract`. Add `testImplementation(testFixtures(project(":search")))` to that module's build file.

## Seeds

The properties use fixed jqwik seeds, so every run explores the same cases and a failure is reproducible. The failure message names the strategy, the check, the seed and the start position. The jqwik failure database (`.jqwik-database`) is not committed.
