# ADR 0002: C++ compiled to WebAssembly is the default browser engine; TeaVM is optional

- **Status:** accepted
- **Date:** 2026-11-18
- **Decided by:** the project owner; the measurements below confirm it
- **Code:** `cpp/src/wasm/`, `teavm-web/`, `web/src/engines/`; details in [web UI](../web-ui.md)

**TL;DR:** The offline browser build offers three engines that play identical moves. The default is the C++ port compiled to WebAssembly with Emscripten, because it is the fastest (1.88 million nodes per second in Node.js, 1.95 times TeaVM's Wasm GC build), the smallest download (105 KiB gzip) and runs in every current browser. The Java engine compiled by TeaVM stays selectable in two forms, Wasm GC and JavaScript, because showing Java in the browser is a purpose of the project. The Wasm GC form needs a recent browser; the JavaScript form runs everywhere but is 7.1 times slower than the default.

## Terms

- **Browser engine:** code that runs the classical player `BROWSER_SPEC` inside the page, in a Web Worker. `BROWSER_SPEC` is the alpha-beta player that reads only the small race table `rows2.bin` ([ADR 0001](0001-browser-mode-without-rows1.md)).
- **Emscripten:** the compiler from C and C++ to WebAssembly (<https://emscripten.org/>).
- **TeaVM:** an ahead-of-time compiler from JVM bytecode to JavaScript and WebAssembly (<https://teavm.org/>).
- **Wasm GC:** WebAssembly with garbage collection. TeaVM 0.16.0 has no other WebAssembly backend.
- **Nodes per second:** search speed; a node is one position the search visits.
- **Offline mode, online mode:** the two builds of the web UI. The offline mode is a static site on GitHub Pages in which everything runs in the browser; the online mode is a server app that adds engines running on the server ([web UI, the two modes](../web-ui.md#the-two-modes)).
- **Network player:** the player that searches with Monte Carlo tree search guided by a neural network, as opposed to the classical alpha-beta player ([network](../network.md)).
- **Fingerprint:** move checksum and total nodes of a fixed benchmark game; equal fingerprints mean equal moves and equal search ([web benchmark](../web-benchmark.md#the-fingerprint)).

## Context

The project's main purpose is to show Java on the GPU with TornadoVM, a framework that compiles Java methods into GPU kernels at run time ([TornadoVM](../tornado.md)), and in the browser with TeaVM. The offline mode of the web UI must play without a server, so an engine must run in the page.

Two ports of the same engine existed:

- The Java engine (Gradle modules `engine`, `search`, `tablebase`), which TeaVM compiles after three small, JVM-neutral source changes (a replacement for `SplittableRandom`, table loading from a byte array, and an evaluator registry that no longer opens files).
- The C++ port in `cpp/` (see [C++ comparison](../cpp-comparison.md)), which Emscripten compiles after one change: loading `rows2.bin` from memory.

The page loads one engine and the 2.45 MB race table. GitHub Pages, the host of the offline mode, cannot send the HTTP headers that cross-origin isolation needs, so no engine may depend on `SharedArrayBuffer` or threads.

## Measurements

From [web benchmark](../web-benchmark.md#results-of-2026-11-18): Node.js v24.21.0 on an Apple M3 Max, 40-ply game, 20,000 nodes per move, median of 5 games, 2026-11-18. All engines and the JVM had the same fingerprint.

| Engine | nodes/s | Against the JVM | Shipped size raw (gzip) | Browser support |
|---|---|---|---|---|
| `cpp-wasm`: C++, Emscripten 6.0.9 | 1,876,760 | 1.35 | 277 KiB (105 KiB) | every browser with WebAssembly; no threads |
| `java-wasmgc`: Java, TeaVM 0.16.0 Wasm GC | 964,261 | 0.69 | 557 KiB (193 KiB) | Chrome 137, Firefox 131, Safari 18.4 according to MDN; caniuse lists no Safari version |
| `java-js`: Java, TeaVM 0.16.0 JavaScript | 263,199 | 0.19 | 313 KiB (115 KiB) | every current browser |
| JVM (reference, one game) | 1,388,889 | 1 | not shipped | |

The machine was shared with other jobs (load average 3.5 to 4.4 over one minute); a repeat run agreed within 3.6 %.

## Decision

> In the context of the offline browser build, which must run the classical player inside the page on any current browser,
> facing a C++ engine that is faster and smaller than the TeaVM output, and a project whose purpose includes showing Java in the browser,
>
> we decided for the C++ engine compiled to WebAssembly as the default, with both TeaVM builds of the Java engine selectable in the engine picker,
> and against a Java-only browser build and against a C++-only browser build,
>
> to achieve the fastest default that runs everywhere, and a visible, measured comparison of Java and C++ in the same page,
> accepting two engine code bases behind one interface and 891,416 bytes (raw) of Java engine files on the site, which a visitor downloads only when choosing a Java engine.

All engines implement the TypeScript interface `Engine` and one worker protocol ([web UI, the engine interface](../web-ui.md#the-engine-interface)). The player specification (the text that configures a player) `BROWSER_SPEC,nodes=N`, which is `BROWSER_SPEC` with a budget of N search nodes per move, is fixed inside each engine, so equal input gives equal moves. The benchmark checks this with the fingerprint on every CI run (6 plies, 2,000 nodes per move).

## Consequences

- **Speed.** At the default strength of 20,000 nodes, a move takes about 11 ms with the default engine, 21 ms with `java-wasmgc` and 76 ms with `java-js`.
- **Fallback.** The engine picker tests for Wasm GC with `WebAssembly.validate` on a small module. When the test fails, `java-wasmgc` shows as unavailable and `java-js` remains.
- **Equal strength.** Because the moves are identical, a visitor who switches engines changes speed only, never strength.
- **Two code bases.** Every change to the player must be made in Java and in C++. Fingerprint tests catch a divergence: `BrowserSpecTest` and `BestSpecFingerprintTest` in Java, and for the browser builds the Node.js tests of the C++ module and the short benchmark run of every CI build ([web UI, tests](../web-ui.md#tests)).
- **Table check.** Both engines can check the table's SHA-256 themselves. The Java engine needs its own SHA-256 code (`teavm-web/src/main/java/sternhalma/web/Sha256.java`), because TeaVM's class library has no `MessageDigest`. When the page runs in a secure context, the browser checks the table with `crypto.subtle` and the engine skips its own check.
- **No network player in the browser.** The C++ port has no Monte Carlo tree search, and the Java network player was never compiled and run with TeaVM. The network player runs only on the server, in the online mode.

## Alternatives

1. **TeaVM Wasm GC as the default.** Rejected: half the speed of the C++ engine, 1.8 times the download, and an uncertain Safari: MDN and caniuse disagree, and nobody tested a real Safari.
2. **TeaVM JavaScript as the default.** Rejected: 7.1 times slower than the C++ engine. A move at the strongest UI level (500,000 nodes) would take about 1.9 s instead of about 0.27 s (extrapolated from the medians, not measured).
3. **Only the C++ engine.** Rejected: the browser would show no Java, which is a purpose of the project.
4. **Only server engines.** Rejected: the offline mode on GitHub Pages has no server.

## When to revisit

- When a TeaVM release makes the Wasm GC build at least as fast as the C++ build, the default can move to Java.
- When the engine needs a feature in one language only (for example a network player in the browser), the second port must either follow or be dropped from the picker.

## References

- [Web UI](../web-ui.md): modes, engines, interface, protocol, build commands.
- [Web benchmark](../web-benchmark.md): method and the full result table.
- [ADR 0001](0001-browser-mode-without-rows1.md): why the browser ships only `rows2.bin`.
- [C++ comparison](../cpp-comparison.md): the C++ port and its native measurements.
