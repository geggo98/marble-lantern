#!/bin/zsh
# WebGPU benchmark of the real SMALL net (web/bench/webgpu): correctness against the Java reference and timings.
#
#   scripts/bench-net-webgpu.zsh [--config gpu|swiftshader|gpu-headed] [--runs N] [--out DIR] [--no-bench] [--worker]
#                                [--cpt 1|2] [--nets real-v2,real-v3,u5-v2,u5-v3] [--batches 1,4,...] [extra run.mts options]
#
# Inputs (nothing is copied into git):
#   STERNHALMA_DATA_DIR  data directory with loop3/gen-0013 and shared/gen0-shared/gen-0000 (default <repo>/data)
#   cpp/golden           written by `./gradlew --no-build-cache :search:goldenExport :nn:goldenExport` (this script runs it
#                        when nn-inputs.safetensors is missing), plus real-ref-v2/-v3 from web/bench/webgpu/RealRef.java
#                        (compiled and run here when missing)
# Output: one JSON per run in --out (default $TMPDIR/bench-net-webgpu) and the tables of web/bench/webgpu/summarize.mts on stdout.
# Before timing it prints the load average; run when it is well below the core count (the page also records a CPU probe).
# Exit: 0 = ran, 2 = wrong call or input missing, anything else = the failing step's code.
set -eu
setopt pipefail

root=${0:A:h:h}
config=gpu runs=1 out=${TMPDIR:-/tmp}/bench-net-webgpu
extra=()
while (( $# )); do
  case $1 in
    --config) config=$2; shift 2 ;;
    --runs) runs=$2; shift 2 ;;
    --out) out=$2; shift 2 ;;
    *) extra+=($1); shift ;;
  esac
done
data=${STERNHALMA_DATA_DIR:-$root/data}
v2=$data/loop3/gen-0013/candidate.safetensors
v3=$data/shared/gen0-shared/gen-0000/candidate.safetensors
for f in $v2 $v3; do [[ -f $f ]] || { print -u2 "bench-net-webgpu: missing $f (set STERNHALMA_DATA_DIR)"; exit 2 }; done
[[ -d $root/web/node_modules ]] || { print -u2 "bench-net-webgpu: web/node_modules is missing; run 'devenv tasks run web:install' first"; exit 2 }

cd $root
golden=$root/cpp/golden
if [[ ! -f $golden/nn-inputs.safetensors ]]; then
  print -u2 "bench-net-webgpu: writing the golden files"
  +errlog -- ./gradlew --no-build-cache :search:goldenExport :nn:goldenExport
fi
if [[ ! -f $golden/real-ref-v2.safetensors || ! -f $golden/real-ref-v3.safetensors ]]; then
  print -u2 "bench-net-webgpu: Java reference outputs of the real nets"
  cp=nn/build/classes/java/main:nn/build/classes/java/test:engine/build/classes/java/main
  classes=$(mktemp -d "${TMPDIR:-/tmp}/realref.XXXXXX")
  javac -q --add-modules jdk.incubator.vector -cp $cp -d $classes web/bench/webgpu/RealRef.java
  java --add-modules jdk.incubator.vector -cp $classes:$cp sternhalma.nn.RealRef $golden $v2 $v3
fi

mkdir -p $out
print -u2 "bench-net-webgpu: cores $(sysctl -n hw.ncpu), load average: $(sysctl -n vm.loadavg)"
for (( i = 1; i <= runs; i++ )); do
  file=$out/run-$config-$i.json
  print -u2 "bench-net-webgpu: run $i of $runs -> $file"
  node web/bench/webgpu/run.mts --config $config --golden $golden --net-v2 $v2 --net-v3 $v3 --out $file $extra
done
node web/bench/webgpu/summarize.mts $out/run-$config-*.json
