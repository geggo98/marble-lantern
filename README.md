# Chinese Checkers AI

**TL;DR:** This project builds an AI for Chinese Checkers (German: Stern-Halma) in Java 25.
It is a showcase for TornadoVM: batched network inference for self-play, network training and the BFS layers of the endgame database are planned as GPU kernels.
Today only the development environment and the TornadoVM SDK setup exist. The engine, the network and the search are not written yet.

## What this project is

- **Game:** Chinese Checkers on the 121-cell star board, two players first, three players later.
- **AI:** AlphaZero-style Monte-Carlo Tree Search (MCTS) with PUCT and a neural network (project decision).
  An alpha-beta search with a hand-written heuristic serves as baseline opponent and test oracle.
- **GPU:** TornadoVM compiles Java kernels for Metal and OpenCL on macOS (aarch64), and for PTX and OpenCL on Linux (amd64).
  A CPU reference implementation with the Java Vector API (`jdk.incubator.vector`) will check every kernel (project decision).
- **Default rules:** a two-player rule set in the style of [Sturtevant2019]: a piece may not leave the goal area, a blockade rule applies, and a move limit ends the game.
  This choice is a project decision. Other variants are switches. See [rules](docs/rules.md).

## Status

| Part | State |
|---|---|
| Git repository and `.gitignore` | done |
| devenv environment (JDK 25, Gradle, `.env`) | done |
| TornadoVM 7.3.1 SDK download with SHA-256 check | done |
| `engine`, `nn`, `search` and all later modules | not started |

On the development Mac, `tornado --devices` lists one Metal device.

## Quick start

Prerequisites: [devenv](https://devenv.sh) and an Apple Silicon Mac or a Linux amd64 machine.

1. Copy the example settings. Do not commit `.env`.
   ```sh
   cp .env.example .env
   ```
2. Check `TORNADO_BACKEND` in `.env`. The example file sets it to `metal`.
   On Linux, change it to `ptx` or `opencl`, because the Linux archives have no Metal backend.
   If the variable is not set, the setup script uses `metal` on macOS and `ptx` on Linux.
3. Download and verify the TornadoVM SDK. Do this once.
   ```sh
   devenv tasks run setup:tornado
   ```
   The script is safe to run again. A verified install is skipped.
4. Start the shell.
   ```sh
   devenv shell
   ```
   If the SDK is missing, the shell prints a reminder to do step 3.
5. Check that TornadoVM finds a device.
   ```sh
   tornado --devices
   ```

The tasks `agent:build` and `agent:test` exist in `devenv.nix`. They have nothing to build until the Gradle modules exist.

## Repository layout

Present now:

```text
README.md                              this file
.gitignore                             ignored files: .env, build output, data/, tornado-sdk/
devenv.nix, devenv.yaml, devenv.lock   development environment
.envrc                                 direnv hook (use devenv)
.env.example                           documented settings; copy to .env (gitignored)
scripts/setup-tornado.sh               pinned TornadoVM download with SHA-256 check
tornado-sdk/                           installed SDK (gitignored, created by setup:tornado)
docs/                                  project documentation (see below)
```

Planned (not yet present):

```text
settings.gradle.kts, gradle/, build-logic/   Gradle multi-module build (Kotlin DSL)
engine/      board, move generator, rules; plain Java
nn/          network: CPU reference and TornadoVM kernels, safetensors I/O
search/      MCTS (PUCT, virtual loss, leaf batching), time and simulation budget
selfplay/    batched self-play, training data format, trainer CLI
book/        opening book
tablebase/   endgame database
bench/       JMH benchmarks: CPU scalar, Vector API, TornadoVM Metal and OpenCL
app/         optional Spring Boot and Vaadin UI
data/        self-play data, checkpoints, weights (gitignored)
```

## Documentation

- [Rules](docs/rules.md): the default rule set and its variants.
- [Architecture](docs/architecture.md): modules, data flow and the role of TornadoVM.
- [Search and evaluation](docs/search-and-evaluation.md): heuristics, alpha-beta, multi-player search, MCTS.
- [Endgame and opening](docs/endgame-and-opening.md): endgame database and opening book.
- [References](docs/references.md): full entries for all citation keys.

## Roadmap

The milestones follow the order of the implementation plan. Milestone 3 (rules before code) is an addition to that plan. Each milestone gets one commit.

1. Git repository and `.gitignore` (done).
2. devenv environment and TornadoVM setup (done).
3. Rules written down in [rules](docs/rules.md), before the engine code.
4. `engine` with tests: perft-style move counts, symmetry tests, property tests.
5. `nn` CPU reference and safetensors reader and writer.
6. TornadoVM kernels and the `bench` benchmark. The results go into this README.
7. `search`: MCTS with leaf batching.
8. Local self-play and training loop with gating against the previous network.
9. `tablebase`: single-player retrograde BFS for the last few pieces, with BFS layers as a TornadoVM kernel.
10. `book`: opening book from late self-play generations.
11. Advisor API (move suggestions) and Opponent API (strength levels).
12. Optional `app` with Vaadin.
13. Self-play in the cloud on NVIDIA GPUs (PTX backend).
