#!/bin/zsh
# Writes the golden files for the tests of the WebAssembly net backend (web/src/engines/net/wasm-backend.test.ts) into one directory:
#   :nn:goldenExport      nn-weights-small-u5 (classic1), nn-weights-small-v3 / nn-weights-tiny-v3 (classic1v), their inputs and references
#   RealRef.java          real-ref-v2 / real-ref-v3: Java reference of the real nets (needs the nets under $STERNHALMA_DATA_DIR)
#   PlainRef.java         nn-weights-small-plain / nn-ref-small-plain (plain plane set, no priors)
# usage: net-wasm-golden.zsh <golden-dir>        (the test reads the directory from STERNHALMA_GOLDEN_DIR, default cpp/golden)
# Env:   STERNHALMA_DATA_DIR  data directory with loop3/gen-0013 and shared/gen0-shared/gen-0000 (default <repo>/data); without it the
#        real-net references are skipped and the vitest tests of the real nets skip too.
# Exit: 0 = written, 2 = wrong call, else the failing step.
set -eu
setopt pipefail

root=${0:A:h:h:h}
cd $root
[[ $# -eq 1 ]] || { print -u2 "usage: net-wasm-golden.zsh <golden-dir>"; exit 2; }
golden=${1:A}
data=${STERNHALMA_DATA_DIR:-$root/data}
mkdir -p $golden

./gradlew --no-build-cache -q :nn:goldenExport -Psternhalma.golden.out=$golden >&2
cp=nn/build/classes/java/main:nn/build/classes/java/test:engine/build/classes/java/main
classes=$(mktemp -d "${TMPDIR:-/tmp}/netwasm-golden.XXXXXX")
javac -nowarn --add-modules jdk.incubator.vector -cp $cp -d $classes web/bench/net-wasm/PlainRef.java web/bench/webgpu/RealRef.java
java --add-modules jdk.incubator.vector -cp $classes:$cp sternhalma.nn.PlainRef $golden
v2=$data/loop3/gen-0013/candidate.safetensors
v3=$data/shared/gen0-shared/gen-0000/candidate.safetensors
if [[ -f $v2 && -f $v3 ]]; then
  java --add-modules jdk.incubator.vector -cp $classes:$cp sternhalma.nn.RealRef $golden $v2 $v3
else
  print -u2 "net-wasm-golden: $v2 or $v3 missing, real-net references skipped"
fi
rm -rf $classes
print -u2 "net-wasm-golden: wrote $golden"
