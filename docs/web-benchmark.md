# Web benchmark: the browser engines on identical work

**TL;DR:** The benchmark plays one fixed 40-ply self-play game on each browser engine and checks that every engine computes the identical search.
On 2026-11-18 all three engines and the JVM gave the same fingerprint (move checksum `-1986550544382302149`, 800,000 nodes).
In Node.js 24 on an Apple M3 Max, the C++ engine compiled to WebAssembly searched 1.88 million nodes per second: 1.95 times the TeaVM Wasm GC build (0.96 million), 7.1 times the TeaVM JavaScript build (0.26 million), and 1.35 times the Java code on the JVM (1.39 million).
The machine was shared with other jobs; the ratios are reliable to a few percent, the absolute numbers are not quiet-machine figures.

## Contents

1. [Terms](#terms)
2. [Question and workload](#question-and-workload)
3. [The fingerprint](#the-fingerprint)
4. [Runners](#runners)
5. [Reported columns](#reported-columns)
6. [Results of 2026-11-18](#results-of-2026-11-18)
7. [How to run it](#how-to-run-it)
8. [What the benchmark does not measure](#what-the-benchmark-does-not-measure)

## Terms

- **Engine ids:** `cpp-wasm` is the C++ engine compiled to WebAssembly with Emscripten; `java-wasmgc` and `java-js` are the Java engine compiled by TeaVM to WebAssembly with garbage collection (Wasm GC) and to JavaScript. `jvm` is the same Java code on the JVM. [Web UI, the engines](web-ui.md#the-engines) describes them.
- **`BROWSER_SPEC`:** the classical alpha-beta player of the browser; it reads only the small race table `rows2.bin` (see [ADR 0001](adr/0001-browser-mode-without-rows1.md)).
- **Node, node budget:** a node is one position the search visits. The node budget is the number of nodes the search may visit for one move. A search with a node budget visits the same positions on every machine, so its result does not depend on speed.
- **Ply:** one move of one side.
- **devenv task:** a named step defined in `devenv.nix` and started with `devenv tasks run <name>`. [devenv](https://devenv.sh) provides the pinned tools (Node.js, Emscripten, the JDK); the task `web:bench` runs `scripts/web/bench.zsh`.
- **Load average:** the 1-, 5- and 15-minute load averages of the operating system (`os.loadavg()`), a measure of how busy the machine was.

## Question and workload

**Question:** how fast are the browser engines on identical work, and do they compute the identical search?

**Workload:** one self-play game of two-player Chinese Checkers ([rules](rules.md)) from the start position, 40 plies. Both sides play `BROWSER_SPEC,nodes=20000` with the default transposition table of the player specification (`tt=18`: 2^18 slots); a different size changes the search and with it the fingerprint. One player per side is created at the start and kept for the whole game, so its transposition table carries over between moves. All engines read `rows2.bin` from the same table package in `web/public/tables/`, which `devenv tasks run web:tables` generates from `tablebase/tables/rows2.bin` (not in Git; see [web UI, build, run and test](web-ui.md#build-run-and-test)). The time covers only the move searches, not the loading.

Each engine first plays a 2-ply warm-up game, then the timed games (default 5).

## The fingerprint

The fingerprint is the pair (move checksum, total nodes) of one game.

- **Checksum:** start at 0; for every ply, `checksum = checksum * 31 + move`, as a signed 64-bit integer with wrap-around (Java `long`; in TypeScript `BigInt.asIntN(64, ...)`). `move` is the engine's move encoding `from | (to << 8)`. The TypeScript code computes the checksum from the moves the engine returns, so the formula is the same for every engine.
- **Total nodes:** the sum of the nodes of all searches of the game.

The expected values are in `web/bench/expected.json`. They come from the JVM: `sternhalma.web.BenchReference` in the Gradle module `teavm-web` plays the same game. Two entries exist:

| Plies | Nodes per move | Checksum | Total nodes | Used by |
|---|---|---|---|---|
| 6 | 2,000 | `142400977966` | 12,000 | CI smoke run |
| 40 | 20,000 | `-1986550544382302149` | 800,000 | full run |

Equal fingerprints mean that all engines played the same 40 moves and searched the same number of nodes. Speed is therefore the only difference between them. Playing strength is equal by construction and is not measured.

## Runners

`web/src/bench/core.ts` runs the workload on any engine. Two runners use it:

- **Node.js runner** `web/bench/run-node.ts`, started by `devenv tasks run web:bench`. It loads each engine adapter (the TypeScript code that drives one engine build, in `web/src/engines/cpp/` and `web/src/engines/java/`) directly, without a Web Worker. With `--jvm` it adds a JVM row: it runs `BenchReference` through Gradle, which plays one 2-ply warm-up game and **one** timed game. The JVM row therefore has no median and no spread.
- **Browser page** `web/bench/index.html` with `web/src/bench/page.ts`. It is meant to run each engine in its own worker. As of 2026-11-18 it does not run (see [what the benchmark does not measure](#what-the-benchmark-does-not-measure)), so all figures below come from the Node.js runner.

## Reported columns

| Column | Meaning |
|---|---|
| check | `fingerprint=ok` if checksum and total nodes equal `expected.json`; otherwise both values |
| rows1 | requests for the large race table `rows1.bin`; must be 0 |
| nodes/s | total nodes divided by the total search time of one game; median over the timed games |
| ms/move | median over all moves of all timed games |
| spread | highest divided by lowest nodes/s of the timed games; above 1.05 the runner marks the row "noisy" |
| load ms | time of the first engine start: fetch, inflate and check of the table, start of the engine, table install |
| size KiB (gzip) | shipped engine files, raw and gzip, from `web/public/engines/engines.json`, which `devenv tasks run web:engines` generates (not in Git) |

The engine order rotates from one timed game to the next, so a slow phase of the machine does not hit one engine only.

## Results of 2026-11-18

**Machine:** Apple M3 Max, 16 cores, macOS. **Runtime:** Node.js v24.21.0 from devenv. **Command:** `devenv tasks run web:bench --input args="--jvm --json <file>"`. **Load average** (1, 5, 15 minutes): 3.49 7.37 10.99 before, 4.40 7.27 10.84 after. Other jobs ran on the machine at the same time.

Run 1, 5 timed games per engine:

| Engine | Fingerprint | rows1 | nodes/s | ms/move | Spread | Load ms | Size raw (gzip), KiB |
|---|---|---|---|---|---|---|---|
| `cpp-wasm` | ok | 0 | 1,876,760 | 10.72 | 1.028 | 42 | 277 (105) |
| `java-wasmgc` | ok | 0 | 964,261 | 21.14 | 1.028 | 156 | 557 (193) |
| `java-js` | ok | 0 | 263,199 | 75.90 | 1.016 | 83 | 313 (115) |
| `jvm` (one game) | ok | 0 | 1,388,889 | 14.40 | n/a | n/a | n/a |

Ratios of nodes/s, from run 1:

| | `cpp-wasm` | `java-wasmgc` | `java-js` |
|---|---|---|---|
| against `jvm` | 1.35 | 0.69 | 0.19 |
| `cpp-wasm` against this engine | 1 | 1.95 | 7.13 |

**Repeat.** A second run directly afterwards (load average 4.79 before, 4.19 after) gave medians within 3.6 % of run 1: `cpp-wasm` 1,912,026 (+1.9 %), `java-wasmgc` 962,806 (-0.2 %), `java-js` 267,564 (+1.7 %), `jvm` 1,438,849 (+3.6 %). Its spreads were 1.053 to 1.082, so the runner marked all three browser rows "noisy". Run 1 is the one quoted, because all its spreads are below 1.05.

**Load time.** The table install dominates the start of the Java engines: 101 ms for `java-wasmgc` and 41 ms for `java-js`, against 2 ms for `cpp-wasm`. Inflating the 2.45 MB gzip table took 24 to 27 ms and the SHA-256 check 7 to 9 ms for every engine. These numbers come from Node.js, which reads the files from disk; in a browser the download adds to them.

**How to read it.** A search of 20,000 nodes takes about 11 ms with `cpp-wasm`, 21 ms with `java-wasmgc` and 76 ms with `java-js`. The strongest level of the UI, 500,000 nodes, is 25 times the work; extrapolated from these medians and not measured, that is about 0.27 s, 0.52 s and 1.9 s per move.

## How to run it

All commands run from the repository root. The table `tablebase/tables/rows2.bin` must be the real file, not a Git LFS pointer.

```sh
# full run: 40 plies, 20,000 nodes per move, 5 timed games per engine (about 25 seconds)
devenv tasks run web:bench

# with the JVM row and a JSON copy of the result
devenv tasks run web:bench --input args="--jvm --json /tmp/bench.json"

# CI smoke run: 6 plies, 2,000 nodes per move, one timed game
devenv tasks run web:bench --input args="--plies 6 --nodes 2000 --runs 1"
```

Options go through `--input args="..."`, because devenv 2.4 does not pass arguments after `--` to a task. The other options: `--engines a,b` limits the run to a comma-separated list of engine ids; `--public DIR` names the directory with `engines/` and `tables/` (default `web/public`); `--expected FILE` replaces `web/bench/expected.json`. Without devenv tasks, `scripts/web/bench.zsh` takes the same options directly.

Exit codes: 0 when every fingerprint is ok; 1 on a mismatch, an error, or an unchecked fingerprint (a combination of plies and nodes that has no entry in `expected.json`, so nothing can be compared); 2 on a wrong call; 130 or 143 when stopped. The table goes to standard error, because devenv tasks keep a task's standard output to themselves.

Before quoting nodes/s, run the benchmark twice and check that the spread stays below 1.05 and the medians agree. The fingerprint does not depend on load; nodes/s does.

## What the benchmark does not measure

- **Speed in a browser.** As of 2026-11-18 the browser page does not run. `web/bench/index.html` is not part of the Vite build, and under the Vite development server its start fails with `Failed to fetch dynamically imported module: http://localhost:5199/engines/cpp/engine-cpp.js?import`, because Vite does not serve files from `web/public/` as JavaScript modules (measured on 2026-11-18). The benchmark screen inside the app is not connected to the benchmark code and shows that the benchmark is not part of the build.
- **Memory in the page.** `performance.measureUserAgentSpecificMemory` needs cross-origin isolation, which GitHub Pages, the host of the offline web UI, cannot provide.
- **Time-limited play.** Every search has a node budget, not a time limit.
- **Playing strength.** All engines play the same moves, so their strength is equal.
