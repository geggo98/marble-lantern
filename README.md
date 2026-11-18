# Marble Lantern

**TL;DR:** A demo of Java on the GPU with [TornadoVM](https://www.tornadovm.org/) and in the browser with [TeaVM](https://teavm.org/).
The workload is a game AI; the game itself is a side effect.
It plays the two-player rules of Chinese Checkers (German: Sternhalma) on the 121-cell star board.

Live page: <https://geggo98.github.io/marble-lantern/>. Everything there runs in your browser.

## Quick start

You need [devenv](https://devenv.sh) and [Git LFS](https://git-lfs.com). The game engines ([web UI, the engines](docs/web-ui.md#the-engines)) need the race table `tablebase/tables/rows2.bin` (precomputed endgame move counts, see [web UI, terms](docs/web-ui.md#terms)). It is a Git LFS file; fetch it with `git lfs pull --include=tablebase/tables/rows2.bin`.

| Goal | Command |
|---|---|
| Build the offline site into `web/dist/` | `devenv tasks run web:build` |
| Serve it at <http://localhost:4173/marble-lantern/> | `cd web && BASE_PATH=/marble-lantern/ devenv shell -- npx vite preview` |
| Run the online mode (engines also on the server) at <http://localhost:8080/> | `devenv tasks run app:run` |
| Build and test the Java modules | `devenv shell -- ./gradlew --no-build-cache build` |

The TornadoVM SDK setup is in [architecture, getting started](docs/architecture.md#getting-started).

## Documentation

- [Web UI](docs/web-ui.md): offline and online mode, the browser engines, how to build and test them.
- [Web benchmark](docs/web-benchmark.md): the browser engines compared on identical work.
- [ADR 0002](docs/adr/0002-browser-engines.md): why C++ compiled to WebAssembly is the default browser engine.
- [Architecture](docs/architecture.md): modules, status, roadmap and TornadoVM setup.
- [Rules](docs/rules.md), [search and evaluation](docs/search-and-evaluation.md), [TornadoVM](docs/tornado.md), [offline mode](docs/offline-mode.md) and the other files in `docs/`.
