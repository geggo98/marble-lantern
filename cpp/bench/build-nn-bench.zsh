#!/bin/zsh
# Builds the network bench API (cpp/bench/nn_bench_api.cpp) as WebAssembly (needs Emscripten: run through devenv) or natively.
# usage: build-nn-bench.zsh native
#        build-nn-bench.zsh wasm <variant> [extra emcc flags]    variants used by scripts/bench-net-wasm.zsh:
#          scalar   (no SIMD instructions)        wasm scalar
#          simd     -msimd128                      fixed-width SIMD
#          relaxed  -msimd128 -mrelaxed-simd       SIMD with f32x4.relaxed_madd in the fused variant of simd_net.cpp
# Output: build/nn-bench/native/nn_bench_native, build/nn-bench/wasm-<variant>/nn_bench.{mjs,wasm}
# Flags as the project: -O3 -DNDEBUG, -ffp-contract=off everywhere except simd_net.cpp (the one named exception, -ffp-contract=fast).
# Exit: 0 = built, 1 = failure, 2 = wrong call.
set -eu
setopt pipefail

root=${0:A:h:h:h}
cd $root/cpp
mode=${1:-}
sources=(src/core/json_min.cpp src/nn/config.cpp src/nn/safetensors.cpp src/nn/parameters.cpp src/nn/gather.cpp src/nn/priors.cpp
         src/nn/reference_net.cpp src/nn/simd_net_nofma.cpp bench/nn_bench_api.cpp)
case $mode in
  native)
    out=$root/build/nn-bench/native
    cxx=(/usr/bin/c++ -mcpu=native)
    link=(/usr/bin/c++ -O3 OBJS -o $out/nn_bench_native)
    ;;
  wasm)
    variant=${2:?variant}
    shift 2
    out=$root/build/nn-bench/wasm-$variant
    command -v emcc >/dev/null || { print -u2 "build-nn-bench: emcc not found; run it through devenv"; exit 1; }
    [[ -n ${EM_CACHE:-} ]] || export EM_CACHE=$root/.devenv/state/em_cache
    mkdir -p $EM_CACHE
    cxx=(em++ $@)
    exports=_malloc,_free,_nb_error,_nb_load,_nb_planes,_nb_players,_nb_lanes,_nb_forward
    link=(em++ -O3 $@ OBJS -o $out/nn_bench.mjs --no-entry -sMODULARIZE=1 -sEXPORT_ES6=1 -sEXPORT_NAME=createNb
          -sENVIRONMENT=web,worker,node -sALLOW_MEMORY_GROWTH=1 -sINITIAL_MEMORY=134217728 -sFILESYSTEM=0
          -sEXPORTED_RUNTIME_METHODS=HEAPU8,UTF8ToString -sEXPORTED_FUNCTIONS=$exports -Wno-version-check)
    ;;
  *) print -u2 "usage: build-nn-bench.zsh native | wasm <scalar|simd|relaxed|name> [emcc flags]"; exit 2 ;;
esac
rm -rf $out
mkdir -p $out
common=(-std=c++23 -O3 -DNDEBUG -Isrc/core -Isrc/nn)
for f in $sources; do
  $cxx $common -ffp-contract=off -c $f -o $out/$(basename $f .cpp).o
done
$cxx $common -ffp-contract=fast -c src/nn/simd_net.cpp -o $out/simd_net.o
objs=($out/*.o)
cmd=()
for a in $link; do
  if [[ $a == OBJS ]]; then cmd+=($objs); else cmd+=($a); fi
done
$cmd

print -u2 "build-nn-bench: built $out"
