#!/bin/zsh
# usage: ptx-check.sh [--self-test]
# Best-effort syntax and type check of the CUDA code (src/gpu/cuda/cuda_net.cu and kernels.cuh) on a machine without nvcc, so
# that a typing mistake does not first show up on the paid instance (docs/cpp-comparison-plan.md, WP12).
# How: clang compiles the .cu file in CUDA mode (-x cuda) without a toolkit (-nocudainc -nocudalib) against a small stand-in
# header, scripts/ptx-shim/cuda_runtime.h, once for the host side and once for the device side of sm_75 (-fsyntax-only).
# Apple clang has CUDA mode but no NVPTX back end ("No available targets are compatible with triple nvptx64-nvidia-cuda"), so it
# cannot print PTX; if a clang with the target is on the PATH (CLANG=...), the script also emits PTX and counts the kernels.
# What this proves: the code parses, names and types are consistent, no host function is called from a kernel and no device
# function from the host side. What it does not prove: that the real CUDA headers declare the API as the shim does, that nvcc
# accepts the file, anything about run time. --self-test plants a kernel that calls a host function and must see it fail.
setopt err_exit pipe_fail no_unset
here=${0:A:h}
cpp=${here:h}
clang=${CLANG:-/usr/bin/clang++}
flags=(-x cuda -std=c++20 -nocudainc -nocudalib --cuda-gpu-arch=sm_75 -Wall -Wextra -Werror -Wno-unused-command-line-argument
       -isystem $here/ptx-shim -I$cpp/src/gpu/cuda)

run() {   # $1 = side (--cuda-host-only or --cuda-device-only), $2 = source
  $clang $flags $1 -fsyntax-only $2
}

if [[ ${1:-} == --self-test ]]; then
  tmp=$(mktemp -d); trap 'rm -rf $tmp' EXIT
  print -r -- '#include <cuda_runtime.h>
void host_only();
__global__ void bad() { host_only(); }' > $tmp/bad.cu
  if run --cuda-device-only $tmp/bad.cu 2> $tmp/err; then print "self-test FAIL: a kernel that calls a host function was accepted"; exit 1; fi
  grep -q "call to __host__ function" $tmp/err || { print "self-test FAIL: wrong error"; cat $tmp/err; exit 1 }
  print -r -- '#include <cuda_runtime.h>
__global__ void k(float* p) { p[0] = 1.0f; }
void f(float* p) { k<<<dim3(1), dim3(1)>>>(p, 3); }' > $tmp/bad2.cu
  if run --cuda-host-only $tmp/bad2.cu 2> $tmp/err2; then print "self-test FAIL: a launch with a wrong argument list was accepted"; exit 1; fi
  print "self-test ok: both planted errors were found"
  exit 0
fi

command -v $clang >/dev/null || { print -u2 "ptx-check: no clang at $clang"; exit 77 }
src=$cpp/src/gpu/cuda/cuda_net.cu
rc=0
for side in --cuda-host-only --cuda-device-only; do
  if run $side $src; then print "ok:   $src ${side#--cuda-}"; else print -u2 "FAIL: $src ${side#--cuda-}"; rc=1; fi
done
if $clang -print-targets 2>/dev/null | grep -q nvptx; then
  $clang $flags --cuda-device-only -S $src -o - | perl -ne '$n++ if /^\.visible \.entry/; END { print "PTX: $n kernels\n" }'
else
  print "note: this clang has no NVPTX target; syntax and type check only, no PTX"
fi
exit $rc
