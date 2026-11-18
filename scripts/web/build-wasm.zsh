#!/bin/zsh
# Builds the C++ engine as WebAssembly (cpp/, preset "wasm") and copies engine-cpp.js and engine-cpp.wasm to
# web/public/engines/cpp/. Run through `devenv tasks run wasm:build` (emcmake and EM_CACHE come from devenv).
#
# usage: build-wasm.zsh [--classic]   --classic: emit a classic script instead of an ES module (SH_WASM_ES6=OFF)
# Exit: 0 = built and copied (also when the output was already current), 1 = failure, 2 = wrong call.
#
# Re-running is safe: CMake and Ninja rebuild only what changed, and the files are published under a temporary name and
# renamed, so a name that exists is complete. A build takes about a minute from scratch (the first link also builds the
# Emscripten system libraries into EM_CACHE, about 30 s more).
set -eu
setopt pipefail

here=${0:A:h}
root=${here:h:h}
out=$root/web/public/engines/cpp
es6=ON
case "${1:-}" in
  "") ;;
  --classic) es6=OFF ;;
  *) print -u2 "usage: build-wasm.zsh [--classic]"; exit 2 ;;
esac

for tool in emcmake cmake ninja; do
  command -v $tool >/dev/null || { print -u2 "build-wasm: $tool not found; run it through devenv (devenv tasks run wasm:build)"; exit 1; }
done
# The Nix store is read-only; Emscripten needs a writable cache (devenv sets EM_CACHE).
if [[ -z "${EM_CACHE:-}" ]]; then
  export EM_CACHE=$root/.devenv/state/em_cache
  print -u2 "build-wasm: EM_CACHE was not set, using $EM_CACHE"
fi
mkdir -p $EM_CACHE

cd $root/cpp
emcmake cmake --preset wasm -DSH_WASM_ES6=$es6 >&2
cmake --build --preset wasm >&2

build=$root/cpp/build/wasm
for f in engine-cpp.js engine-cpp.wasm; do
  [[ -s $build/$f ]] || { print -u2 "build-wasm: $build/$f is missing or empty"; exit 1; }
done

mkdir -p $out
for f in engine-cpp.js engine-cpp.wasm; do
  tmp=$out/$f.tmp.$$
  cp $build/$f $tmp
  mv -f $tmp $out/$f
done
print -u2 "build-wasm: wrote $out/engine-cpp.js ($(wc -c < $out/engine-cpp.js | tr -d ' ') bytes) and engine-cpp.wasm ($(wc -c < $out/engine-cpp.wasm | tr -d ' ') bytes), ES module: $es6"
