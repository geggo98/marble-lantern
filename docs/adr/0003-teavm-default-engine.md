# ADR 0003: Java compiled by TeaVM to Wasm GC is the default browser engine; C++ is the fallback

- **Status:** accepted; supersedes the default-engine decision of [ADR 0002](0002-browser-engines.md)
- **Date:** 2026-11-18
- **Decided by:** the project owner
- **Code:** `web/src/engines/registry.ts` (engine order), `web/src/app/session.ts` (choice of the first engine); details in [web UI](../web-ui.md)

**TL;DR:** The offline browser build starts with `java-wasmgc`, the Java engine compiled by TeaVM to WebAssembly with garbage collection, because the project is first of all a Java demo. A browser without Wasm GC gets the C++ engine `cpp-wasm`, and only if that is missing too the Java engine compiled to JavaScript, `java-js`. The cost is a default that is about half as fast as C++ and a download of 193 KiB instead of 105 KiB (gzip); at the default strength a move still takes about 21 ms. The rest of [ADR 0002](0002-browser-engines.md) stays valid: three engines, identical moves, the fallback logic, and the guard of [ADR 0001](0001-browser-mode-without-rows1.md) that keeps `rows1.bin` out of the browser.

## Terms

- **Browser engine:** code that runs `BROWSER_SPEC`, the classical alpha-beta player of the browser, inside the page in a Web Worker ([web UI, terms](../web-ui.md#terms)).
- **TeaVM:** an ahead-of-time compiler from JVM bytecode to JavaScript and WebAssembly (<https://teavm.org/>).
- **TornadoVM:** a framework that compiles Java methods into GPU kernels at run time ([TornadoVM](../tornado.md)).
- **Wasm GC:** WebAssembly with garbage collection. TeaVM 0.16.0 has no other WebAssembly backend.
- **Engine ids:** `java-wasmgc` (Java, TeaVM, Wasm GC target), `cpp-wasm` (the C++ port, compiled with Emscripten, the compiler from C and C++ to WebAssembly), `java-js` (Java, TeaVM, JavaScript target).
- **Engine picker:** the engine selection sheet of the play screen (`web/src/ui/EngineSheet.tsx`, see [web UI, the engines](../web-ui.md#the-engines)).
- **Nodes:** positions the search visits; the strength setting of the UI is a node budget per move.

## Context

The project is first of all a Java demo: Java on GPUs with TornadoVM, and Java in the browser with TeaVM. The C++ port in `cpp/` exists as a performance comparison ([C++ comparison](../cpp-comparison.md)). A visitor to the page should therefore meet the Java engine first.

[ADR 0002](0002-browser-engines.md) made `cpp-wasm` the default, because it is the fastest and smallest engine and runs in every browser with WebAssembly. With that default, a visitor saw Java only after opening the engine picker.

## Decision

> In the context of the offline browser build of a project whose purpose is to show Java in the browser,
> facing a Java engine that is about half as fast as the C++ engine and needs Wasm GC,
>
> we decided for `java-wasmgc` as the default browser engine, with `cpp-wasm` and then `java-js` as the fallback chain,
> and against keeping `cpp-wasm` as the default and against `java-js` as the default,
>
> to achieve that a visitor meets the Java engine first,
> accepting a slower default and a larger first download.

The registry order in `web/src/engines/registry.ts` is `java-wasmgc`, `cpp-wasm`, `java-js`. The session starts with the first engine in that order that the browser can run and the build shipped.

### Fallback chain

`web/src/engines/registry.ts` detects Wasm GC with `WebAssembly.validate` on a 13-byte probe module that declares one struct type. If the check fails, `java-wasmgc` is unavailable and the session takes `cpp-wasm`; `java-js` comes last.

`cpp-wasm` comes before `java-js` because it is faster. The numbers are from [ADR 0002](0002-browser-engines.md#measurements) and the [web benchmark](../web-benchmark.md): `cpp-wasm` searches 1.88 million nodes per second in Node.js, 1.95 times the speed of the Wasm GC build, and `java-js` is 7.1 times slower than `cpp-wasm`.

A unit test in `web/src/app/session.test.ts` simulates a browser without Wasm GC and checks that the session chooses `cpp-wasm`, not `java-js`.

## Consequences

Costs, measured and stated in [ADR 0002](0002-browser-engines.md#measurements):

- **Speed.** The Wasm GC build is about half as fast as the C++ build. At 20,000 nodes per move, the default strength, a move takes about 21 ms against about 11 ms.
- **Download.** The Wasm GC engine is 197,505 bytes gzip (193 KiB), the C++ engine 107,823 bytes (105 KiB).
- **Safari.** ADR 0002 noted that nobody had tested Wasm GC in a real Safari. The fallback chain covers this risk: if the probe fails, Safari gets `cpp-wasm`.

Mitigations:

- The fallback chain gives every browser without Wasm GC the fastest engine it can run.
- All three engines stay selectable in the engine picker. The "default" badge in the picker marks the first available engine, so it follows the fallback chain.
- At the default strength a move stays under about 25 ms, so the player does not notice the difference to C++.

## Alternatives

1. **Keep `cpp-wasm` as the default.** Rejected by the owner: the page is a Java demo.
2. **`java-js` as the default.** Rejected: 7.1 times slower than `cpp-wasm`, and Wasm GC is the more interesting Java target.

## When to revisit

If Wasm GC support in the browsers the project cares about proves unreliable, for example through reports from users or a test in a real Safari, revisit the default.

## References

- [ADR 0002](0002-browser-engines.md): the three engines, their measurements and the fallback logic.
- [Web benchmark](../web-benchmark.md): method and the full result table.
- [ADR 0001](0001-browser-mode-without-rows1.md): why the browser ships only `rows2.bin`.
- [Web UI](../web-ui.md): engines, interface and build commands.
