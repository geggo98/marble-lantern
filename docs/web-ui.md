# Web UI: offline and online mode

**TL;DR:** The browser UI of Marble Lantern is one React code base in `web/src/` that is built twice.
The **offline mode** is a static site (Vite build in `web/dist/`, published on GitHub Pages): the engine, the race table and the page all run in the browser, with the engine in a Web Worker.
The **online mode** is a Vaadin Hilla app (Gradle module `app`) that serves the same UI and adds engines that run on the server.
Three browser engines play the same moves: Java compiled by TeaVM to WebAssembly GC (the default), C++ compiled to WebAssembly (the fallback for browsers without Wasm GC), and Java compiled by TeaVM to JavaScript.
All engines implement one TypeScript `Engine` interface, and the browser engines speak one worker message protocol.
The engines are compared in [ADR 0002](adr/0002-browser-engines.md), the reasons for the default engine are in [ADR 0003](adr/0003-teavm-default-engine.md), and the speed comparison is in [web benchmark](web-benchmark.md).
The address of the page holds the current game, so a game can be shared as a link ([game URL](#game-url)).
The browser engines can also score moves ([analysis API](#analysis-api)). The UI uses this for optional marks on the board ([move guides](#move-guides)) and for an estimate of the player's strength ([skill estimate](#skill-estimate)).
The app plays game variants: the long-hop jump rule and games with three players, with a human or an engine on each seat ([game variants](#game-variants)). The UI for choosing a variant is not built yet; a start hook and game links start them.

## Contents

1. [Terms](#terms)
2. [The two modes](#the-two-modes)
3. [Game URL](#game-url)
4. [Game variants](#game-variants)
5. [The engines](#the-engines)
6. [The engine interface](#the-engine-interface)
7. [The worker protocol](#the-worker-protocol)
8. [Analysis API](#analysis-api)
9. [Move guides](#move-guides)
10. [Skill estimate](#skill-estimate)
11. [Small screens and touch](#small-screens-and-touch)
12. [Loading and checking the race table](#loading-and-checking-the-race-table)
13. [Build, run and test](#build-run-and-test)
14. [Continuous integration](#continuous-integration)
15. [Known limits](#known-limits)

## Terms

- **Race table:** a precomputed table of the exact number of moves one side needs to fill its goal corner (the corner of the star board it must occupy to win, see [rules](rules.md)) when the two sides no longer touch. The engines use it for move choice in the endgame and in their evaluation. `rows2.bin` (13,123,286 bytes) is the small table the browser uses; `rows1.bin` (254,187,064 bytes) is the large one, server only. [Offline mode](offline-mode.md) and [ADR 0001](adr/0001-browser-mode-without-rows1.md) explain why the browser ships only `rows2.bin`.
- **`BROWSER_SPEC`:** the classical alpha-beta player of the browser. It reads `rows2.bin` only. Defined in `search/src/main/java/sternhalma/search/Strategies.java` and, for the C++ port, in `cpp/src/search/player_spec.cpp`.
- **C++ port:** a reimplementation of the Java engine and search in C++ (`cpp/`) that plays the same moves ([C++ comparison](cpp-comparison.md)).
- **`BEST_SPEC`:** the strongest classical player. It reads `rows1.bin`, so it runs on the server only.
- **Node budget:** the number of search nodes an engine may visit for one move.
- **Level:** the strength setting of the engine in the UI. The five preset levels, counted from 1, are the node budgets 1,000, 5,000, 20,000 (default), 100,000 and 500,000. A custom level is any other node budget from 10 to 5,000,000; the [skill estimate](#skill-estimate) sets one.
- **Score:** the value of a move or position that the search reports, in points, from the point of view of the side that makes the move; higher is better. One step of progress towards the goal corner is worth about 30 points ([analysis API](#analysis-api)).
- **Engine card, skill card:** panels of the play screen. The engine card shows the engine and its level. The skill card shows the skill estimate. The engine sheet is the dialog "Engine and strength", opened from the engine card, in which the player chooses engine and level.
- **Ply:** one move of one side.
- **Variant:** the pair of jump rule (`adjacent` or `long`, see [rules](rules.md), "Jump rule") and player count (2 or 3). The default variant is adjacent jumps with two players: the game of every link written before the variants existed.
- **Seat:** the place of one player. Seat 0 moves first, and the seat to move after `ply` plies is `ply % players`. A seat is played by a human or by an engine.
- **Hot-seat:** a three-player game with two human seats on the same device and one engine seat. The two humans share one board and take turns; the board does not turn.
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
| `web/src/game/` | game state: move history, side to move, undo, new game; `variant.ts` (the variant and its helpers), `setup.ts` (who plays which seat) |
| `web/src/ui/`, `web/src/app/` | screens and components |
| `web/src/guides/` | move guides: bands, marks, advantage text, background analysis (see [move guides](#move-guides)) |
| `web/src/skill/` | skill estimate: statistic, calibration, level encoding, background tracker (see [skill estimate](#skill-estimate)) |
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
#g=v1.<payload>&h=<side>&l=<level>&j=long
#g=v1.<payload>&l=<level>&p=3&s=<seats>
```

The first line is the default variant (adjacent jumps, two players). The second adds the long-hop rule. The third is a three-player game, with the long hop if `&j=long` is added.

- `g=v1.` is the version prefix.
- `h=0` or `h=1` is the side the human plays. Side 0 moves first. The dialogs call side 0 Blue and side 1 Amber.
- `l=<n>` is the level. A number 0 to 4 is an index into the five preset levels (1,000, 5,000, 20,000, 100,000 and 500,000 nodes). A number 10 to 5,000,000 is a custom level, the node budget itself, which the button "Match engine to my strength" sets (see [skill estimate](#skill-estimate)); `l=7300` means 7,300 nodes per move. The numbers 5 to 9 and everything else are invalid.

- `j=long` is the long-hop jump rule. The adjacent rule has no `j`; the encoder never writes `j=adjacent`.
- `p=3` is a game with three players. Two players have no `p`; the encoder never writes `p=2`.
- `s=<seats>` is who plays each seat: one character per seat, `h` for a human and `e` for an engine, in playing order. It is required with `p=3` and not allowed without it. A three-player link has at least one and at most two `h`: `hee` (one human against two engines), `hhe` (hot-seat). Two players keep `h=<side>`; three players have no `h`, because the seats say it.

`h` and `l` are optional for the parser; the app writes both for two players (for three, `l` only). An empty game at the default strength is `#g=v1.&h=0&l=2`. A three-player game of one human on seat 0 is `#g=v1.&l=2&p=3&s=hee`.

Origin: a decision of the project owner (game-variants feature). Rationale: a link written before the variants has none of the new fields, so it keeps its meaning, and the new fields appear only where they differ from the default. Limit: the format has no field for a network player.

The payload holds the moves from the start position. Each move is 14 bits: 7 bits for the from-cell and 7 bits for the to-cell, cells 0 to 120. The moves form one big-endian bit stream, zero-padded to whole bytes and encoded as base64url without padding (alphabet `A-Z`, `a-z`, `0-9`, `-`, `_`). 4 moves take 7 bytes, 5 moves take 9 bytes.

Size, measured by the unit test `web/src/app/game-url.test.ts`: 100 plies give 175 bytes and a payload of 234 characters. The move limit of a two-player game is 200 plies (`MOVE_LIMIT` in `web/src/game/board-state.ts`, `moveLimit(variant)` in `web/src/game/variant.ts`, see [rules](rules.md)); that gives 350 bytes and 467 characters, and a fragment of 481 characters with the prefix and both fields. A three-player game has 300 plies: 525 bytes and 700 characters, and 720 characters with the prefix, `&l=2&p=3&s=hhe` (measured by the unit test `web/src/app/game-url.variants.test.ts`). A limit of 1,000 plies would give 1,750 bytes and 2,334 characters by the same arithmetic (calculated, not measured); this shows the cost if the limit were raised. A game at the move limit is short enough to paste into a chat message.

### Routes

`#/bench` and `#/about` open the benchmark and the about screen. Any other fragment that is not a game fragment shows the play screen. When the user goes back from another screen to the play screen during a game, the app writes the current game into the address again.

### History

The app writes one history entry per full turn:

| Event | History call |
|---|---|
| A human moves (any human seat) | `pushState`: a new entry with the position after that move |
| An engine answers (any engine seat) | `replaceState`: the same entry now holds the answer too |
| Undo, new game | `pushState` |
| Only the strength changes | `replaceState` |
| The human swaps sides | a new game, so `pushState`; `replaceState` if no move was played yet |
| The human seat rotates (three players, swap button) | `replaceState`: the moves stay, the address shows the new seats |
| The first state of a page | `replaceState`, no extra entry |
| Engine-versus-engine demo (two engines play each other; the play screen button "Engine vs. engine") | nothing |

Back loads the previous entry: it cancels a running search (the engine terminates its Web Worker and starts a new one, see [the engine interface](#the-engine-interface)) and loads that position without a dialog. Forward does the same in the other direction. The app marks its own entries in `history.state`. An address that the user typed or pasted has no marker, so it opens the "Continue this game?" dialog instead.

Origin: a decision of the project owner. Rationale: with one entry per ply, Back would first show the position in which the engine is to move. The engine would answer at once, so Back would seem to do nothing or to fight the player. The player thinks in turns, and with one entry per turn Back means "take back my last move and the engine's answer". Limit: the rule assumes that the engines answer right after a human. With three players one entry holds the move of a human and the answers of the engines up to the next human seat; in hot-seat every move of a human is an entry of its own, because the next move is another person's. The demo writes no entries at all.

### Strict parsing

The parser is strict. It requires:

- the version prefix `g=v1.`
- a payload whose length is a whole number of moves, with zero padding bits
- only the base64url alphabet, in canonical form: no padding characters and no non-zero spare bits
- every cell between 0 and 120
- at most as many moves as the variant allows: 200 for two players, 300 for three (the variant comes from `p`, which may stand after the payload)
- `h` 0 or 1, only for two players; `l` 0 to 4 or 10 to 5,000,000, without leading zeros; the fields may come in any order, and `h` and `l` may be missing
- `j` only as `j=long`; `p` only as `p=3`; `s` of three characters from `h` and `e` with one or two `h`, required with `p=3` and not allowed without it
- no unknown fields and no repeated fields

Anything else is a broken link, which the app reports and never repairs. Rationale: a repaired link could show a game that nobody sent, and the canonical payload gives each move list exactly one text.

The parser does not check whether the moves are legal; the engine does, under the rules of the variant of the link. A hop that is legal only with the long-hop rule is an illegal move in a link without `j=long`: `legalMoves(history, variant)` rejects it, and the link is reported as broken. `legalMoves` rejects an illegal history with the error code `illegal-history` ([worker protocol](#the-worker-protocol)). The app asks the engine as soon as the engine is ready, while the dialog is still open, and again when the player continues the game. A broken link or an illegal history shows a plain message with the choice "Start a new game". A bad link never crashes the app.

### Start dialog

A page without a game link asks "Do you want to begin?". "Yes" lets the human play side 0 and move first; "No" lets the engine begin. A page opened with a game link, or a link pasted into the address bar while a game runs, opens "Continue this game?". It shows the ply of the position and the side to move, and offers to continue as the side to move, to continue as the other side, or to start a new game. While a game runs, it also offers to keep the current game; Escape does the same. The button "New game" asks "Do you want to begin?" again; Escape keeps the running game. The engine loads in the background while the dialog is open.

The dialog builds a `GameSetup { seats, variant }` (`web/src/game/setup.ts`, see [game variants](#game-variants)). A seat is `human`, `engine` or `network`. The dialog offers the two-player game of the default variant only, with `human` and `engine`; the variants start through the hook and the links described under [game variants](#game-variants). Network players are planned, and no controller plays them yet.

### Copy link

The button calls `navigator.clipboard.writeText`, which browsers offer only in a secure context (HTTPS or `localhost`). If that call fails or is missing, the button shows a read-only text field with the address selected, for copying by hand. The button is disabled during the engine-versus-engine demo and while the dialog is open.

## Game variants

The app plays two jump rules and two player counts, and each seat can be a human or an engine. A **variant** is `{ jump: 'adjacent' | 'long'; players: 2 | 3 }` (`web/src/game/variant.ts`); `DEFAULT_VARIANT` is adjacent jumps with two players, and `isDefault`, `sidesOf`, `sameVariant`, `sideAt(ply, variant)`, `moveLimit(variant)` (200 plies for two players, 300 for three) and `variantKey` are the helpers. The rules of both jump rules and of three players are in [rules](rules.md); the engines are described under [analysis API](#analysis-api) and in the [C++ README](../cpp/README.md).

**Status.** The engine layer, the game controller, the game URL and the skill tracker support the variants. The dialog for choosing a variant and the board colours of a third player are not built. The default game looks and plays as before.

### Game setup

`GameSetup` (`web/src/game/setup.ts`) is `{ seats: Seat[]; variant?: Variant }` with as many seats as players. A missing `variant` is the default. Three constructors build the game modes; one level applies to all engine seats:

| Constructor | Game |
|---|---|
| `humanVsEngine(side, level, jump = 'adjacent')` | two players, one human, one engine |
| `hotSeatVsEngine(level, engineSeat = 2, jump = 'adjacent')` | three players, two humans on the same device, the engine on `engineSeat` |
| `humanVsTwoEngines(humanSeat, level, jump = 'adjacent')` | three players, one human, two engines |

`engineVsEngine(level, jump)` is the two-player demo. Readers: `humanSeatsOf`, `engineSeatsOf`, `humanSeatOf` (the one human seat or `null`), `humanSideOf` (two players only), `isDemo`, `isHotSeat`, `levelOf`, `variantOf`, `isSupported`. Changers: `withLevel`, `withHumanSeat(setup, seat)`, `withAllEngines`. `controllerSeats(setup)` gives the `'human' | 'engine'` list that the controller takes and refuses `network` seats.

### Game controller

`GameController` (`web/src/game/game.ts`) takes `{ engine, nodes, seats?, variant?, enginePauseMs?, mode?, humanSide? }`. `mode` and `humanSide` are the two-player shortcuts that existed before; `seats` and `variant` replace them. The state (`GameState`) has `history`, `owner` (cell to seat, 0 to 2, or -1), `sideToMove` (0 to 2), `legal`, `outcome`, `thinking`, `lastEngine`, `error`, `variant`, `seats` and `canSwap`.

- **Turns.** The side to move is `ply % players`. A human seat accepts `play(move)`; an engine seat gets `choose`. `isHumanTurn()` and `isEngineTurn()` say which. Two engine seats in a row play one after another.
- **Pause.** Between two engine moves in a row the controller waits `enginePauseMs` (default 350 ms for three players, 0 for two, so the two-player demo runs at engine speed), so that the person can follow the board. `cancel()` ends the pause. Tests pass 0.
- **Hot-seat.** Consecutive human seats need no engine call and no pause; the board does not turn (a UI matter: the controller only reports `sideToMove`).
- **End of the game.** After each move the controller asks `Engine.outcome`. A win names the seat (`{ kind: 'win', winner }`). A draw is `{ kind: 'move-limit' }` at the move limit, `{ kind: 'no-moves', side }` when the side to move has no legal move, and `{ kind: 'draw' }` otherwise (a repeated position). The win and the move limit are also computed from the board at once (`outcomeOf` in `board-state.ts`), so the result card needs no round trip; a unit test and the cross-engine test check that the board and the engines agree on every win.
- **Undo.** `undo()` takes back the last move and then every move until a human seat is to move. Two players with one human: the human move and the engine answer, as before. One human and two engines: the human move and both answers. Hot-seat: one ply when the previous seat was human, else the engine move and the move of the human before it, so that the same human is to move again. A game with no human seat takes back one move. Origin: the project owner asked for "undo to the previous human turn". Rationale: after an engine move the engine would play the same move again at once, so a one-ply undo would do nothing.
- **Swap.** `canSwap` is true with at most one human seat and false in hot-seat; `swapSides` throws when it is false. Two players: the human takes the other side, as before. Three players with one human: `swapSides()` rotates the human seat to the next seat (`swapSides(seat)` names it) and the board stays. The engines then play their seats from the position.
- **Loading.** `loadGame(history, { humanSide?, seats?, variant? })` asks the engine whether the history is legal under the variant, and leaves the game as it was if not. `newGame({ seats?, variant?, humanSide?, mode?, nodes? })` starts a game with other seats or another variant.

### Starting a variant

There is no dialog yet. Two hooks start variants:

- **A game link.** `#g=v1.&p=3&s=hee` starts a three-player game with the human on seat 0, at once and without a question, because the link names the seats. A two-player long-hop link (`...&j=long`) opens the usual "Continue this game?" dialog and keeps the rule. A link whose moves are illegal under its variant is a broken link.
- **The `initialSetup` property of `App`** (`web/src/app/App.tsx`) takes a `GameSetup` and starts that game as soon as an engine is ready, without the "Do you want to begin?" dialog. A game link in the address takes precedence.

In a variant, "New game" restarts the same setup without a dialog; the swap button follows `canSwap`; the engine demo makes every seat an engine.

### What differs from the default game

- **Engines.** Only the browser engines play variants ([the engines](#the-engines)). The session loads a browser engine if the one in use is a server engine.
- **Analysis.** Move guides and the skill estimate need analysis, which exists for two players. For three players the guide switch, the advantage bar and the skill card are not shown, and no analysis request is made (checked by `web/src/app/App.variants.test.tsx`). For the long hop the guides work; the skill estimate works with the caveat under [skill estimate](#skill-estimate).
- **Board.** The board draws seat 2 in the style of the second side, and the labels "your piece" and "engine piece" are for two sides. The hot-seat labels and the third colour belong to the variant UI. One further limit of the board: its keyboard tab stop stays on the piece that moved last, which is not a movable piece in hot-seat.

Tests: `game.variants.test.ts`, `setup.variants.test.ts`, `variant.test.ts` (controller, setup, board state), `app/game-url.variants.test.ts`, `app/App.variants.test.tsx`, `engines/variants.test.ts`, `engines/variants.cross.test.ts` (whole games on all three browser engines), and `web/e2e/variants.spec.ts` (real browser).

## The engines

| Engine id | Language, compiler | Shipped files | Size raw (gzip) | Browser needs |
|---|---|---|---|---|
| `cpp-wasm` (fallback without Wasm GC) | C++, Emscripten 6.0.9 | `engines/cpp/engine-cpp.js`, `engine-cpp.wasm` | 332,585 bytes (119,979) | WebAssembly; no threads, no `SharedArrayBuffer` |
| `java-wasmgc` (default) | Java, TeaVM 0.16.0, Wasm GC target | `engines/java/engine-java.wasm`, `engine-java.wasm-runtime.js` | 609,221 bytes (208,705) | Wasm GC and the final version of WebAssembly exception handling, which TeaVM's output uses: Chrome 137, Firefox 131, Safari 18.4 according to MDN |
| `java-js` | Java, TeaVM 0.16.0, JavaScript target | `engines/java/engine-java.js` | 337,073 bytes (123,277) | any current browser |
| `server:browser` | Java on the server | none | | online mode |
| `server:best` | Java on the server, `BEST_SPEC` | none | | online mode, and the server has `rows1.bin` |
| `server:net` | Java on the server, network player (MCTS) | none | | online mode, and the server has a weights file |

The sizes come from `web/public/engines/engines.json` of the build of 2026-11-19 (the sizes grew with the long-hop and three-player code). `scripts/web/engines-manifest.zsh` writes this file with raw size, gzip size and SHA-256 of every shipped file, and the UI and the benchmark read the sizes from it.

All three browser engines and `server:browser` play `BROWSER_SPEC` with the node budget appended (`BROWSER_SPEC,nodes=N`). The full player specification is fixed inside each engine, not in the UI, because a different transposition-table size changes the search and with it the moves.

`web/src/engines/registry.ts` checks for Wasm GC with `WebAssembly.validate` on a 13-byte module that declares one struct type. If the check fails, the engine picker shows `java-wasmgc` as unavailable and the session starts with `cpp-wasm`; `java-js` stays selectable. The session starts with the first available engine in the order `java-wasmgc`, `cpp-wasm`, `java-js` ([ADR 0003](adr/0003-teavm-default-engine.md)). A definition may also declare `supportsVariant(variant)`. `describeEngines(manifest, variant)` then marks an engine that cannot play the variant as unavailable with the reason `variant`, and the engine sheet says "This engine plays only the standard game." The three browser engines play every variant; the server engines play the default variant only. `EngineSession.setVariant()` lists the engines again for the variant of the next game and loads the first engine that can play it if the engine in use cannot.

No network player runs in the browser: the C++ port has no Monte Carlo tree search, and the TeaVM build of it was never run.

### The server engines

`app/src/main/java/sternhalma/app/GameEndpoint.java` has three methods, each called as `POST /connect/GameEndpoint/<method>`:

- `engines()`: the list of server engines. `browser` always; `best` when the server finds `rows1.bin`; `net` when the property `sternhalma.app.weights` names a readable safetensors weights file.
- `legalMoves(history)`: the legal moves after the history.
- `choose(engineId, history, nodes)`: replays the history, creates a fresh player, searches and returns the same fields as `ChooseResult` ([the engine interface](#the-engine-interface)). The node budget must be between 1 and 5,000,000. A semaphore limits concurrent searches to the number of CPU cores.

The server engines play the default variant only (adjacent jumps, two players): `GameEndpoint` has no field for the jump rule or the player count. The adapter (`web/src/engines/server/client.ts`) rejects every other variant with the code `unsupported` before it calls the server, in `newGame`, `legalMoves`, `choose`, `analyse` and `outcome`. For the default variant it computes `outcome` itself from the board rules (a win, the move limit, no legal move).

The server is stateless: no transposition table carries over between calls. The `net` player turns the node budget into `nodes / 100` simulations, at least one.

## The engine interface

`web/src/engines/engine.ts` defines it:

```ts
export interface Engine {
  readonly info: EngineInfo;
  init(onProgress?: (p: Progress) => void): Promise<LoadReport>;
  newGame(setup: GameSetup): Promise<void>; // setup: { nodes: readonly number[]; variant?: Variant }
  legalMoves(history: readonly number[], variant?: Variant): Promise<number[]>;
  choose(history: readonly number[], signal?: AbortSignal, variant?: Variant): Promise<ChooseResult>;
  analyse(history: readonly number[], moves: readonly number[] | null, nodes: number, signal?: AbortSignal, variant?: Variant): Promise<AnalysisResult>;
  outcome(history: readonly number[], variant?: Variant): Promise<EngineOutcome>;
  dispose(): void;
}

// EngineOutcome = { kind: 'ongoing' } | { kind: 'win'; winner: 0 | 1 | 2 } | { kind: 'draw' }
```

A move is one number: `from | (to << 8)`, with cell ids 0 to 120 as in `engine/src/main/java/sternhalma/engine/Move.java`. The C++ port uses the same ids.

Rules behind the interface:

- **The move history is the game state.** `choose` and `legalMoves` take every move since the start position, and the engine replays them. The search needs the earlier positions to handle repetitions, and a game has at most 200 plies, so the replay is cheap. Between calls of one game the engine keeps one player per seat, so the transposition table carries over. `newGame` drops the players. After an undo the UI calls `newGame` and continues.
- **Switching the engine keeps the game.** `web/src/app/switchable-engine.ts` swaps the engine behind the game controller (`GameController` in `web/src/game/game.ts`, which holds the history and calls the engine); the new engine replays the history on its next call.
- **The variant is an argument.** `legalMoves`, `analyse` and `outcome` replay the history under the `variant` they get (default: adjacent jumps, two players), so they need no game. `newGame(setup)` takes `setup.variant` and a node budget per seat (`setup.nodes`: two entries for two players, three for three; a missing entry repeats the one before). `choose` plays for the side to move with the variant of the last `newGame`; a `variant` argument that differs from it is an error (`internal`). The `WorkerEngine` client replays `newGame` with its variant after it restarted the worker. `outcome` says whether the game after the history goes on, was won (by seat 0, 1 or 2) or ended without a winner (`draw`: the move limit, a repeated position, or a side without a legal move); it rejects an illegal history with `illegal-history`. An engine that cannot play a variant rejects with the code `unsupported`.
- **The race table is for one variant.** Only the adjacent two-player game reads it. Long-hop play and three-player play have no race play, so `newGame`, `legalMoves`, `choose` and `outcome` for those variants work before a table is installed (and ignore an installed one). The adjacent two-player game still fails with `internal` ("the race table is not installed") until `installTable` ran.
- **Cancel means terminate.** No engine can stop a search early. When the `AbortSignal` fires, `web/src/engines/client.ts` terminates the worker and starts a new one. The main thread keeps the table bytes, so the restart does not download them again.
- **The board does not come from the engine.** The UI draws the board from `web/src/board/cells.generated.json` before any engine has loaded. A browser test compares this file with the cell coordinates of the C++ module.
- **Jump paths are for display only.** `web/src/board/path.ts` finds the chain of jumps for the move animation by a breadth-first search over the board. Which moves are legal always comes from `legalMoves`.

`ChooseResult` reports the move, score, search depth, nodes, wall time, whether the move came from race play (the race table, without search), and `rows1Requests`. `rows1Requests` must stay 0: it counts requests for `rows1.bin`, which the browser engines must never make (ADR 0001).

## The worker protocol

`web/src/engines/protocol.ts` defines it. Each request carries an `id`; the worker answers with exactly one reply with the same `id`. Progress events during `init` carry the `id` of the running request.

| Request `type` | Fields | Reply `result` |
|---|---|---|
| `init` | `assets` (URLs of engine files, table manifest, table), `table` (bytes the main thread already holds, transferred, or `null`) | `LoadReport` |
| `newGame` | `setup.nodes`: node budget per seat (two or three entries); `setup.variant` (optional) | `null` |
| `legalMoves` | `history`, `variant` (optional) | `number[]` |
| `choose` | `history`, `variant` (optional: the variant of the last `newGame`) | `ChooseResult` |
| `outcome` | `history`, `variant` (optional) | `EngineOutcome` |
| `bench` | `plies`, `nodes`, `variant` (optional) | `BenchResult` (the benchmark game, played inside the worker) |

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
| `unsupported` | the engine cannot do this: a server engine asked for another variant than the default, or analysis for three players |
| `aborted` | the request was cancelled |
| `internal` | anything else |

## Analysis API

`Engine.analyse(history, moves, nodes, signal?, variant?)` scores candidate moves after a history and returns an `AnalysisResult`:

- `moves`: the scored moves. If the call passes `null`, these are all legal moves in the order of `legalMoves`; otherwise the given subset.
- `scores`: one score per move, in the order of `moves`.
- `position`: the best of `scores`, so the value of the position for the side to move (the best of the subset if a subset is given).
- `nodes`: the nodes searched for all moves together; `ms`: the wall time inside the worker.

The worker request has the type `analyse` with the fields `history`, `moves` and `nodes`; the reply is an `AnalysisResult`. Error codes, in addition to those of [the worker protocol](#the-worker-protocol): `illegal-move` (a move is not legal, or the list is empty), `illegal-history` (the game is over or the history is invalid) and `internal` (`nodes` is less than 1). An abort works as for `choose`: the client terminates the worker and starts a new one. The server engines reject the call with the code `unsupported`, because `GameEndpoint` has no analysis method yet. `rows1Requests` stays 0 as for `choose`.

**Method.** For each move, the engine makes the move, searches the resulting position with the full node budget and negates the score. The budget therefore applies per scored move. The search runs in a separate analysis player, not in the game players. It has its own transposition table of 2^16 entries, which the engine clears before every move, so a score depends neither on earlier calls nor on the order of the moves. Its specification is `BROWSER_SPEC` without race play (`race=1,racerows=2` removed), plus `tt=16`. A player specification is a comma-separated list of options: `race=1` turns on race play, `racerows=2` selects the table `rows2.bin`, `tt=N` sets a transposition table of 2^N entries, and `nodes=N` sets the node budget. Because the game players stay untouched, `choose` results and the benchmark fingerprint do not change; a test runs an analysis between every two moves and checks this. The three browser engines return identical scores for the same position and budget. The code is in `shw_analyse` in `cpp/src/wasm/wasm_api.cpp`, in `WebCore.analyse` in `teavm-web/`, and in `web/src/engines/cpp/core.ts` and `web/src/engines/java/core.ts`.

**Jump rule (TeaVM engines).** `WebCore` and the `Web` facade in `teavm-web/` play either jump rule of [docs/rules.md](rules.md) ("Jump rule"): `adjacent` (default) or `long` (Halma long hop). Every facade entry point that replays a history or searches takes the rule as a string argument: `newGame(nodes0, nodes1, jump, players, nodes2)`, `legalMoves(history, jump, players)`, `choose(history, jump, players)`, `analyse(history, moves, nodes, jump, players)`, `bench(plies, nodes, jump, players)` and `outcome(history, jump, players)`. The `players` argument is described under [Three players](#three-players-teavm-engines). `"adjacent"`, `""` and `null` mean `adjacent`, `"long"` means `long`, and any other text fails with `error internal`. The TeaVM JavaScript target does not turn a missing argument into `null` (it arrives as `undefined` and fails), so the caller always passes the argument. The rule applies to the whole call: the history is replayed under it, so a move that is legal only with a long hop, sent with `adjacent`, gives `error illegal-history`. A game plays one rule: `choose` with a rule other than the one of the last `newGame` gives `error internal`; call `newGame` again.

For `long`, the game players use `BROWSER_LONG_SPEC` and the analysis player uses `BROWSER_LONG_SPEC,tt=16`. Both have no race play and no race evaluator, because the race table counts single jumps only. `long` therefore needs no table: it works before `setTable` and ignores an installed one, and `choose` always reports `raceMove` 0 and `rows1Requests` 0. `adjacent` is unchanged and still fails with `error internal` ("no table installed") before `setTable`. `analyse` does not depend on the rule of the current game. Scores of `long` use the same scale and mate bounds as above, but they come from the weaker evaluator `linear-r3`, so they are not comparable with `adjacent` scores. The C++ engine plays both rules through `shw_game_new2(jump, players)` and `shw_player_new(nodes, jump)` ([C++ README](../cpp/README.md), "Browser API"). The three browser engines give the same moves, scores and node counts for long-hop games and the same outcome (`web/src/engines/variants.cross.test.ts`, which plays whole games on all three, without a race table).

#### Three players (TeaVM engines)

The `players` argument of every entry point above is the string `"2"` or `"3"`; `""` and `null` mean `"2"`, any other text fails with `error internal`. Pass it always, as for `jump`. Two-player behaviour and the benchmark fingerprint do not change.

- **`newGame(nodes0, nodes1, jump, players, nodes2)`** returns `ok`. For `"3"` it creates one `ThreePlayerPlayer` per seat (spec `BROWSER_3P_SPEC` or, for `long`, `BROWSER_3P_LONG_SPEC`, with `nodes=` set per seat), each with its own transposition table. A `nodes2` of 0 or less means `nodes1`. For `"2"`, `nodes2` is ignored; pass 0.
- **`choose`, `legalMoves`, `bench`** replay the history on the default rules for that player count (300 plies for three players) and the given jump rule. For three players `choose` returns the same fields as for two, with `raceMove` 0 and `rows1Requests` 0; the score is the paranoid score from the mover's view. Three players need no table. A game keeps its player count: `choose` with another count than the one of the last `newGame` gives `error internal`.
- **`analyse`** with `"3"` gives `error unsupported analysis is available for two players only`. Analysis is two-player only.
- **`outcome(history, jump, players)`** returns `ok ongoing`, `ok win <side>` (side 0, 1 or 2) or `ok draw`. A `draw` is every end without a winner: the move limit, repetition and a side without a legal move. At the move limit the rules score the game by distance, which names no winner, so the facade does not either. A history that is illegal gives `error illegal-history`.
- The call with a finished game: `legalMoves` returns an empty list, `choose` gives `error illegal-history`.
- On the TypeScript side, `analyse` for three players rejects with the code `unsupported` before the facade or the module is called, on all three browser engines and in the fake engine of the tests. The C++ module answers `shw_analyse` with -3 for three players.

The code is in `WebCore.newGame3`, `WebCore.outcome` and the `Web` facade in `teavm-web/`; the player is described in [Three-player browser player](search-and-evaluation.md#three-player-browser-player).

**Scores.** The unit is the same as for `ChooseResult.score`. A score is always seen from the side that makes the move, and higher is better. A move that wins at once scores 999,999 (the mate value 1,000,000 minus one); a move that allows the opponent to win with the next move scores -999,998. In race positions, `choose` reports 0 for a move from race play, because race play does not search. The analysis player has no race play and searches such positions like any other, so its scores there are meaningful.

| Band | Range of \|score\| | Source of the leaf values |
|---|---|---|
| ordinary | below 5,000 | linear evaluation; about 30 points per step towards the goal corner |
| race | 5,000 to below 999,000 | race table; 200 points per race move |
| win | 999,000 and above | a forced win was found |

In the ordinary band, a logistic fit gives the probability that the side to move wins as 1 / (1 + exp(-score / 80)): 100 points are 78 %, 200 points 92 %, 300 points 98 %. The fit comes from 4,407 positions of 60 self-play games with 1,000 against 20,000 nodes, so the strength gap inflates these rates. Scores of one position vary by about 20 points between budgets. `web/src/engines/analysis.ts` exports `SCORE` (the band constants), `scoreScale = 80`, `advantage(score)` (tanh(score / 160), which equals 2 × win probability − 1, a value from -1 to 1), `scoreBand`, `scoreLoss`, `GUIDE_NODES = 4000` and `SKILL_NODES = 20000`.

**Speed.** On `cpp-wasm`, all 68 legal moves of a mid-game position take about 0.1 to 0.2 s at `GUIDE_NODES` (4,000 nodes per move, for the [move guides](#move-guides)) and about 0.7 s at `SKILL_NODES` (20,000 nodes per move, for the [skill estimate](#skill-estimate), which runs in the background). These figures come from the comments in `analysis.ts`; the measurement setup is not recorded.

**Tests.** `web/src/engines/testing/analysis-suite.ts` runs against the C++ engine and both TeaVM targets; `web/src/engines/analysis.cross.test.ts` and `web/src/engines/variants.cross.test.ts` compare the engines; further tests are `WebFacadeTest` in `teavm-web/` and `cpp/wasm/test/analyse.mjs`.

## Move guides

Move guides are optional marks on the board that show how good each move is. The switch "Show move guides" turns them on; they are off by default. The app stores the choice in `localStorage` under the key `ml.guides` and works without storage too; the choice then lasts for the page only.

**What is shown.**

- **Piece badges.** Each piece that can move gets a badge with a ring. It rates the best move of that piece against the best move of the whole position.
- **Target marks.** After the human selects a piece, each target cell gets a ring and a mark. It rates that move against the value of the position.
- **Advantage bar.** A gauge near the board shows who is ahead, with a text such as "You: slightly ahead".
- **Legend.** "What the marks mean" under the switch lists the five bands and their marks.

The marks appear only while the board accepts input, that is on the human's turn.

**The five bands.** Targets and pieces share five bands, five marks and five ring styles. A step is one step of progress towards the goal corner, about 30 points ([analysis API](#analysis-api)).

| Band | Mark | Target: difference to the position value | Piece badge: its best move behind the best move of the position |
|---|---|---|---|
| much better | ▲▲ | 90 points or more | up to 30 points (text "best move: top") |
| better | ▲ | more than 30, less than 90 | up to 90 (text "good") |
| about equal | = | -30 to 30 | up to 180 (text "fair") |
| worse | ▼ | more than -90, less than -30 | up to 300 (text "weak") |
| much worse | ▼▼ | -90 or less | more than 300 (text "poor") |

The limits are in `web/src/guides/bands.ts`: one step (30 points) is the half width of "about equal", because the scores of one position vary by about 20 points between budgets; three steps (90 points) mark "much". The piece ranks use 1, 3, 6 and 10 steps. Before the comparison, every forced win counts the same and every forced loss counts the same, so that two winning moves are never told apart by the number of plies to the win.

**How the scores are computed.** On the human's turn, while the engine is idle, `web/src/guides/analyser.ts` calls `Engine.analyse(history, null, GUIDE_NODES)`: all legal moves, 4,000 nodes each. One position is analysed at a time. The result is cached by engine and history, so Undo and Back do not repeat the work. A move of the human, an undo or the engine's turn aborts a running analysis; the adapter then restarts its worker, as for any abort. `web/src/guides/model.ts` turns the scores into bands (`buildView`).

**The advantage bar.** It shows the value of the position from the human's side. The text uses the score: below 30 points "Even", below 100 "slightly ahead", below 200 "clearly ahead", from 200 on "far ahead"; in the race band "ahead in the race"; for a forced win "wins by force in N plies" (`web/src/guides/advantage.ts`). After the human moves, the bar shows the cached score of that move at once. After the engine answers, the bar is dimmed and marked "updating" until the analysis of the new position is ready.

**The reference problem.** The analysis returns the best score as the value of the position (`AnalysisResult.position`). Target bands compare each move with that value, so no target can be better than the position: the best target is "about equal", and the others are "about equal", "worse" or "much worse". The bands "better" and "much better" therefore appear only on piece badges, where they mean "top" and "good". `buildView(result, reference)` accepts another reference value, but no honest one is available: the engine's last `score` is 0 after a move from race play and is out of date after an undo. This stays open.

**Accessibility.**

- Colour is never the only carrier: each band has its own mark and its own ring style (width and dash pattern).
- The accessible name of a cell includes the band, for example "e5, empty, target, much better", and of a piece its rank, for example "best move: top".
- The advantage bar is a `role="meter"` with the text as `aria-valuetext`. The state of the analysis ("Analysing the position") is a polite live region.
- The five guide colours reach a contrast of at least 3:1 against the board colours in the light and the dark theme; `web/src/guides/tokens.test.ts` computes the ratios from `tokens.css`. In forced-colours mode the marks use system colours.
- The marks have no animation.
- On small screens the marks grow with the other board marks ([small screens and touch](#small-screens-and-touch)).

**Variants.** The guides are for two players. For three players there is no analysis, so the switch, the advantage bar and the legend are not shown and no analysis is requested. For the long hop the analysis runs under the long-hop rule (`BROWSER_LONG_SPEC` without race play), and the app passes the variant with every request; the cache key includes the variant. The bands were fitted for the adjacent rule: the long-hop evaluator `linear-r3` is weaker, so the same point difference may mean a different move quality. Nobody measured this.

**Server engines.** The server engines reject `analyse` with the code `unsupported`. The guides then show "Move guides need an engine that runs in this browser." and draw no marks; choosing a browser engine brings them back. Any other error shows "Move guides are not available right now." for that position. A failure never stops the game.

**Tests.** `web/src/guides/*.test.ts` (bands, model, advantage text, marks, preference, analyser, contrast), `web/src/ui/Board.guides.test.tsx` (marks and accessible names on the board) and `web/e2e/guides.spec.ts` (the switch and the marks in a browser).

## Skill estimate

While the human plays, the app scores each of the human's moves against the best move of the same position. From these losses it estimates a node budget that plays about as well, and one button sets the engine to it. The estimate is rough, and it is valid only for this scoring.

**What is measured.** After each human move, the position before that move is analysed with `Engine.analyse(history, null, SKILL_NODES)` (all legal moves, 20,000 nodes each, see [analysis API](#analysis-api)). The loss of the move is `scoreLoss(best score, score of the move played)`, in score points (about 30 points per step towards the goal corner). The statistic of a set of moves is the mean of the losses, each capped at `LOSS_CAP = 200` points (`web/src/skill/stats.ts`). The median is shown in the data but not used for the mapping.

- *Why a capped mean.* A good player plays the best move most of the time, so the median is 0 for a wide range of strengths and cannot tell them apart (in the table below it is 0 at every budget from 1,000 nodes on except 2,000). The plain mean fails the other way: race and mate scores reach 1,000,000 points, so one blunder into a lost position would be the whole statistic. The cap limits one move to 200 points, one move of race arithmetic and a swing of about 92 % in win probability.
- *Minimum.* No estimate is shown below 8 analysed moves (`MIN_MOVES`). The skill card shows "N of 8 moves analysed" until then. With 8 moves the interval is wide; the card always shows the number of moves the estimate rests on.
- *Interval.* The card shows an 80 % interval: a seeded percentile bootstrap of the mean of the capped losses (400 resamples), mapped through the same function. It shows the sampling noise of the player's moves only. It does not include the noise of the calibration.

**When it runs.** `web/src/skill/tracker.ts` (`SkillTracker`, framework-free) runs one analysis at a time, newest human move first, in the background of the engine worker.

- It starts only while the engine is not thinking and it is the human's turn, after the legal moves of the position are loaded, and after the game has been quiet for 800 ms. One worker handles one request at a time and an analysis takes about a second. Without the quiet period, an analysis started right after the engine's move delayed the legal-move request of the next turn (found by the browser test `web/e2e/flow.spec.ts`). A player who moves faster than 800 ms after the engine's reply never waits for an analysis; one who moves while an analysis runs waits for it, up to about one second, before the engine starts its search.
- It aborts the running analysis when the engine starts to think, when the history no longer contains the analysed position (undo, new game, a loaded game), and when the engine loads or is replaced. The adapter then restarts its worker, as for any abort. A result that arrives after an abort is dropped.
- Results are cached by position and move. Undo, Back and an engine switch do not repeat finished work. The cache is valid across engines because all browser engines return identical analysis scores.
- Only moves that the person at the board played count. The moves of an opened game link and the moves before a swap of sides are not analysed. The card shows the progress of the current game as "Analysing your moves: n of N".
- A server engine rejects `analyse` with the code `unsupported`. The tracker then stops, and the card says that this engine cannot analyse moves. Choosing another engine tries again. Any other error stops the tracker and the card says so; the next engine switch resets it.

**Kept over many games.** The losses of the measured human moves are kept in this browser, in `localStorage` under the key `ml.skill.v1` (`web/src/skill/ledger.ts`). Each entry holds only a 64-bit hash of the position with the move played, and the loss; no moves are stored. The store keeps at most 1,000 entries (`LEDGER_MAX`), the newest ones. Nothing leaves the browser. The estimate uses all stored moves, over all games on this device. If `localStorage` is blocked, the store lives in memory until the page closes.

Two buttons on the skill card are for the case that another person takes over the device:

- "Pause measuring" stops the analysis and the recording. The pause is stored too, so it survives a reload. Moves played during a pause never count, also not after "Resume measuring".
- "Delete measurements" clears the store and starts counting from the current position; the moves played so far stay out.

**Variants.** The ledger is kept per jump rule: an entry belongs to the rule the move was played under, and the estimate uses the entries of the rule of the game that is on. The file in `localStorage` stays `ml.skill.v1`: the entries of the adjacent rule are the `entries` list as before, and the long-hop entries are an optional second list `long`, which older files do not have and the reader accepts as missing. A damaged `long` list voids the file like any other damage. Three players are not measured: the tracker starts no analysis, `TrackerState.enabled` is false, `ledger` is empty, and the skill card shows nothing. "Delete measurements" clears every list. `TrackerState` carries `variant`, `enabled` and `calibrated`; `estimateForVariant(losses, calibration, variant)` in `web/src/skill/estimate.ts` returns the estimate with a flag `calibrated`, or `null` for three players.

**The long hop is not calibrated.** `calibration.json` was fitted on two-player games with adjacent jumps, and nobody measured which node budget matches a loss statistic under the long hop. The estimate for the long hop uses the adjacent calibration for lack of a better one and is marked `calibrated: false`; the skill card then adds the note "This estimate uses the calibration of the standard game. For long jumps it is only a rough guide." A long-hop calibration would need the script `scripts/web/calibrate-skill.zsh` to play the long-hop rule.

**Calibration.** The mapping from the statistic to a node budget comes from measurements, not from a model. `scripts/web/calibrate-skill.zsh` (code in `scripts/web/calibrate-skill.mts`; run it inside `devenv shell`, after `devenv tasks run web:engines web:tables`) lets the C++ engine play whole games against itself at each node budget of a ladder: the 13 budgets from 50 to 500,000 nodes in the table below, one rung each. The first 4 plies are random legal moves, seeded with 1000 to 1015, the same 16 seeds for every budget. For every engine move after the opening, the script measures the loss with the same analysis as the app (`analyse` at `SKILL_NODES`, same engine code). `web/src/skill/calibration.json` holds the result with the seeds, the number of games and moves, the engine version, the date and the definition of the statistic.

Result (16 games per budget, both sides measured, committed on 2026-11-18, engine built from commit `3ac176f`):

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

- *Monotonicity.* The raw ladder is not monotone: 3 of 12 neighbouring pairs increase with the budget (1,000 to 2,000, 100,000 to 200,000, 200,000 to 500,000). The first increase (0.2 points) is within the standard error. The other two are larger than the combined standard error (1.2 points against about 0.6, and 2.5 against about 0.7), and the last shows the effect: 500,000 nodes lose more against the 20,000-node analysis than 100,000 nodes do. A search with much more budget than the analysis sees better moves than the analysis can confirm, so "agreement with a 20,000-node search" stops measuring strength there. The app therefore uses the isotonic fit (pool-adjacent-violators, weighted by the number of moves): the closest fit that never increases with the budget. The column "After smoothing" shows it.
- *The strong end.* From 50,000 nodes on, the smoothed ladder is flat. `resolvedUpTo` in `web/src/skill/calibration.json` (50,000) is the first budget within one standard error of that plateau, and the mapping uses only rungs up to it. A player whose statistic is as low as that or lower gets "at least 50,000 nodes" (the card says the real strength may be higher). Levels 4 and 5 (100,000 and 500,000 nodes) lie on the plateau, so this method cannot tell them apart from each other or from 50,000 nodes. A higher `SKILL_NODES` would move that limit up and make the background analysis proportionally slower.
- *The weak end.* The engine at 10 and 20 nodes gave the same results as at 50 nodes (all 16 games gave the same number of moves and the same statistic), so 50 nodes is the weakest setting. Its statistic, 19.5, is far below that of a random mover: a test on the real engine measured 86.4 for 12 random moves (seed 2). A player who loses more than 19.5 points per move on average is weaker than anything the engine can be set to; the card then says "weaker than anything measured", and the button sets 50 nodes.
- *The mapping* (`nodesForStat` in `web/src/skill/calibration.ts`) is piecewise linear between ladder points on log(nodes) and clamped at both ends. On a plateau it returns the middle of the plateau on the log scale. The card describes the result relative to the five levels ("between level 2 and level 3"), rounds it to two significant figures and shows the interval in nodes. Levels are counted from 1, as in the engine card.

**The button.** "Match engine to my strength" sets a custom level: `level` is then a node budget of 10 to 5,000,000 instead of an index 0 to 4 (`web/src/skill/level.ts`). The engine card shows "Custom" and the budget, and the game URL carries the budget in `l=` (see [game URL](#game-url)). The game stays: `onApply` in `App.tsx` makes a new engine game with the new budget, and the engine replays the history on its next search. "Back to preset level" goes back to the last preset level. The engine sheet shows the default level while a custom level is active; applying it replaces the custom level.

**Limits.**

- Few moves mean a wide interval; 8 moves are a minimum, not a good sample. Games differ in how hard the positions are, and the statistic does not correct for that.
- The estimate says how much your moves lose against this program's own evaluation at 20,000 nodes. It is not a rating, says nothing about other players, and is not comparable with Elo. Different engines give the same numbers, because the analysis scores are identical in all browser engines; the estimate does not depend on which engine you play against.
- The calibration measures engines playing against themselves. A human may lose points in other ways than an engine with a small budget does, so the same statistic can mean a different strength for a human. The mapping is only an approximation of "the engine that loses as much as you do".
- The data come from one engine build. A change to the search or the evaluation makes `calibration.json` stale; run the script again.

**Tests.** `web/src/skill/*.test.ts` cover the statistic, the interval, the mapping, clamping, the minimum-moves rule, isotonic smoothing, the level encoding and the tracker (with a controllable fake engine). `web/src/app/App.skill.test.tsx` runs the app with the fake engine. `web/src/skill/skill.real.test.ts` plays a simulated player on the real C++ engine: the best mover gets the strongest end of the ladder, the random mover the weakest (skipped when the engine build is missing). `web/e2e/skill.spec.ts` runs the whole flow in a browser.

## Small screens and touch

The play screen has two presentations of one set of content. The breakpoint is 60 rem (960 px at the default font size). `useWide()` in `web/src/ui/useMedia.ts` reads it for React, and `web/src/ui/mobile.css` repeats it as `max-width: 59.99rem` for the layout.

**Wide screens (60 rem and up).** Board on the left, side column on the right. The side column holds the notice (the card for loading, result or error), the four game buttons (Undo, New game, Swap sides, Engine vs. engine), the move guide switch, copy link, the engine card, the skill card, the figures of the last engine move and the move list.

**Small screens.** The status bar stays on top, the board fills the space below it, and a bar at the bottom holds Undo, New game and Menu. Everything else sits in the menu, a sheet that opens from the Menu button: Swap sides, Engine vs. engine, move guides, copy link, engine card with the engine sheet, skill card, figures, move list, the theme switch, and the footer note. The advantage bar stays outside the menu, under the board. `PlayScreen.tsx` builds that content once (`cards`) and places it in the side column or in the menu.

- **Portrait.** Header (one row from 320 px; the mode badge keeps only its icon below 26 rem), status bar, board, advantage bar, bottom bar. The page is exactly as high as the viewport (`100dvh`) and does not scroll; the footer is hidden on this screen because the menu holds its content.
- **Landscape phones** (height up to 32 rem). The header is one row. The board stands on the left. A column 12 to 17 rem wide holds the status bar, the advantage bar and the three actions, stacked.
- **No layout shift.** The board is an SVG that fills a grid cell and scales with `preserveAspectRatio` meet. Loading, result and error cards lie over the lower part of the board (`.ml-play__notice`) and take no room. The slot of the advantage bar is reserved as soon as the move guides are on.
- **Safe areas.** `viewport-fit=cover` in `index.html`; the app, the footer and the sheets add `env(safe-area-inset-*)` as padding.

**The menu** (`Menu.tsx`). A button with `aria-haspopup="dialog"`, `aria-expanded` and `aria-controls`; the sheet is a `role="dialog"` with `aria-modal`. It uses `useDialog` (`useDialog.ts`), the same hook as the start dialog: focus moves in, Tab and Shift+Tab stay inside, Escape closes (also when focus is outside the sheet, as happens in Safari after a tap), focus returns to the button. `useDialog` also makes every other child of `document.body` inert while the sheet is open. The sheet closes with Escape, the close button, a tap on the backdrop, or a swipe down on its top edge (a pull of 96 px, or 24 px at 0.5 px/ms or faster; `shouldDismissSwipe` in `touch-model.ts`). The engine sheet opens above the menu; Escape closes the engine sheet first. With `prefers-reduced-motion` the sheet has no animation. The reason for the focus return through a ref instead of `document.activeElement`: Safari does not focus a button that is tapped, so the active element at that moment is the page body.

**Touch input on the board** (`Board.tsx`, `touch-model.ts`).

- **Tap and tap.** A tap on a piece selects it, a tap on a target plays the move. The hit circle of each cell is at least 44 CSS px wide (`hitRadiusUnits`): on a board 320 px wide the drawn cell is about 16 px across (23 of the 468 units of the board width), the hit circle 44 px, so neighbouring circles overlap. The nearest cell centre decides, not the topmost circle.
- **Drag and drop** with Pointer Events. A press on a movable piece followed by a move of more than 6 px picks the piece up and selects it. The piece follows the pointer; the nearest legal target within 22 px of the pointer is highlighted. Release there plays the move. Release anywhere else cancels the drop and leaves the piece selected. `pointercancel` cancels without a move. The click that follows a drag is ignored. This works with finger, pen and mouse.
- **Gestures.** `touch-action: none` and `user-select: none` apply to the board only, and its `contextmenu` event is cancelled, so a long press opens no menu. The rest of the page uses `touch-action: manipulation` (no double-tap zoom; pinch zoom stays).
- **Vibration.** A short `navigator.vibrate` on select, move and illegal target, wrapped in `try/catch`. Safari on iOS has no `vibrate`; nothing depends on it.
- **No hover.** The mouse preview of a target is an addition; every action works without it.
- **Keyboard and screen reader** work on every screen size: one tab stop, arrow keys, Enter and Space, Escape.
- **Marks at small sizes.** Below 0.9 px per board unit (a board narrower than about 420 px) the select ring, the target rings and dots, and the guide badges grow by up to 1.5 times (`markBoost`, CSS variable `--ml-boost`). Colour is still never the only carrier: each band has its own glyph.

**Targets.** Buttons, links, the language and theme switches, the sliders and the switch labels are at least 44 px high and wide on small screens. `web/e2e/mobile.spec.ts` measures this.

**Tests.** `touch-model.test.ts` (mapping, snapping, swipe rule, sizes), `Menu.test.tsx` (open, close, focus, Escape, backdrop, swipe, inert), `Board.touch.test.tsx` (tap, drag, snap, cancel, jitter, vibrate, long press, keyboard), `App.mobile.test.tsx` (the play screen with the matchMedia of a phone). `web/e2e/mobile.spec.ts` runs four emulated devices in Chromium and WebKit (320 x 568, 390 x 844, 844 x 390, 820 x 1180) against the real engine: no horizontal scroll, all targets at least 44 px, no board shift while the engine loads, the menu, a move by tap and by drag, and screenshots (directory from `SHOT_DIR`, default `test-results/screens`). Firefox has no mobile emulation in Playwright, so the spec skips it.

**Limits.**

- Playwright's WebKit is not Safari, and an emulated device is not a real one: nobody has tried the screen on a real iPhone or Android phone. The safe-area insets are zero in emulation.
- The result card covers the lower part of the board on a small screen. The final position is still visible above it, but the card has no close button.
- Dragging is tested with the mouse in both browsers and with real touch events through the DevTools protocol in Chromium only.

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

The 14-day cooldown for npm, uv and Dependabot and the `.npmrc` files are described in [supply-chain.md](supply-chain.md).

## Known limits

- **Safari and Wasm GC.** MDN lists Safari 18.4 for the Wasm GC features TeaVM uses; caniuse lists no Safari version. Nobody has tested a real Safari. When the feature check fails, the session starts with `cpp-wasm` instead.
- **Benchmark in the browser.** The benchmark screen of the app (`#/bench`) runs the benchmark in the browser, but a web page cannot read the CPU time of a worker and reads memory only for `cpp-wasm`. The separate benchmark page `web/bench/index.html` is not part of the Vite build, and under the Vite development server it fails to load the C++ engine. The benchmark in Node.js (`devenv tasks run web:bench`) works. Details in [web benchmark](web-benchmark.md#what-the-benchmark-does-not-measure).
- **Node for the Hilla build.** The Vaadin Gradle plugin downloads its own Node.js (24.22.2) into `~/.vaadin` for the production front-end build. No plugin option to use the Node.js of devenv was found.
- **TornadoVM in the online mode** was run once, on 2026-11-18 on an Apple M3 Max: the server selected the Metal device and `server:net` answered a move with a small test weights file. Its playing strength in the online mode was not measured.
- **No offline cache.** The site has no service worker, so it needs the network on every visit.
