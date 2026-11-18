# Web benchmark: the browser engines on identical work

**TL;DR:** The benchmark plays one fixed 40-ply self-play game on each browser engine and checks that every engine computes the identical search.
On 2026-11-18 all three engines and the JVM gave the same fingerprint (move checksum `-1986550544382302149`, 800,000 nodes).
In Node.js 24 on an Apple M3 Max, the C++ engine compiled to WebAssembly searched 1.88 million nodes per second: 1.95 times the TeaVM Wasm GC build (0.96 million), 7.1 times the TeaVM JavaScript build (0.26 million), and 1.35 times the Java code on the JVM (1.39 million).
The machine was shared with other jobs; the ratios are reliable to a few percent, the absolute numbers are not quiet-machine figures.
Since the same day each engine also runs in its own process, and the report adds CPU time and peak memory. In Node.js the C++ engine used 2.2 s of CPU time and 189 MiB of peak resident memory for five timed games, the TeaVM JavaScript build 15.9 s and 235 MiB (section [CPU time and memory](#cpu-time-and-memory-of-2026-11-18)). A browser page can measure far less; the benchmark screen shows "n/a" with the reason instead of a guess.

## Contents

1. [Terms](#terms)
2. [Question and workload](#question-and-workload)
3. [The fingerprint](#the-fingerprint)
4. [Runners](#runners)
5. [Reported columns](#reported-columns)
6. [Results of 2026-11-18](#results-of-2026-11-18)
7. [CPU time and memory of 2026-11-18](#cpu-time-and-memory-of-2026-11-18)
8. [How to run it](#how-to-run-it)
9. [What the benchmark does not measure](#what-the-benchmark-does-not-measure)

## Terms

- **Engine ids:** `cpp-wasm` is the C++ engine compiled to WebAssembly with Emscripten; `java-wasmgc` and `java-js` are the Java engine compiled by TeaVM to WebAssembly with garbage collection (Wasm GC) and to JavaScript. `jvm` is the same Java code on the JVM. [Web UI, the engines](web-ui.md#the-engines) describes them.
- **`BROWSER_SPEC`:** the classical alpha-beta player of the browser; it reads only the small race table `rows2.bin` (see [ADR 0001](adr/0001-browser-mode-without-rows1.md)).
- **Node, node budget:** a node is one position the search visits. The node budget is the number of nodes the search may visit for one move. A search with a node budget visits the same positions on every machine, so its result does not depend on speed.
- **Ply:** one move of one side.
- **devenv task:** a named step defined in `devenv.nix` and started with `devenv tasks run <name>`. [devenv](https://devenv.sh) provides the pinned tools (Node.js, Emscripten, the JDK); the task `web:bench` runs `scripts/web/bench.zsh`.
- **CPU time:** the time a processor spent running the code of a process, split into user time (the program) and system time (the operating system on its behalf). Node.js reports both through `process.cpuUsage()`; this document adds them. A process that waits for a free processor accumulates wall time but no CPU time.
- **Wall time:** the time on a clock, from the start to the end of a phase.
- **RSS (resident set size):** the memory of a process that is held in RAM at a moment. The peak RSS is the highest value over the life of the process (`process.resourceUsage().maxRSS`, in KiB). It includes the Node.js runtime itself, about 62 MiB before an engine is loaded.
- **Wasm linear memory:** the one growable byte array of a WebAssembly module. It never shrinks, so its last size is its peak. Only the C++ engine has one that a page can read (`HEAPU8.byteLength`); a Wasm GC build keeps its objects in the garbage-collected heap of the browser.
- **Chrome DevTools Protocol (CDP):** the remote-control interface of Chromium. Playwright can send CDP commands, for example `Performance.getMetrics` (per page) and `SystemInfo.getProcessInfo` (CPU time of each browser process).
- **Cross-origin isolated:** a page served with the headers `Cross-Origin-Opener-Policy: same-origin` and `Cross-Origin-Embedder-Policy: require-corp`. Only such a page may call `performance.measureUserAgentSpecificMemory()`.
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

- **Node.js runner** `web/bench/run-node.ts`, started by `devenv tasks run web:bench`. It starts one child process per engine (`web/bench/child.ts`), one after the other. A child loads its engine adapter (the TypeScript code that drives one engine build, in `web/src/engines/cpp/` and `web/src/engines/java/`) directly, without a Web Worker, plays the warm-up game and the timed games, and prints one line of JSON. The parent checks the fingerprint with the unchanged `summarize` and `judge` of `core.ts`. One process per engine makes the peak RSS belong to one engine: the peak RSS of a process cannot be reset, so engines in one process would inherit each other's peak. The price: the engine order no longer rotates between games, so a slow phase of the machine can hit one engine only. The warnings below and the spread catch that. With `--jvm` the runner adds a JVM row: it runs `BenchReference` through Gradle, which plays one 2-ply warm-up game and **one** timed game. The JVM row therefore has no median and no spread, and its CPU time and memory are "n/a": the process also holds Gradle and the compiler.
- **Browser, benchmark screen** (route `#/bench` of the app): `web/src/bench/browser-runner.ts` starts each engine in its own Web Worker and plays three timed games (the node runner plays five). The code loads when the Start button is first pressed, not with the app. The standalone page `web/bench/index.html` with `web/src/bench/page.ts` does the same with a form for plies, nodes and runs. It is not part of the Vite build, and under the Vite development server its start fails with `Failed to fetch dynamically imported module: http://localhost:5199/engines/cpp/engine-cpp.js?import`, because Vite does not serve files from `web/public/` as JavaScript modules (measured on 2026-11-18). The Playwright test `web/e2e/bench.spec.ts` runs the benchmark screen against `vite preview` in Chromium and Firefox.

## Reported columns

| Column | Meaning |
|---|---|
| check | `fingerprint=ok` if checksum and total nodes equal `expected.json`; otherwise both values |
| rows1 | requests for the large race table `rows1.bin`; must be 0 |
| nodes/s | total nodes divided by the total search time of one game; median over the timed games |
| ms/move | median over all moves of all timed games |
| spread | highest divided by lowest nodes/s of the timed games; above 1.05 the runner marks the row "noisy" |
| cpu ms | User plus system CPU time of the timed games of one engine, in ms. Node.js runner only. In the browser: "n/a", because no page API reads the CPU time of a worker. |
| cpu/wall | CPU time divided by the wall time measured around the same games. About 1.00 for one busy thread on a quiet machine (1.00 to 1.01 over 40 plies on 2026-11-18). A game of 6 plies showed 1.6 to 2.2, because the runtime compiles code on other threads. Below 0.85 the runner warns: the process waited for a processor. |
| peak MiB | Node.js runner: the peak RSS of the engine's own process, which includes the Node.js runtime. Browser: the size of the Wasm linear memory for `cpp-wasm`; for `java-js` the JS heap in use, if `performance.memory` exists in the worker; "n/a" for `java-wasmgc`. These are different quantities and are not comparable across the two runners. Each cell of the benchmark screen carries its kind as a tooltip and in the notes under the table. |
| load ms | time of the first engine start: fetch, inflate and check of the table, start of the engine, table install |
| size KiB (gzip) | shipped engine files, raw and gzip, from `web/public/engines/engines.json`, which `devenv tasks run web:engines` generates (not in Git) |

The JSON file (`--json`, or the copy button of the screen) keeps every earlier field. Each engine entry gains an optional `resources` object: `wallMs`, `cpuMs`, `cpuToWall`, `rssStartBytes` (before the engine is loaded), `rssAfterLoadBytes`, `peakRssBytes`, `wasmMemoryBytes`, `jsHeapBytes`, `peakMemoryBytes`, `peakMemoryKind` (`process-rss`, `wasm-linear-memory` or `js-heap-sampled`) and `na`, which maps `cpu` and `memory` to the reason code of a missing value. A value that cannot be measured is `null` with a reason, never 0. A reader of the older format ignores the new object; a result from an older runner has no `resources` object, and the table shows "n/a". The `environment` object gains `warnings` and `processModel`.

The node runner prints a `WARNING` line when the 1-minute load average is above half of the cores, when the CPU/wall ratio of an engine is below 0.85, or when a spread is above 1.05. Rationale: the node limit makes the search deterministic, but the speed figures depend on whether the process had a processor to itself. The load average sees other jobs; the CPU/wall ratio sees that this very process waited; the spread sees variation between games. Limit: the load average cannot tell a short burst from a long load, so a clean run is no proof of a quiet machine.

## Results of 2026-11-18

**Machine:** Apple M3 Max, 16 cores, macOS. **Runtime:** Node.js v24.21.0 from devenv. These two tables come from the earlier in-process runner (all engines in one process, order rotating from game to game); the CPU and memory columns did not exist yet. **Command:** `devenv tasks run web:bench --input args="--jvm --json <file>"`. **Load average** (1, 5, 15 minutes): 3.49 7.37 10.99 before, 4.40 7.27 10.84 after. Other jobs ran on the machine at the same time.

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

## CPU time and memory of 2026-11-18

**Machine:** Apple M3 Max, 16 cores, macOS. **Runtime:** Node.js v24.21.0 and the Playwright Chromium of devenv. **Load average** (1, 5, 15 minutes) of the Node.js run: 4.18 5.22 4.52 before, 4.57 5.24 4.55 after; the runner printed no warning. **Command:** `devenv tasks run web:bench --input args="--json <file>"`, 40 plies, 20,000 nodes per move, 5 timed games per engine. All three fingerprints were ok.

**Node.js, one process per engine:**

| Engine | nodes/s | Spread | Wall time of the 5 games | CPU time | CPU/wall | RSS before load | RSS after load | Peak RSS | Wasm memory |
|---|---|---|---|---|---|---|---|---|---|
| `cpp-wasm` | 1,835,161 | 1.008 | 2.19 s | 2.20 s | 1.00 | 61.6 MiB | 149.2 MiB | 188.6 MiB | 25.2 MiB |
| `java-wasmgc` | 944,251 | 1.010 | 4.25 s | 4.26 s | 1.00 | 62.0 MiB | 153.6 MiB | 238.2 MiB | n/a (GC heap) |
| `java-js` | 253,484 | 1.016 | 15.80 s | 15.95 s | 1.01 | 62.8 MiB | 136.2 MiB | 235.0 MiB | n/a |
| `jvm` | not measured quietly | n/a | n/a | n/a (Gradle) | n/a | n/a | n/a | n/a (Gradle) | n/a |

- **CPU time and wall time agree** within 1 % (ratios 1.004, 1.002 and 1.009). One engine uses one thread, and the machine gave it a processor for the whole run. This is the check that the nodes/s figures are quiet-machine figures.
- **Memory.** Over the RSS before the load, the engines hold 88 MiB (`cpp-wasm`), 92 MiB (`java-wasmgc`) and 73 MiB (`java-js`) once loaded. The timed games add another 39, 85 and 99 MiB (peak minus RSS after load). The whole cost of an engine, runtime excluded, is the peak minus the RSS before the load: about 127, 176 and 172 MiB. The peak RSS is one maximum and does not say when it occurred.
- **The JVM row** was run once on the same day with the load average at 10.9 and gave 764,818 nodes/s. That is not a quiet figure and is not quoted. The JVM figure in the first table (1,388,889 nodes/s) comes from the earlier run.
- **A busy machine shows up.** A run a few minutes later, with the load average at 62.8 (other jobs on the machine), printed warnings and gave CPU/wall 0.84 (`cpp-wasm`) and 0.71 (`java-js`), spreads up to 2.95 and only 633,888 nodes/s for `cpp-wasm`. That run is not used. Its fingerprints were ok as well: load changes the speed, never the search.

**Chromium, benchmark screen** (Playwright, `web/e2e/bench.spec.ts`; 40 plies, 20,000 nodes, 3 timed games; each engine in its own worker; load average 4.9 at the start):

| Engine | nodes/s | ms/move | Spread | CPU time | Peak memory | How the memory is measured |
|---|---|---|---|---|---|---|
| `cpp-wasm` | 1,916,168 | 10.6 | 1.03 | n/a (no page API) | 25.2 MiB | Wasm linear memory, `HEAPU8.byteLength` after the game |
| `java-wasmgc` | 913,451 | 22.3 | 1.01 | n/a (no page API) | n/a | Wasm GC objects live in the browser heap; no page API reports it for a worker |
| `java-js` | 287,925 | 68.8 | 1.03 | n/a (no page API) | n/a | `performance.memory` is absent in a Chromium worker (measured) |

The Wasm memory of `cpp-wasm` is the same in both runners (25.2 MiB in Chromium, 25.19 MiB in Node.js). For the whole benchmark run the test also read two numbers through the Chrome DevTools Protocol, which the page cannot read and which are therefore not in the UI table. The CPU time of the renderer processes (page and workers together; loading, warm-ups and games of all three engines) was 13.42 s and 13.48 s in two runs. The main thread of the page spent 0.14 s and 0.16 s in tasks (`TaskDuration` of `Performance.getMetrics`); it only waits for the workers. `JSHeapUsedSize` of the page main thread was 5.6 to 6.0 MB; it says nothing about the engines, which run in workers. Firefox ran the same test and passed; its figures were not recorded.

**Not measured:** `performance.measureUserAgentSpecificMemory()`. The benchmark screen calls it only when the page is cross-origin isolated and writes the result into the JSON (`environment.userAgentMemoryBytes`, otherwise `userAgentMemoryNa` with the reason). GitHub Pages cannot send the two headers. `vite preview`, which the end-to-end tests use, does not send them either, and the tests should see what the live site sees; no benchmark-only header setting was added. So there is no figure of this kind. The peak RSS of the browser's renderer process was not read either.

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

Before quoting nodes/s, run the benchmark twice and check that the spread stays below 1.05, the medians agree, the CPU/wall ratio is about 1.00 and the runner printed no `WARNING`. The fingerprint does not depend on load; nodes/s does.

The browser benchmark screen runs in the end-to-end tests (`devenv tasks run web:e2e`). Set `BENCH_E2E_JSON=<file>` to make the Chromium run write its table and its CDP figures to a file.

## What the benchmark does not measure

- **CPU time in a browser.** No web API gives a page the CPU time of a worker. The screen shows "n/a". Chromium's CDP can read it from outside the page, and only for the whole renderer process (see above).
- **Memory in a browser, beyond the Wasm memory.** `performance.measureUserAgentSpecificMemory` needs cross-origin isolation, which GitHub Pages, the host of the offline web UI, cannot provide. No API gives the peak memory of a worker; for Wasm GC and, in Chromium, for the JavaScript build the screen shows "n/a".
- **CPU time and memory of the JVM.** The JVM row runs inside Gradle.
- **The standalone page.** `web/bench/index.html` is not part of the Vite build (see above); the benchmark screen of the app is the supported browser runner.
- **Time-limited play.** Every search has a node budget, not a time limit.
- **Playing strength.** All engines play the same moves, so their strength is equal.
