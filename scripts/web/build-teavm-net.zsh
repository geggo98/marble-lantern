#!/bin/zsh
# Builds the net-MCTS engine without its forward pass with TeaVM (module teavm-net) and copies the three files to
# web/public/engines/net/: engine-net.wasm, engine-net.wasm-runtime.js (Wasm GC) and engine-net.js (JavaScript).
# Run through `devenv tasks run teavm:build-net`; web:engines runs after it.
#
# usage: build-teavm-net.zsh
# Exit: 0 = built and copied (also when nothing changed), 1 = failure, 2 = wrong call.
#
# Same publishing rules as build-teavm.zsh: each file is written under a temporary name and renamed, leftovers of an
# aborted run (*.tmp.*) are removed at the start.
set -eu
setopt pipefail

[[ $# -eq 0 ]] || { print -u2 "usage: build-teavm-net.zsh"; exit 2; }
here=${0:A:h}
root=${here:h:h}
src=$root/teavm-net/build/generated/teavm
out=$root/web/public/engines/net

cd $root
print -u2 "build-teavm-net: Gradle generateWasmGC, copyWasmGCRuntime, generateJavaScript"
./gradlew --no-build-cache :teavm-net:generateWasmGC :teavm-net:copyWasmGCRuntime :teavm-net:generateJavaScript >&2

typeset -A files=(
  wasm-gc/engine-net.wasm            engine-net.wasm
  wasm-gc/engine-net.wasm-runtime.js engine-net.wasm-runtime.js
  js/engine-net.js                   engine-net.js
)
for f in ${(k)files}; do
  [[ -s $src/$f ]] || { print -u2 "build-teavm-net: $src/$f is missing or empty"; exit 1; }
done

mkdir -p $out
rm -f $out/*.tmp.*(N)
for f in ${(k)files}; do
  tmp=$out/${files[$f]}.tmp.$$
  cp $src/$f $tmp
  cmp -s $src/$f $tmp || { rm -f $tmp; print -u2 "build-teavm-net: copy of $f differs from the source"; exit 1; }
  mv -f $tmp $out/${files[$f]}
done
for f in ${(v)files}; do
  print -u2 "build-teavm-net: wrote $out/$f ($(wc -c < $out/$f | tr -d ' ') bytes, gzip $(gzip -9 -c $out/$f | wc -c | tr -d ' '))"
done
