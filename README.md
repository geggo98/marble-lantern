# Chinese Checkers AI

**TL;DR:** This project builds an AI for Chinese Checkers (German: Stern-Halma) in Java 25 and uses it as a TornadoVM showcase.
Today the development environment, the TornadoVM SDK setup, the `engine` module and a two-player alpha-beta baseline exist.
The rules were decided on 2026-11-07 (see [rules](docs/rules.md)). The next milestone is the neural network: a CPU reference first, then the TornadoVM kernels.

## What this project is

- **Game:** Chinese Checkers on the 121-cell star board, two players first, three players later.
- **AI:** first an alpha-beta search for two players. Its heuristic is the single-agent distance to the goal, which [Sturtevant2019, §2] calls a common heuristic for play. Then AlphaZero-style Monte Carlo tree search (MCTS) with PUCT and a neural network for two and three players. Alpha-beta stays as baseline opponent and test oracle (project decision).
- **GPU:** TornadoVM kernels for batched network inference, training and, as an optional experiment, the BFS layers of a small endgame database, on Metal and OpenCL (macOS aarch64) or CUDA and OpenCL (Linux amd64; see docs/cloud.md). A CPU reference with `jdk.incubator.vector` checks every kernel (project decision).
- **Rules:** the win condition follows Definition 1 of [Sturtevant2019, §3.2]: the goal is full and at least one piece in it is your own. A two-player game ends after 200 plies, and a game without a winner is scored by distance (`drawHandling = SCORE_BY_DISTANCE`). Three players move clockwise, and the game ends at the first winner; ranking the other players (`FULL_RANKING`) is reserved for later. All of these are project decisions of 2026-11-07. `leaveGoal = FORBIDDEN` and `repetition = MOVE_LIMIT`: project decision; differs from [Sturtevant2019, §3]. See [rules](docs/rules.md).
- **Scope:** the project trains a neural network. It does not try to strongly solve any variant, because the development machine has too little RAM. [Sturtevant2019] serves only for verification and as an optional later test oracle (project decision).

## Status

| Part | State |
|---|---|
| Git repository and `.gitignore` | done |
| devenv environment (JDK 25, Gradle, `.env`) | done |
| TornadoVM 7.3.1 SDK download with SHA-256 check | done |
| Rules document, decisions of 2026-11-07 | done |
| Gradle build (wrapper 9.8.2, dependency verification), `engine` module | done |
| `search` module: alpha-beta baseline, learned linear evaluation, search enhancements, arena, strategy contract tests | done; round 2 integrated on 2026-11-08: `Strategies.BEST_SPEC` is `racelin1-r3` (race arithmetic over the bestGain evaluation) with futility pruning and race play, and scores 0.95 against the round-1 BEST at equal CPU time ([integration](docs/search-and-evaluation.md#integration-of-round-2-measured-2026-11-08)) |
| `nn` module: network design, safetensors, encoder, CPU reference, Vector API path | done (TornadoVM kernels not started) |
| `selfplay` module: classical self-play data tool (adjudication, control games, sample files; `scripts/selfplay.sh`) | done for classical engines (round 2, E05); generation-0 training (`scripts/gen0.sh`), PUCT self-play and the CPU training loop with gating and a strength record (`scripts/loop.sh`, block 2h) done |
| `tablebase` module: rows >= 2 and rows >= 1 race tables (Git LFS), race play, S1 adjudication, race arithmetic as a leaf value | done (round 2, E03, E08, E09); GPU build not started |
| `book`, `bench`, `app` | not started |

On the development Mac, `tornado --devices` lists one Metal device.

## Quick start

Prerequisites: [devenv](https://devenv.sh) and an Apple Silicon Mac or a Linux amd64 machine.

1. Copy the example settings. Do not commit `.env`.
   ```sh
   cp .env.example .env
   ```
2. Check `TORNADO_BACKEND` in `.env`. The example sets `metal`. On Linux, set `cuda` (`ptx` is accepted as an alias) or `opencl`, because the Linux archives have no Metal backend. Without the variable, the setup script uses `metal` on macOS and `cuda` on Linux.
3. Download and verify the TornadoVM SDK once. A second run skips a verified install.
   ```sh
   devenv tasks run setup:tornado
   ```
4. Start the shell and check that TornadoVM finds a device.
   ```sh
   devenv shell
   tornado --devices
   ```

Build and test with the Gradle wrapper. Use `--no-build-cache` while the build changes:
```sh
devenv shell -- ./gradlew --no-build-cache build
```
The tasks `devenv tasks run agent:build` and `devenv tasks run agent:test` do the same.

## Repository layout

Present now: `devenv.nix`, `devenv.yaml`, `devenv.lock`, `.envrc`, `.env.example`, `scripts/setup-tornado.sh`, `tornado-sdk/` (gitignored), `docs/`, the Gradle build (`settings.gradle.kts`, `build-logic/`, `gradle/`, `gradlew`) and the modules `engine`, `search` and `nn`.
Planned: the other modules described in [architecture](docs/architecture.md).

## Documentation

- [Rules](docs/rules.md): the default rule set, its switches and the conformance tests.
- [Architecture](docs/architecture.md): modules, data flow and the role of TornadoVM.
- [Search and evaluation](docs/search-and-evaluation.md): heuristics, alpha-beta, multi-player search, MCTS.
- [Endgame and opening](docs/endgame-and-opening.md): endgame database and opening book.
- [Offline mode](docs/offline-mode.md): which race table and player the browser build ships, and what leaving out `rows1.bin` costs.
- [ADR 0001: the browser mode ships the small race table only](docs/adr/0001-browser-mode-without-rows1.md): the decision, every examined variant with its measured result, and the open thesis l.
- [References](docs/references.md): full entries for all citation keys.

## Roadmap

Each milestone gets one commit. Milestones 3 and 5 are additions to the implementation plan; milestone 5 follows the owner decision of 2026-11-07 to build alpha-beta before the network.

1. Git repository and `.gitignore` (done).
2. devenv environment and TornadoVM setup (done).
3. Rules in [rules](docs/rules.md) (done).
4. **Next:** Gradle scaffold and `engine` with tests: move counts computed by our generator, symmetry tests, property tests.
5. **Next:** alpha-beta baseline for two players in `search`, with the single-agent distance heuristic.
6. `nn` CPU reference and safetensors reader and writer.
7. TornadoVM kernels and the `bench` benchmark. The results go into this README.
8. `search`: MCTS with PUCT and leaf batching.
9. Local self-play and training loop with gating against the previous network.
10. Optional experiment, `tablebase`: single-player retrograde BFS for the last 4 to 5 pieces, BFS layers as a TornadoVM kernel.
11. `book`: opening book from late self-play generations.
12. Advisor API (move suggestions) and Opponent API (strength levels).
13. Optional `app` with Vaadin.
14. Self-play in the cloud on NVIDIA GPUs (CUDA backend; docs/cloud.md).
