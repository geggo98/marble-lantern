#!/bin/zsh
# Node tests of the C++ WebAssembly engine (cpp/wasm/test/): the benchmark game fingerprint, the error codes of damaged tables, the analysis, the long hop, the three-player game, the full-ranking game, the foreign corners and the house size.
# Run through `devenv tasks run wasm:test` (after wasm:build and web:tables).
#
# usage: test-wasm.zsh [--engine DIR] [--tables DIR]
#   --engine DIR  directory with engine-cpp.js and engine-cpp.wasm   (default: web/public/engines/cpp)
#   --tables DIR  directory with rows2.bin.gz and rows2.manifest.json (default: web/public/tables; if that has no table,
#                 the script packs tablebase/tables/rows2.bin into cpp/build/wasm-tables with scripts/offline/pack-tables.py)
# Exit: 0 = all tests passed, 1 = a test failed or an input is missing, 2 = wrong call.
set -eu
setopt pipefail

here=${0:A:h}
root=${here:h:h}
engine=$root/web/public/engines/cpp
tables=$root/web/public/tables
while (( $# )); do
  case $1 in
    --engine) engine=${2:?--engine needs a directory}; shift 2 ;;
    --tables) tables=${2:?--tables needs a directory}; shift 2 ;;
    *) print -u2 "usage: test-wasm.zsh [--engine DIR] [--tables DIR]"; exit 2 ;;
  esac
done

for f in engine-cpp.js engine-cpp.wasm; do
  [[ -s $engine/$f ]] || { print -u2 "test-wasm: $engine/$f is missing; run devenv tasks run wasm:build"; exit 1; }
done

if [[ ! -s $tables/rows2.bin.gz || ! -s $tables/rows2.manifest.json ]]; then
  src=
  for c in ${STERNHALMA_TABLE_DIR:+$STERNHALMA_TABLE_DIR/rows2.bin} $root/tablebase/tables/rows2.bin; do
    [[ -s $c ]] && { src=$c; break }
  done
  [[ -n $src ]] || { print -u2 "test-wasm: no table package in $tables and no rows2.bin to pack (run git lfs pull)"; exit 1 }
  tables=$root/cpp/build/wasm-tables
  print -u2 "test-wasm: no table package in web/public/tables; packing $src into $tables"
  mkdir -p $tables
  $root/scripts/offline/pack-tables.py $src --out $tables >&2
fi

dir=$root/cpp/wasm/test
print -u2 "test-wasm: engine $engine, tables $tables"
print -u2 -- "--- benchmark game"
node $dir/game.mjs --engine $engine --tables $tables
print -u2 -- "--- damaged tables"
node $dir/probe.mjs --engine $engine --tables $tables
print -u2 -- "--- analysis"
node $dir/analyse.mjs --engine $engine --tables $tables
print -u2 -- "--- long hop"
node $dir/longhop.mjs --engine $engine --tables $tables
print -u2 -- "--- three players"
node $dir/threeplayer.mjs --engine $engine --tables $tables
print -u2 -- "--- full ranking"
node $dir/ranking.mjs --engine $engine --tables $tables
print -u2 -- "--- foreign corners"
node $dir/camps.mjs --engine $engine --tables $tables
print -u2 -- "--- house size"
node $dir/rows5.mjs --engine $engine --tables $tables
print -u2 "test-wasm: all tests passed"
