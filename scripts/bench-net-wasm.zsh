#!/bin/zsh
# Network forward pass (SMALL, policy + value) in the browser: C++ WebAssembly and Java through TeaVM against the JVM and native C++.
# Builds everything, runs every path, prints one table (positions/s and ms per batch at batch 1, 8, 32 and 128, with spread,
# self-ratio and the error against the Java reference), then the F16 study.
#
# usage: scripts/bench-net-wasm.zsh [--net FILE] [--out DIR] [--only native,jvm,node,chromium,firefox] [--skip-build]
#                                   [--skip-f16] [--max-load N] [--quick]
#   --net       the weights (default $STERNHALMA_DATA_DIR/loop3/gen-0013/candidate.safetensors, data dir default: data/)
#   --only      which runners (default all). node = Node.js, chromium and firefox = headless Playwright browsers.
#   --max-load  wait (up to 30 min) until the 1-minute load average is at most N before each runner; a WARNING stays if it never is
#   --quick     smoke test: short schedule (0.1 s rounds), numbers are not results
# Output: DIR/<runner>.jsonl, DIR/table.txt, DIR/f16-<net>.txt (default DIR build/nn-bench/results-<time>).
# Needs devenv (Emscripten, Node, JDK); the script re-runs itself inside `devenv shell` when emcc is not on PATH.
# Exit: 0 = done, 1 = failure, 2 = wrong call, 130/143 = stopped.
set -eu
setopt pipefail

root=${0:A:h:h}
cd $root
if ! command -v emcc >/dev/null; then
  exec devenv shell -- $0 "$@"
fi

net=${STERNHALMA_DATA_DIR:-$root/data}/loop3/gen-0013/candidate.safetensors
out=$root/build/nn-bench/results-$(date +%Y%m%dT%H%M%S)
only=native,jvm,node,chromium,firefox
build=1 f16=1 maxload=0 quick=
while (( $# )); do
  case $1 in
    --net) net=$2; shift 2 ;;
    --out) out=$2; shift 2 ;;
    --only) only=$2; shift 2 ;;
    --skip-build) build=0; shift ;;
    --skip-f16) f16=0; shift ;;
    --max-load) maxload=$2; shift 2 ;;
    --quick) quick=1; shift ;;
    *) print -u2 "usage: bench-net-wasm.zsh [--net FILE] [--out DIR] [--only a,b] [--skip-build] [--skip-f16] [--max-load N] [--quick]"; exit 2 ;;
  esac
done
[[ -s $net ]] || { print -u2 "bench-net-wasm: net $net not found (set STERNHALMA_DATA_DIR or pass --net)"; exit 1; }
mkdir -p $out
data=$root/build/netbench
mkdir -p $data
ref=$data/ref-$(basename ${net:h}).safetensors
[[ -n $quick ]] && export NB_QUICK=1
stop=
trap 'stop=1' INT TERM

load1() { perl -e 'my ($l) = split " ", `sysctl -n vm.loadavg | tr -d "{}"`; print $l'; }
wait_for_load() {
  (( maxload > 0 )) || return 0
  local waited=0
  while (( $(load1) > maxload )) && (( waited < 1800 )); do
    print -u2 "bench-net-wasm: load $(load1) > $maxload, waiting (${waited}s)"
    sleep 30; waited=$((waited + 30))
  done
  (( $(load1) <= maxload )) || print -u2 "bench-net-wasm: WARNING load $(load1) > $maxload after 30 minutes; the numbers are indicative only"
}

print -u2 "bench-net-wasm: machine $(sysctl -n machdep.cpu.brand_string), $(sysctl -n hw.ncpu) cores, load $(load1), net $net"
if [[ $build == 1 ]]; then
  print -u2 "bench-net-wasm: reference file (Java scalar Network on the golden positions)"
  ./gradlew --no-build-cache -q :nn:wasmBenchExport --args="$net $ref 64 1" >&2
  print -u2 "bench-net-wasm: TeaVM (Wasm GC and JS)"
  ./gradlew --no-build-cache -q :teavm-nn-bench:generateWasmGC :teavm-nn-bench:copyWasmGCRuntime :teavm-nn-bench:generateJavaScript >&2
  print -u2 "bench-net-wasm: C++ native and WebAssembly (scalar, simd128, simd128 + relaxed-simd)"
  cpp/bench/build-nn-bench.zsh native
  cpp/bench/build-nn-bench.zsh wasm scalar
  cpp/bench/build-nn-bench.zsh wasm simd -msimd128
  cpp/bench/build-nn-bench.zsh wasm relaxed -msimd128 -mrelaxed-simd
fi
[[ -s $ref ]] || { print -u2 "bench-net-wasm: $ref missing (run without --skip-build)"; exit 1; }

for runner in ${(s:,:)only}; do
  [[ -z $stop ]] || { print -u2 "bench-net-wasm: stopped"; exit 130; }
  wait_for_load
  print -u2 "bench-net-wasm: $runner (load $(load1))"
  case $runner in
    native) build/nn-bench/native/nn_bench_native $net $ref > $out/native.jsonl ;;
    jvm) ./gradlew --no-build-cache -q :teavm-nn-bench:jvmBench --args="$net $ref" 2>/dev/null > $out/jvm.jsonl ;;
    node) node cpp/bench/bench-node.mjs --root $root --net $net --ref $ref > $out/node.jsonl ;;
    chromium|firefox) node cpp/bench/bench-browser.mjs --browser $runner --root $root --net $net --ref $ref > $out/$runner.jsonl ;;
    *) print -u2 "bench-net-wasm: unknown runner $runner"; exit 2 ;;
  esac
done

print -u2 "bench-net-wasm: load after the runs $(load1)"
node cpp/bench/bench-table.mjs $out/*.jsonl | tee $out/table.txt
if [[ $f16 == 1 ]]; then
  print "\nFloat16 storage (F32 -> F16 -> F32 at load), 2000 mid-game positions of seeded two-player playouts:"
  ./gradlew --no-build-cache -q :nn:halfPrecisionStudy --args="$net 2000 11" 2>/dev/null | tee $out/f16-$(basename ${net:h}).txt
fi
print -u2 "bench-net-wasm: results in $out"
