# Testing

**TL;DR:** Every playing strategy must pass two contract properties. They run against all strategies in `Strategies.registered()`, so a new evaluator is tested without extra work. The properties use jqwik with fixed seeds. A failure prints everything needed to rebuild the case.

## The strategy contract

The abstract class `StrategyContract` (in `search/src/testFixtures`) holds the two properties. A module that adds a strategy extends it and returns its own strategy list. The module `search` does this in `RegisteredStrategiesContractTest`.

| Property | What must hold | If it fails |
|---|---|---|
| `strategyBeatsARandomPlayer` | The strategy plays a seeded random player, in either colour, from the default start or after up to 4 random plies. The random player must lose every game. A won game must last at least a minimum number of plies. | Something is generally broken: the strategy, the engine or the game loop. |
| `selfPlayIsCleanFromAnyLegalPosition` | Two instances of the strategy play each other from many legal positions. No move is illegal, nothing throws, each player keeps its pieces, the incremental hash equals a rebuilt hash, and the game ends within the move limit with values that sum to zero. | The strategy or the engine breaks an invariant. |

Start positions come from `PositionGenerator`: the default start (often), random plies, greedy play that reaches late-game positions, and positions one move before the end of a game. Four rule variants are covered: the default, `leaveGoal = ALLOWED`, repetition as draw with `DRAW`, and no blockade rule with legal suicide moves and half the move limit. Strategies that support three players are also played with three players.

`GameRunner` rejects an illegal move with an `IllegalMoveException`. Every game played anywhere in the project therefore checks the legality of each move.

## The minimum game length

A won game cannot be arbitrarily short. The floor depends on the rules:

- **Without the blockade rule** (`NONE`): 19 plies. This is proven. The goal must hold 10 own pieces, so the winner vacates all 10 start cells, and every move vacates one cell.
- **With Definition 1** (`OPPONENT_FILLS`, the default): 8 plies. This is a sanity floor chosen by the project, not a theorem. Opponent pieces in the goal count as filling it, so a passive opponent lets the winner fill a single hole. Measured against a random player (800 games per strategy): at least 28 plies from the default start, at least 15 plies from random openings.

The first version of the check used 19 plies for both cases and failed on a legal 15-ply game. The proof does not hold under Definition 1.

## Checking the checks

`StrategyContractMetaTest` feeds deliberately broken strategies to the checks and expects each check to fail: a cheater (illegal move), a crasher (exception), a runaway (legal moves, never wins), a random strategy that claims to beat random players, and a player that changes the position it is given. It also tests that the position generator is varied and that positions "just before the end" really are one move from the end.

## Adding a strategy

Register it in `Strategies.registered()` with the player counts it supports and whether it must beat a random player. A strategy in another module extends `StrategyContract`. Add `testImplementation(testFixtures(project(":search")))` to that module's build file.

## Seeds

The properties use fixed jqwik seeds, so every run explores the same cases and a failure is reproducible. The failure message names the strategy, the check, the seed and the start position. The jqwik failure database (`.jqwik-database`) is not committed.
