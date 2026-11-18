# Web UI: offline and online mode

**TL;DR:** The browser UI of Marble Lantern is one React code base in `web/src/` that is built twice.
The **offline mode** is a static site (Vite build in `web/dist/`, published on GitHub Pages): the engine, the race table and the page all run in the browser, with the engine in a Web Worker.
The **online mode** is a Vaadin Hilla app (Gradle module `app`) that serves the same UI and adds engines that run on the server.
Three browser engines play the same moves: Java compiled by TeaVM to WebAssembly GC (the default), C++ compiled to WebAssembly (the fallback for browsers without Wasm GC), and Java compiled by TeaVM to JavaScript.
All engines implement one TypeScript `Engine` interface, and the browser engines speak one worker message protocol.
The engines are compared in [ADR 0002](adr/0002-browser-engines.md), the reasons for the default engine are in [ADR 0003](adr/0003-teavm-default-engine.md), and the speed comparison is in [web benchmark](web-benchmark.md).
The address of the page holds the current game, so a game can be shared as a link ([game URL](#game-url)).

## Contents

1. [Terms](#terms)
2. [The two modes](#the-two-modes)
3. [Game URL](#game-url)
4. [The engines](#the-engines)
5. [The engine interface](#the-engine-interface)
6. [The worker protocol](#the-worker-protocol)
7. [Analysis API](#analysis-api)
8. [Skill estimate](#skill-estimate)
9. [Loading and checking the race table](#loading-and-checking-the-race-table)
10. [Build, run and test](#build-run-and-test)
11. [Continuous integration](#continuous-integration)
12. [Known limits](#known-limits)

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
| `web/src/skill/` | skill estimate: statistic, calibration, level encoding, background tracker (see [Skill estimate](#skill-estimate)) |
| `web/src/i18n/` | German and English texts |
| `web/src/bench/`, `web/bench/` | benchmark (see [web benchmark](web-benchmark.md)) |
| `web/e2e/` | Playwright browser tests |
| `web/design/` | design tokens, mock-up and logo |
| `cpp/src/wasm/wasm_api.cpp` | flat C API of the C++ engine for the browser |
| `teavm-web/` | Gradle module: the Java facade `sternhalma.web.Web` that TeaVM compiles |
| `app/` | Gradle module: the Hilla app and the endpoint `GameEndpoint` |

Generated and not in Git: `web/public/engines/`, `web/public/tables/`, `web/dist/`, `web/node_modules/`. Committed although generated: `web/src/board/cells.generated.json` (from `scripts/web/export-board.zsh`) and the npm lock files.

## Game URL

The current game lives in the fragment of the page address, the part after `#`. A browser never sends the fragment to a server, so the game stays private. A fragment also works on GitHub Pages, a static host without server-side routing. The "Copy link" button copies the address, so a game can be shared as a link.

Code: `web/src/app/game-url.ts` (encode, decode and parse, pure functions without DOM or engine), `web/src/app/game-nav.ts` (`NavigationPort`, a thin wrapper around `history` and `location` that tests replace with a fake, and `GameUrlSync`, which writes the game into the address), `web/src/app/App.tsx` (the flow before and during a game), `web/src/ui/StartDialog.tsx` (the dialog before a game).

### Format

```text
#g=v1.<payload>&h=<side>&l=<level>
```

- `g=v1.` is the version prefix.
- `h=0` or `h=1` is the side the human plays. Side 0 (Blue in the UI) moves first; side 1 is Amber.
- `l=<n>` is the strength level. A number 0 to 4 is an index into the node budgets 1,000, 5,000, 20,000, 100,000 and 500,000. A number 10 to 5,000,000 is a custom node budget itself, which the button "Match engine to my strength" sets (see [Skill estimate](#skill-estimate)); `l=7300` means 7,300 nodes per move. The numbers 5 to 9 and everything else are invalid.

`h` and `l` are optional for the parser; the app always writes both. An empty game at the default strength is `#g=v1.&h=0&l=2`.

The payload holds the moves from the start position. Each move is 14 bits: 7 bits for the from-cell and 7 bits for the to-cell, cells 0 to 120. The moves form one big-endian bit stream, zero-padded to whole bytes and encoded as base64url without padding (alphabet `A-Z`, `a-z`, `0-9`, `-`, `_`). 4 moves take 7 bytes, 5 moves take 9 bytes.

Size, measured by the unit test `web/src/app/game-url.test.ts`: 100 plies give 175 bytes and a payload of 234 characters. The move limit of a game is 200 plies (`MOVE_LIMIT` in `web/src/game/board-state.ts`, see [rules](rules.md)); that gives 350 bytes and 467 characters, and a fragment of 481 characters with the prefix and both fields. A limit of 1,000 plies would give 1,750 bytes and 2,334 characters by the same arithmetic (calculated, not measured); this shows the cost if the limit were raised. A game at the move limit is short enough to paste into a chat message.

### Routes

`#/bench` and `#/about` open the benchmark and the about screen; these routes are unchanged by the game fragment. Any other fragment that is not a game fragment shows the play screen. When the user goes back from another screen to the play screen during a game, the app writes the current game into the address again.

### History

The app writes one history entry per full turn:

| Event | History call |
|---|---|
| The human moves | `pushState`: a new entry with the position after the human's move |
| The engine's answer arrives | `replaceState`: the same entry now holds the answer too |
| Undo, new game | `pushState` |
| Only the strength changes | `replaceState` |
| The human swaps sides | a new game, so `pushState`; `replaceState` if no move was played yet |
| The first state of a page | `replaceState`, no extra entry |
| Engine-versus-engine demo (two engines play each other; the play screen button "Engine vs. engine") | nothing |

Back loads the previous entry: it cancels a running search (the engine terminates its Web Worker and starts a new one, see [the engine interface](#the-engine-interface)) and loads that position without a dialog. Forward does the same in the other direction. The app marks its own entries in `history.state`. An address that the user typed or pasted has no marker, so it opens the "Continue this game?" dialog instead.

Origin: a decision of the project owner. Rationale: with one entry per ply, Back would first show the position in which the engine is to move. The engine would answer at once, so Back would seem to do nothing or to fight the player. The player thinks in turns, and with one entry per turn Back means "take back my last move and the engine's answer". Limit: the rule assumes one human against one engine; the demo writes no entries at all.

### Strict parsing

The parser is strict. It requires:

- the version prefix `g=v1.`
- a payload whose length is a whole number of moves, with zero padding bits
- only the base64url alphabet, in canonical form: no padding characters and no non-zero spare bits
- every cell between 0 and 120
- at most 200 moves
- `h` 0 or 1, `l` a number of one or two digits between 0 and 4; the two fields may come in either order, and either may be missing
- no unknown fields and no repeated fields

Anything else is a broken link, which the app reports and never repairs. Rationale: a repaired link could show a game that nobody sent, and the canonical payload rule makes each game's move list have exactly one text. Limit: the optional fields are less strict; `l=01` is read as level 1.

The parser does not check whether the moves are legal; the engine does. `legalMoves(history)` rejects an illegal history with the error code `illegal-history` ([worker protocol](#the-worker-protocol)). The app asks the engine as soon as the engine is ready, while the dialog is still open, and again when the player continues the game. A broken link or an illegal history shows a plain message with the choice "Start a new game". A bad link never crashes the app.

### Start dialog

A page without a game link asks "Do you want to begin?". "Yes" lets the human play side 0 and move first; "No" lets the engine begin. A page opened with a game link, or a link pasted into the address bar while a game runs, opens "Continue this game?". It shows the ply of the position and the side to move, and offers to continue as the side to move, to continue as the other side, or to start a new game. While a game runs, it also offers to keep the current game; Escape does the same. The engine loads in the background while the dialog is open.

The dialog builds a `GameSetup { seats: [Seat, Seat] }` (`web/src/game/setup.ts`). A seat is `human`, `engine` or `network`. Today there are exactly two seats, and the UI offers only `human` and `engine`; more players and network players are planned.

### Copy link

The button calls `navigator.clipboard.writeText`, which browsers offer only in a secure context (HTTPS or `localhost`). If that call fails or is missing, the button shows a read-only text field with the address selected, for copying by hand. The button is disabled during the engine-versus-engine demo and while the dialog is open.

## The engines

| Engine id | Language, compiler | Shipped files | Size raw (gzip) | Browser needs |
|---|---|---|---|---|
| `cpp-wasm` (fallback without Wasm GC) | C++, Emscripten 6.0.9 | `engines/cpp/engine-cpp.js`, `engine-cpp.wasm` | 284,072 bytes (107,823) | WebAssembly; no threads, no `SharedArrayBuffer` |
| `java-wasmgc` (default) | Java, TeaVM 0.16.0, Wasm GC target | `engines/java/engine-java.wasm`, `engine-java.wasm-runtime.js` | 570,792 bytes (197,505) | Wasm GC and the final version of WebAssembly exception handling, which TeaVM's output uses: Chrome 137, Firefox 131, Safari 18.4 according to MDN |
| `java-js` | Java, TeaVM 0.16.0, JavaScript target | `engines/java/engine-java.js` | 320,624 bytes (117,745) | any current browser |
| `server:browser` | Java on the server | none | | online mode |
| `server:best` | Java on the server, `BEST_SPEC` | none | | online mode, and the server has `rows1.bin` |
| `server:net` | Java on the server, network player (MCTS) | none | | online mode, and the server has a weights file |

The sizes come from `web/public/engines/engines.json` of the build of 2026-11-18. `scripts/web/engines-manifest.zsh` writes this file with raw size, gzip size and SHA-256 of every shipped file, and the UI and the benchmark read the sizes from it.

All three browser engines and `server:browser` play `BROWSER_SPEC` with the node budget appended (`BROWSER_SPEC,nodes=N`). The full player specification is fixed inside each engine, not in the UI, because a different transposition-table size changes the search and with it the moves.

`web/src/engines/registry.ts` checks for Wasm GC with `WebAssembly.validate` on a 13-byte module that declares one struct type. If the check fails, the engine picker shows `java-wasmgc` as unavailable and the session starts with `cpp-wasm`; `java-js` stays selectable. The session starts with the first available engine in the order `java-wasmgc`, `cpp-wasm`, `java-js` ([ADR 0003](adr/0003-teavm-default-engine.md)).

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

## Analysis API

`Engine.analyse(history, moves, nodes, signal?)` scores candidate moves after a history and returns an `AnalysisResult`:

- `moves`: the scored moves. If the call passes `null`, these are all legal moves in the order of `legalMoves`; otherwise the given subset.
- `scores`: one score per move, in the order of `moves`.
- `position`: the best of `scores`, so the value of the position for the side to move (the best of the subset if a subset is given).
- `nodes`: the nodes searched for all moves together; `ms`: the wall time inside the worker.

The worker request has the type `analyse` with the fields `history`, `moves` and `nodes`; the reply is an `AnalysisResult`. Error codes: `illegal-move` (a move is not legal, or the list is empty), `illegal-history` (the game is over or the history is invalid) and `internal` (`nodes` is less than 1). An abort works as for `choose`: the client terminates the worker and starts a new one. The server engines reject the call with the code `unsupported`, because `GameEndpoint` has no analysis method yet. `rows1Requests` stays 0 as for `choose`.

**Method.** For each move, the engine makes the move, searches the resulting position with the full node budget and negates the score. The budget therefore applies per scored move. The search runs in a separate analysis player, not in the game players. It has its own transposition table of 2^16 entries, which the engine clears before every move, so a score depends neither on earlier calls nor on the order of the moves. Its specification is `BROWSER_SPEC` without race play (`race=1,racerows=2` removed), plus `tt=16`. Because the game players stay untouched, `choose` results and the benchmark fingerprint do not change; a test runs an analysis between every two moves and checks this. The three browser engines return identical scores for the same position and budget. The code is in `shw_analyse` in `cpp/src/wasm/wasm_api.cpp`, in `WebCore.analyse` in `teavm-web/`, and in `web/src/engines/cpp/core.ts` and `web/src/engines/java/core.ts`.

**Scores.** The unit is the same as for `ChooseResult.score`. A score is always seen from the side that makes the move, and higher is better. A move that wins at once scores 999,999 (the mate value 1,000,000 minus one); a move that allows the opponent to win with the next move scores -999,998. In race positions, `choose` reports 0 for a move from race play, because race play does not search. The analysis player has no race play and searches such positions like any other, so its scores there are meaningful: at the first race move of a measured self-play game, all scores had the sign of the eventual winner.

| Band | Range of \|score\| | Source of the leaf values |
|---|---|---|
| ordinary | below 5,000 | linear evaluation; about 30 points per step towards the goal corner |
| race | 5,000 to below 999,000 | race table; 200 points per race move |
| win | 999,000 and above | a forced win was found |

In the ordinary band, a logistic fit gives the probability that the side to move wins as 1 / (1 + exp(-score / 80)): 100 points are 78 %, 200 points 92 %, 300 points 98 %. The fit comes from 4,407 positions of 60 self-play games with 1,000 against 20,000 nodes, so the strength gap inflates these rates. Scores of one position vary by about 20 points between budgets. `web/src/engines/analysis.ts` exports `SCORE` (the band constants), `scoreScale = 80`, `advantage(score)` (a value from -1 to 1), `scoreBand`, `scoreLoss`, `GUIDE_NODES = 4000` and `SKILL_NODES = 20000`.

**Speed.** `cpp-wasm` in Node.js analyses about 1.7 million nodes per second. All 68 legal moves of a mid-game position take 82 ms at 2,000 nodes per move, 197 ms at 5,000 and 726 ms at 20,000. `GUIDE_NODES` keeps such a position near 0.16 s; `SKILL_NODES` is meant for skill estimates in the background.

**Tests.** `web/src/engines/testing/analysis-suite.ts` runs against the C++ engine and both TeaVM targets; `web/src/engines/analysis.cross.test.ts` compares the engines; further tests are `WebFacadeTest` in `teavm-web/` and `cpp/wasm/test/analyse.mjs`.

## Skill estimate

**TL;DR:** While the human plays, the app scores each of the human's moves against the best move of the same position. From these losses it estimates a node budget that plays about as well, and one button sets the engine to it. The estimate is rough, and it is valid only for this scoring.

**What is measured.** After each human move, the position before that move is analysed with `Engine.analyse(history, null, SKILL_NODES)` (all legal moves, 20,000 nodes each, see [Analysis API](#analysis-api)). The loss of the move is `scoreLoss(best score, score of the move played)`, in score points (about 30 points per step towards the goal corner). The statistic of a set of moves is the mean of the losses, each capped at `LOSS_CAP = 200` points (`web/src/skill/stats.ts`). The median is shown in the data but not used for the mapping.

- *Why a capped mean.* A good player plays the best move most of the time, so the median is 0 for a wide range of strengths and cannot tell them apart (in the table below it is 0 from 1,000 nodes on). The plain mean fails the other way: race and mate scores reach 1,000,000 points, so one blunder into a lost position would be the whole statistic. The cap limits one move to 200 points, one move of race arithmetic and a swing of about 92 % in win probability.
- *Minimum.* No estimate is shown below 8 analysed moves (`MIN_MOVES`). The card shows "N of 8 moves analysed" until then. With 8 moves the interval is wide; the card always shows the number of moves the estimate rests on.
- *Interval.* The card shows an 80 % interval: a seeded percentile bootstrap of the mean of the capped losses (400 resamples), mapped through the same function. It shows the sampling noise of the player's moves only. It does not include the noise of the calibration.

**When it runs.** `web/src/skill/tracker.ts` (`SkillTracker`, framework-free) runs one analysis at a time, newest human move first, in the background of the engine worker.

- It starts only while the engine is not thinking and it is the human's turn, after the legal moves of the position are loaded, and after the game has been quiet for 800 ms. One worker handles one request at a time and an analysis takes about a second. Without the quiet period, an analysis started right after the engine's move delayed the legal-move request of the next turn (found in the browser test). A player who moves faster than 800 ms after the engine's reply never waits for an analysis; one who moves while an analysis runs waits for it, up to about one second, before the engine starts its search.
- It aborts the running analysis when the engine starts to think, when the history no longer contains the analysed position (undo, new game, a loaded game), and when the engine loads or is replaced. The adapter then restarts its worker, as for any abort. A result that arrives after an abort is dropped.
- Results are cached by position and move. Undo, Back and an engine switch do not repeat finished work. The cache is valid across engines because all browser engines return identical analysis scores.
- The human's moves of a loaded game link are analysed in the same way, newest first. The card shows "Analysing your moves: n of N".
- A server engine rejects `analyse` with the code `unsupported`. The tracker then stops, and the card says that this engine cannot analyse moves. Choosing another engine tries again. Any other error stops the tracker and the card says so; the next engine switch resets it.

**Calibration.** The mapping from the statistic to a node budget comes from measurements, not from a model. `scripts/web/calibrate-skill.zsh` (code in `scripts/web/calibrate-skill.mts`; run it inside `devenv shell`, after `devenv tasks run web:engines web:tables`) lets the C++ engine at each budget of a ladder play whole games against itself. The first 4 plies are random legal moves, seeded with 1000 to 1015, the same 16 seeds for every budget. For every engine move after the opening, the script measures the loss with the same analysis as the app (`analyse` at `SKILL_NODES`, same engine code). `web/src/skill/calibration.json` holds the result with the seeds, the number of games and moves, the engine version, the date and the definition of the statistic.

Result (16 games per budget, both sides measured, committed on 2026-11-18, engine version `3ac176f`):

| Budget (nodes) | Moves | Statistic ± standard error | Median | After smoothing |
|---|---|---|---|---|
| 50 | 1,448 | 19.5 ± 0.7 | 7 | 19.5 |
| 100 | 1,489 | 19.0 ± 0.7 | 6 | 19.0 |
| 200 | 1,642 | 18.7 ± 0.6 | 9 | 18.7 |
| 500 | 1,540 | 16.2 ± 0.6 | 5 | 16.2 |
| 1,000 | 1,452 | 13.8 ± 0.6 | 0 | 13.9 |
| 2,000 | 1,553 | 14.0 ± 0.6 | 2 | 13.9 |
| 5,000 | 1,451 | 10.6 ± 0.5 | 0 | 10.6 |
| 10,000 | 1,416 | 10.1 ± 0.5 | 0 | 10.1 |
| 20,000 | 1,533 | 8.7 ± 0.5 | 0 | 8.7 |
| 50,000 | 1,447 | 7.0 ± 0.5 | 0 | 8.0 |
| 100,000 | 1,479 | 6.7 ± 0.4 | 0 | 8.0 |
| 200,000 | 1,532 | 7.8 ± 0.4 | 0 | 8.0 |
| 500,000 | 1,463 | 10.3 ± 0.6 | 0 | 8.0 |

The standard error treats the moves as independent, which they are not (moves of one game share a position type), so it is optimistic.

- *Monotonicity.* The raw ladder is not monotone: 3 of 12 neighbouring pairs increase with the budget (1,000 to 2,000, 100,000 to 200,000, 200,000 to 500,000). The first increase (0.2 points) is within the standard error. The other two are larger than the combined standard error (1.2 points against about 0.6, and 2.5 against about 0.7), and the last shows the effect: 500,000 nodes lose more against the 20,000-node analysis than 100,000 nodes do. A search with much more budget than the analysis sees better moves than the analysis can confirm, so "agreement with a 20,000-node search" stops measuring strength there. The app therefore uses the isotonic fit (pool-adjacent-violators, weighted by the number of moves), which never increases with the budget. The column "After smoothing" shows it.
- *The strong end.* From 50,000 nodes on, the smoothed ladder is flat. `resolvedUpTo` in the file (50,000) is the first budget within one standard error of that plateau, and the mapping uses only rungs up to it. A player whose statistic is as low as that or lower gets "at least 50,000 nodes" (the card says the real value may be higher). Levels 4 and 5 (100,000 and 500,000 nodes) cannot be told apart from level 3 and above by this method. A higher `SKILL_NODES` would move that limit up and make the background analysis proportionally slower.
- *The weak end.* The engine at 10 and 20 nodes gave the same results as at 50 nodes (all 16 games gave the same number of moves and the same statistic), so 50 nodes is the weakest setting. Its statistic, 19.5, is far below that of a random mover: a test on the real engine measured 86.4 for 12 random moves (seed 2). A player who loses more than 19.5 points per move on average is weaker than anything the engine can be set to; the card then says "weaker than anything measured", and the button sets 50 nodes.
- *The mapping* (`nodesForStat` in `web/src/skill/calibration.ts`) is piecewise linear between ladder points on log(nodes) and clamped at both ends. On a plateau it returns the middle of the plateau on the log scale. The card describes the result relative to the five levels ("between level 2 and level 3"), rounds it to two significant figures and shows the interval in nodes. Levels are counted from 1 as in the engine card.

**The button.** "Match engine to my strength" sets a custom level: `level` is then a node budget of 10 to 5,000,000 instead of an index 0 to 4 (`web/src/skill/level.ts`). The engine card shows "Custom" and the budget, and the game URL carries the budget in `l=` (see [Game URL](#game-url)). The game stays: `onApply` in `App.tsx` makes a new engine game with the new budget, and the engine replays the history on its next search. "Reset to level" goes back to the last preset level. The engine dialog shows the default level while a custom level is active; applying it replaces the custom level.

**Limits.**

- Few moves mean a wide interval; 8 moves are a minimum, not a good sample. Games differ in how hard the positions are, and the statistic does not correct for that.
- The estimate says how much your moves lose against this program's own evaluation at 20,000 nodes. It is not a rating, says nothing about other players, and is not comparable with Elo. Different engines give the same numbers, because the analysis scores are identical in all browser engines; the estimate does not depend on which engine you play against.
- The calibration measures engines playing against themselves. A human may lose points in other ways than an engine with a small budget does, so the same statistic can mean a different strength for a human. The mapping is only an approximation of "the engine that loses as much as you do".
- The data come from one engine build. A change to the search or the evaluation makes `calibration.json` stale; run the script again.

**Tests.** `web/src/skill/*.test.ts` cover the statistic, the interval, the mapping, clamping, the minimum-moves rule, isotonic smoothing, the level encoding and the tracker (with a controllable fake engine). `web/src/app/App.skill.test.tsx` runs the app with the fake engine. `web/src/skill/skill.real.test.ts` plays a simulated player on the real C++ engine: the best mover gets the strongest end of the ladder, the random mover the weakest (skipped when the engine build is missing). `web/e2e/skill.spec.ts` runs the whole flow in a browser.

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

- **Safari and Wasm GC.** MDN lists Safari 18.4 for the Wasm GC features TeaVM uses; caniuse lists no Safari version. Nobody has tested a real Safari. When the feature check fails, the session starts with `cpp-wasm` instead.
- **No benchmark in the browser.** As of 2026-11-18, the benchmark screen of the app is not connected to the benchmark code and shows that the benchmark is not part of the build. The separate benchmark page `web/bench/index.html` is not part of the Vite build, and under the Vite development server it fails to load the C++ engine. The benchmark in Node.js (`devenv tasks run web:bench`) works. Details in [web benchmark](web-benchmark.md#what-the-benchmark-does-not-measure).
- **Node for the Hilla build.** The Vaadin Gradle plugin downloads its own Node.js (24.22.2) into `~/.vaadin` for the production front-end build. No plugin option to use the Node.js of devenv was found.
- **TornadoVM in the online mode** was run once, on 2026-11-18 on an Apple M3 Max: the server selected the Metal device and `server:net` answered a move with a small test weights file. Its playing strength in the online mode was not measured.
- **No offline cache.** The site has no service worker, so it needs the network on every visit.
