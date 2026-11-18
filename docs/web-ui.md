# Web UI: offline and online mode

**TL;DR:** The browser UI of Marble Lantern is one React code base in `web/src/` that is built twice.
The **offline mode** is a static site (Vite build in `web/dist/`, published on GitHub Pages): the engine, the race table and the page all run in the browser, with the engine in a Web Worker.
The **online mode** is a Vaadin Hilla app (Gradle module `app`) that serves the same UI and adds engines that run on the server.
Three browser engines play the same moves: C++ compiled to WebAssembly (the default), and Java compiled by TeaVM to WebAssembly GC or to JavaScript.
All engines implement one TypeScript `Engine` interface, and the browser engines speak one worker message protocol.
The reasons for the default engine are in [ADR 0002](adr/0002-browser-engines.md); the speed comparison is in [web benchmark](web-benchmark.md).

## Contents

1. [Terms](#terms)
2. [The two modes](#the-two-modes)
3. [The engines](#the-engines)
4. [The engine interface](#the-engine-interface)
5. [The worker protocol](#the-worker-protocol)
6. [Loading and checking the race table](#loading-and-checking-the-race-table)
7. [Build, run and test](#build-run-and-test)
8. [Continuous integration](#continuous-integration)
9. [Known limits](#known-limits)

## Terms

- **Race table:** a precomputed table of the exact number of moves one side needs to fill its goal corner (the corner of the star board it must occupy to win, see [rules](rules.md)) when the two sides no longer touch. The engines use it for move choice in the endgame and in their evaluation. `rows2.bin` (13,123,286 bytes) is the small table the browser uses; `rows1.bin` (254,187,064 bytes) is the large one, server only. [Offline mode](offline-mode.md) and [ADR 0001](adr/0001-browser-mode-without-rows1.md) explain why the browser ships only `rows2.bin`.
- **`BROWSER_SPEC`:** the classical alpha-beta player of the browser. It reads `rows2.bin` only. Defined in `search/src/main/java/sternhalma/search/Strategies.java` and, for the C++ port, in `cpp/src/search/player_spec.cpp`.
- **C++ port:** a reimplementation of the Java engine and search in C++ (`cpp/`) that plays the same moves ([C++ comparison](cpp-comparison.md)).
- **`BEST_SPEC`:** the strongest classical player. It reads `rows1.bin`, so it runs on the server only.
- **Node budget:** the number of search nodes an engine may visit for one move. The UI calls it strength and offers 1,000, 5,000, 20,000 (default), 100,000 and 500,000 nodes.
- **Ply:** one move of one side.
- **Wasm GC:** WebAssembly with garbage collection. TeaVM 0.16.0 has only this WebAssembly backend.
- **Network player:** the player that searches with Monte Carlo tree search (MCTS) guided by a neural network; its weights are a safetensors file ([network](network.md)). It runs only on the server.
- **Fingerprint:** move checksum and total nodes of a fixed benchmark game. Equal fingerprints mean equal moves and equal search ([web benchmark, the fingerprint](web-benchmark.md#the-fingerprint)).
- **TornadoVM:** a framework that compiles Java methods into GPU kernels at run time ([TornadoVM](tornado.md)).
- **TeaVM:** an ahead-of-time compiler from JVM bytecode to JavaScript and WebAssembly (<https://teavm.org/>).
- **Hilla:** Vaadin's framework for React front ends with typed calls to Spring endpoints (<https://vaadin.com/hilla>).
- **devenv, devenv task:** [devenv](https://devenv.sh) is the Nix-based tool that provides this repository's development shell (`devenv shell`) with pinned tools. A devenv task is a named step in `devenv.nix`, started with `devenv tasks run <name>`. Each web task runs one script in `scripts/web/`.
- **Engine adapter:** the TypeScript code that drives one engine build behind the `Engine` interface (`web/src/engines/cpp/`, `web/src/engines/java/`, `web/src/engines/server/`).

## The two modes

| | Offline mode | Online mode |
|---|---|---|
| Built by | `devenv tasks run web:build` (plain Vite project in `web/`) | `./gradlew -Pvaadin.productionMode=true :app:bootJar` (Hilla's Vite build of `app/src/main/frontend/`) |
| Served by | any static web server; GitHub Pages under `/marble-lantern/` | the Spring Boot jar `app/build/libs/app.jar` |
| Engines | the three browser engines | the three browser engines plus the server engines |
| Badge in the UI | "offline" | "online" |

Both builds compile the same `web/src/`. The Hilla entry `app/src/main/frontend/views/@index.tsx` only mounts the shared `App` component.

A compile-time constant separates the modes. `__ONLINE__` is `false` in `web/vite.config.ts` and `true` in `app/vite.config.ts`. The only import of the generated Hilla client is a dynamic `import()` inside `if (__ONLINE__)` in `web/src/engines/server/load.ts`, so Vite removes it from the static build. `scripts/web/build.zsh` checks this: no JavaScript file in `web/dist/assets/` may contain the string `connect/`, the URL prefix of every Hilla endpoint call.

`app/vite.config.ts` also sets `resolve.dedupe: ['react', 'react-dom']`. The shared code and the Hilla entry have separate `node_modules` directories, and two copies of React break React hooks.

In the online mode, `app/build.gradle.kts` copies `web/public/engines/` and `web/public/tables/` into the jar, so the online page also offers the browser engines. If these directories are missing when the jar is built, the jar has no browser engines.

### Directory map

| Path | Content |
|---|---|
| `web/src/engines/` | `engine.ts` (interface), `protocol.ts` (worker messages), `client.ts` (wraps a worker into an `Engine`), `registry.ts` (engine list and feature detection), `tables.ts` (table loading) |
| `web/src/engines/cpp/` | adapter for the C++ WebAssembly module |
| `web/src/engines/java/` | adapter for the TeaVM output, both targets |
| `web/src/engines/server/` | adapter for the server engines (online mode only) |
| `web/src/board/` | board geometry `cells.generated.json` (121 cells with id and axial coordinates `q`, `r`) and `path.ts` (jump path for the move animation) |
| `web/src/game/` | game state: move history, side to move, undo, new game |
| `web/src/ui/`, `web/src/app/` | screens and components |
| `web/src/i18n/` | German and English texts |
| `web/src/bench/`, `web/bench/` | benchmark (see [web benchmark](web-benchmark.md)) |
| `web/e2e/` | Playwright browser tests |
| `web/design/` | design tokens, mock-up and logo |
| `cpp/src/wasm/wasm_api.cpp` | flat C API of the C++ engine for the browser |
| `teavm-web/` | Gradle module: the Java facade `sternhalma.web.Web` that TeaVM compiles |
| `app/` | Gradle module: the Hilla app and the endpoint `GameEndpoint` |

Generated and not in Git: `web/public/engines/`, `web/public/tables/`, `web/dist/`, `web/node_modules/`. Committed although generated: `web/src/board/cells.generated.json` (from `scripts/web/export-board.zsh`) and the npm lock files.

## The engines

| Engine id | Language, compiler | Shipped files | Size raw (gzip) | Browser needs |
|---|---|---|---|---|
| `cpp-wasm` (default) | C++, Emscripten 6.0.9 | `engines/cpp/engine-cpp.js`, `engine-cpp.wasm` | 284,072 bytes (107,823) | WebAssembly; no threads, no `SharedArrayBuffer` |
| `java-wasmgc` | Java, TeaVM 0.16.0, Wasm GC target | `engines/java/engine-java.wasm`, `engine-java.wasm-runtime.js` | 570,792 bytes (197,505) | Wasm GC and the final version of WebAssembly exception handling, which TeaVM's output uses: Chrome 137, Firefox 131, Safari 18.4 according to MDN |
| `java-js` | Java, TeaVM 0.16.0, JavaScript target | `engines/java/engine-java.js` | 320,624 bytes (117,745) | any current browser |
| `server:browser` | Java on the server | none | | online mode |
| `server:best` | Java on the server, `BEST_SPEC` | none | | online mode, and the server has `rows1.bin` |
| `server:net` | Java on the server, network player (MCTS) | none | | online mode, and the server has a weights file |

The sizes come from `web/public/engines/engines.json` of the build of 2026-11-18. `scripts/web/engines-manifest.zsh` writes this file with raw size, gzip size and SHA-256 of every shipped file, and the UI and the benchmark read the sizes from it.

All three browser engines and `server:browser` play `BROWSER_SPEC` with the node budget appended (`BROWSER_SPEC,nodes=N`). The full player specification is fixed inside each engine, not in the UI, because a different transposition-table size changes the search and with it the moves.

`web/src/engines/registry.ts` checks for Wasm GC with `WebAssembly.validate` on a 13-byte module that declares one struct type. If the check fails, the engine picker shows `java-wasmgc` as unavailable and `java-js` stays selectable.

No network player runs in the browser: the C++ port has no Monte Carlo tree search, and the TeaVM build of it was never run.

### The server engines

`app/src/main/java/sternhalma/app/GameEndpoint.java` has three methods, each called as `POST /connect/GameEndpoint/<method>`:

- `engines()`: the list of server engines. `browser` always; `best` when the server finds `rows1.bin`; `net` when the property `sternhalma.app.weights` names a readable safetensors weights file.
- `legalMoves(history)`: the legal moves after the history.
- `choose(engineId, history, nodes)`: replays the history, creates a fresh player, searches and returns the same fields as `ChooseResult`. The node budget must be between 1 and 5,000,000. A semaphore limits concurrent searches to the number of CPU cores.

The server is stateless: no transposition table carries over between calls. The `net` player turns the node budget into `nodes / 100` simulations, at least one.

## The engine interface

`web/src/engines/engine.ts` defines it:

```ts
export interface Engine {
  readonly info: EngineInfo;
  init(onProgress?: (p: Progress) => void): Promise<LoadReport>;
  newGame(setup: GameSetup): Promise<void>;
  legalMoves(history: readonly number[]): Promise<number[]>;
  choose(history: readonly number[], signal?: AbortSignal): Promise<ChooseResult>;
  dispose(): void;
}
```

A move is one number: `from | (to << 8)`, with cell ids 0 to 120 as in `engine/src/main/java/sternhalma/engine/Move.java`. The C++ port uses the same ids.

Rules behind the interface:

- **The move history is the game state.** `choose` and `legalMoves` take every move since the start position, and the engine replays them. The search needs the earlier positions to handle repetitions, and a game has at most 200 plies, so the replay is cheap. Between calls of one game the engine keeps one player per side, so the transposition table carries over. `newGame` drops both players. After an undo the UI calls `newGame` and continues.
- **Switching the engine keeps the game.** `web/src/app/switchable-engine.ts` swaps the engine behind the game controller (`GameController` in `web/src/game/game.ts`, which holds the history and calls the engine); the new engine replays the history on its next call.
- **Cancel means terminate.** No engine can stop a search early. When the `AbortSignal` fires, `web/src/engines/client.ts` terminates the worker and starts a new one. The main thread keeps the table bytes, so the restart does not download them again.
- **The board does not come from the engine.** The UI draws the board from `web/src/board/cells.generated.json` before any engine has loaded. A browser test compares this file with the cell coordinates of the C++ module.
- **Jump paths are for display only.** `web/src/board/path.ts` finds the chain of jumps for the move animation by a breadth-first search over the board. Which moves are legal always comes from `legalMoves`.

`ChooseResult` reports the move, score, search depth, nodes, wall time, whether the move came from race play (the race table, without search), and `rows1Requests`. `rows1Requests` must stay 0: it counts requests for `rows1.bin`, which the browser engines must never make (ADR 0001).

## The worker protocol

`web/src/engines/protocol.ts` defines it. Each request carries an `id`; the worker answers with exactly one reply with the same `id`. Progress events during `init` carry the `id` of the running request.

| Request `type` | Fields | Reply `result` |
|---|---|---|
| `init` | `assets` (URLs of engine files, table manifest, table), `table` (bytes the main thread already holds, transferred, or `null`) | `LoadReport` |
| `newGame` | `setup.nodes`: node budget for side 0 and side 1 | `null` |
| `legalMoves` | `history` | `number[]` |
| `choose` | `history` | `ChooseResult` |
| `bench` | `plies`, `nodes` | `BenchResult` (the benchmark game, played inside the worker) |

A failed request replies with `ok: false` and an `EngineError` with an English message for logs and one of these codes, which the UI maps to a translated text:

| Code | Meaning |
|---|---|
| `table-checksum` | the table bytes do not match the SHA-256 in the manifest or in the table header |
| `table-truncated` | the table or its manifest is shorter than expected |
| `table-trailing` | the table has extra bytes at the end |
| `table-magic` | the bytes are not a table file (for example an HTML error page) |
| `wasm-gc-unsupported` | the browser cannot run the Wasm GC engine |
| `illegal-history` | the history contains an illegal move |
| `rows1-requested` | the engine asked for `rows1.bin` |
| `aborted` | the request was cancelled |
| `internal` | anything else |

## Loading and checking the race table

`web/src/engines/tables.ts` loads `tables/rows2.manifest.json` and `tables/rows2.bin.gz`. Both are written by `devenv tasks run web:tables`, which calls `scripts/offline/pack-tables.py` (format in [offline mode, the table package](offline-mode.md#the-table-package)).

1. Fetch the manifest and the table.
2. If the table starts with the gzip bytes `1f 8b`, inflate it with the browser's `DecompressionStream('gzip')`. Otherwise use the bytes as they are. A server can send a `.gz` file with `Content-Encoding: gzip`, and then `fetch` has already inflated it.
3. Compare the length with `rawBytes` of the manifest.
4. If `crypto.subtle` exists (only on HTTPS or `localhost`), compare the SHA-256 with the manifest and tell the engine to skip its own check. Otherwise the engine computes the SHA-256 itself. The Java engine has its own SHA-256 code, `teavm-web/src/main/java/sternhalma/web/Sha256.java`, because TeaVM's class library has no `MessageDigest`.

`LoadReport.tableVerifiedBy` records which check ran: `subtle-sha256`, `engine` or `length-only`.

## Build, run and test

All commands run from the repository root, inside the devenv environment. `tablebase/tables/rows2.bin` must be the real file, not a Git LFS pointer: `git lfs pull --include=tablebase/tables/rows2.bin`.

| Task | Script | Does |
|---|---|---|
| `wasm:build` | `scripts/web/build-wasm.zsh` | builds the C++ engine with Emscripten (`cpp/`, CMake preset `wasm`) into `web/public/engines/cpp/` |
| `wasm:test` | `scripts/web/test-wasm.zsh` | Node.js tests of the C++ module, including the benchmark game and corrupt tables |
| `teavm:build` | `scripts/web/build-teavm.zsh` | builds the Java engine with TeaVM into `web/public/engines/java/` |
| `web:tables` | `scripts/web/tables.zsh` | packs `rows2.bin` into `web/public/tables/` |
| `web:engines` | `scripts/web/engines-manifest.zsh` | writes `web/public/engines/engines.json` |
| `web:install` | `scripts/web/install.zsh` | `npm ci` in `web/` |
| `web:test` | `scripts/web/test.zsh` | TypeScript type check and Vitest |
| `web:build` | `scripts/web/build.zsh` | type check, Vite build into `web/dist/`, then two checks: `index.html` refers to assets under the base path, and no JavaScript file contains `connect/` |
| `web:bench` | `scripts/web/bench.zsh` | Node.js benchmark, see [web benchmark](web-benchmark.md) |
| `web:e2e` | `scripts/web/e2e.zsh` | Playwright tests against `vite preview` |
| `web:ci` | `scripts/web/ci.zsh` | all of the above in order, as the Pages workflow runs them |
| `app:run` | `scripts/web/app-run.zsh` | builds and starts the online mode |
| `app:run-tornado` | `scripts/web/app-run-tornado.zsh` | the same with the TornadoVM JVM flags |

A task also runs the tasks it depends on: `web:build` first runs `wasm:build`, `teavm:build`, `web:engines`, `web:tables` and `web:install`.

### Offline mode

```sh
devenv tasks run web:build
cd web && BASE_PATH=/marble-lantern/ devenv shell -- npx vite preview
```

Open <http://localhost:4173/marble-lantern/>. The build uses the base path `/marble-lantern/` unless `BASE_PATH` says otherwise, and `vite preview` must get the same value.

### Online mode

```sh
devenv tasks run app:run
```

Open <http://localhost:8080/>. The script builds `app/build/libs/app.jar` with the production front end and starts it on `127.0.0.1`. Environment variables: `APP_PORT` (default 8080), `APP_ADDRESS` (default `127.0.0.1`; the endpoint needs no login, so bind to other addresses only on purpose), `APP_WEIGHTS` (a safetensors file; enables `server:net`), `APP_JAVA_OPTS`.

`devenv tasks run app:run-tornado` adds the JVM flags of the TornadoVM SDK, so that `server:net` evaluates the network on the GPU. It needs the SDK (`devenv tasks run setup:tornado`, see [architecture](architecture.md#getting-started)). If TornadoVM does not start, use `app:run`; `server:net` then uses the CPU path with `jdk.incubator.vector`.

The endpoint needs a CSRF token: the first page load sets the cookie `csrfToken`, and each call must send its value in the header `X-CSRF-Token`. The browser page does this by itself. With `curl`:

```sh
curl -s -c jar.txt http://localhost:8080/ >/dev/null
T=$(perl -ne 'print $1 if /csrfToken\s+(\S+)/' jar.txt)
curl -s -b jar.txt -X POST -H 'Content-Type: application/json' -H "X-CSRF-Token: $T" -d '{}' \
  http://localhost:8080/connect/GameEndpoint/engines
```

### Tests

| Command | Checks |
|---|---|
| `devenv tasks run web:test` | type check; Vitest: table loading (gzip, already inflated, wrong SHA-256, wrong length), protocol client (reply matching, error mapping, restart on abort), a full game against a fake engine, UI components |
| `devenv tasks run wasm:test` | the C++ module plays the benchmark game with the expected fingerprint and reports the right error code for corrupt tables |
| `./gradlew --no-build-cache :teavm-web:test` | the Java facade on the JVM, and `Sha256` against `MessageDigest` |
| `./gradlew --no-build-cache :app:test` | the server engine list and move choice |
| `devenv tasks run web:e2e` | Playwright against the built site: page load, each browser engine plays a move, board geometry against the C++ module, screenshots at 360 and 1280 pixels in German and English |

`web:e2e` uses Chromium, Firefox and WebKit from nixpkgs. Chromium and Firefox decide the exit code; WebKit runs for information only, because Playwright's WebKit is not Safari.

## Continuous integration

Two GitHub Actions workflows run everything through devenv:

- `.github/workflows/pages.yml`: on every push to `main`, runs `devenv tasks run web:ci` and, if it passes, publishes `web/dist/` to GitHub Pages.
- `.github/workflows/ci.yml`: on pull requests, runs the Gradle build, `web:ci`, and a security job (action pins, workflow analysis, secret scan with gitleaks).

`web:ci` runs the Java engine tests, both engine builds, the web tests, the build with its bundle checks, a benchmark smoke run (6 plies, 2,000 nodes per move, fingerprints must match) and the browser tests. The workflows fetch only `rows2.bin` from Git LFS and keep it in the Actions cache (`scripts/ci/fetch-rows2.zsh`); no job downloads `rows1.bin.zst`.

CI runs neither the native C++ tests (the Linux devenv shell has no host C++ compiler; the C++ engine code is still tested through `wasm:test`) nor the Hilla production build (it downloads its own Node.js and npm packages, which Gradle's dependency verification does not cover).

## Known limits

- **Safari and Wasm GC.** MDN lists Safari 18.4 for the Wasm GC features TeaVM uses; caniuse lists no Safari version. Nobody has tested a real Safari. The registry falls back to `java-js` when the feature check fails.
- **No benchmark in the browser.** As of 2026-11-18, the benchmark screen of the app is not connected to the benchmark code and shows that the benchmark is not part of the build. The separate benchmark page `web/bench/index.html` is not part of the Vite build, and under the Vite development server it fails to load the C++ engine. The benchmark in Node.js (`devenv tasks run web:bench`) works. Details in [web benchmark](web-benchmark.md#what-the-benchmark-does-not-measure).
- **Node for the Hilla build.** The Vaadin Gradle plugin downloads its own Node.js (24.22.2) into `~/.vaadin` for the production front-end build. No plugin option to use the Node.js of devenv was found.
- **TornadoVM in the online mode** was run once, on 2026-11-18 on an Apple M3 Max: the server selected the Metal device and `server:net` answered a move with a small test weights file. Its playing strength in the online mode was not measured.
- **No offline cache.** The site has no service worker, so it needs the network on every visit.
