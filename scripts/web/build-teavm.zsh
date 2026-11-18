#!/bin/zsh
# Builds the Java engine with TeaVM (module teavm-web) and copies the three files to web/public/engines/java/:
#   engine-java.wasm, engine-java.wasm-runtime.js (Wasm GC) and engine-java.js (JavaScript).
# Run through `devenv tasks run teavm:build`.
#
# usage: build-teavm.zsh
# Exit: 0 = built and copied (also when nothing changed), 1 = failure, 2 = wrong call.
#
# Re-running is safe: Gradle rebuilds only what changed (about a minute from scratch), and each file is published under
# a temporary name and renamed, so a name that exists is complete. Reruns leave leftovers of an aborted run as
# *.tmp.* files; they are removed at the start.
set -eu
setopt pipefail

[[ $# -eq 0 ]] || { print -u2 "usage: build-teavm.zsh"; exit 2; }
here=${0:A:h}
root=${here:h:h}
src=$root/teavm-web/build/generated/teavm
out=$root/web/public/engines/java

# The wrapper pins Gradle 9.8.2; --no-build-cache is the project rule for builds that change.
cd $root
print -u2 "build-teavm: Gradle generateWasmGC, copyWasmGCRuntime, generateJavaScript"
./gradlew --no-build-cache :teavm-web:generateWasmGC :teavm-web:copyWasmGCRuntime :teavm-web:generateJavaScript >&2

# source file -> published name; every file must exist and be non-empty before anything is published
typeset -A files=(
  wasm-gc/engine-java.wasm            engine-java.wasm
  wasm-gc/engine-java.wasm-runtime.js engine-java.wasm-runtime.js
  js/engine-java.js                   engine-java.js
)
for f in ${(k)files}; do
  [[ -s $src/$f ]] || { print -u2 "build-teavm: $src/$f is missing or empty"; exit 1; }
done

mkdir -p $out
rm -f $out/*.tmp.*(N)
for f in ${(k)files}; do
  tmp=$out/${files[$f]}.tmp.$$
  cp $src/$f $tmp
  cmp -s $src/$f $tmp || { rm -f $tmp; print -u2 "build-teavm: copy of $f differs from the source"; exit 1; }
  mv -f $tmp $out/${files[$f]}
done
for f in ${(v)files}; do
  print -u2 "build-teavm: wrote $out/$f ($(wc -c < $out/$f | tr -d ' ') bytes)"
done
