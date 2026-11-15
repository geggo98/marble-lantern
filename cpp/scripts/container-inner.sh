#!/bin/bash
# Runs INSIDE the ubuntu:22.04 container of build-ubuntu-container.sh (as root). /src is the worktree (read-only), /golden the golden
# files (read-only). Prints one "RESULT <name>: ok|FAIL|SKIP ..." line per check; the exit code is 1 if any check failed.
# shellcheck disable=SC2015,SC1091  # "A && result ok || result FAIL" is the intended pass-or-fail
set -u
export DEBIAN_FRONTEND=noninteractive
FAILS=0
result() { echo "RESULT $1: $2"; case $2 in FAIL*) FAILS=$((FAILS + 1)) ;; esac; }

apt-get update -qq > /tmp/apt.log 2>&1 && apt-get install -y -qq g++-12 cmake ninja-build zsh binutils perl ca-certificates >> /tmp/apt.log 2>&1 \
  || { tail -5 /tmp/apt.log; echo "RESULT apt: FAIL"; exit 1; }
echo "== machine: $(uname -m), $(. /etc/os-release && echo "$PRETTY_NAME")"
echo "== compiler: $(g++-12 --version | head -1)"
echo "== cmake: $(cmake --version | head -1), ninja: $(ninja --version)"
g++-12 -dumpfullversion | grep -q '^12\.3\.' && result compiler-version "ok (12.3)" || result compiler-version "FAIL (not 12.3: $(g++-12 -dumpfullversion))"

# the build is in the container: a copy of cpp/ without the Mac build directories and the golden files
mkdir -p /work && tar -C /src --exclude=./cpp/build --exclude=./cpp/golden -cf - ./cpp | tar -C /work -xf -
cd /work/cpp || exit 1
mkdir -p golden && cp -a /golden/. golden/ 2> /dev/null     # the compiled-in default path; the tests also get STERNHALMA_GOLDEN_DIR
export STERNHALMA_GOLDEN_DIR=/golden STERNHALMA_GOLDEN_REQUIRED=1

# the 16-lane objects on an x86-64 CPU without AVX-512 (Rosetta) would stop on a note about the vector ABI (-Wpsabi)
[ "$(uname -m)" = x86_64 ] && export SH_EXTRA_CXXFLAGS=-Wno-psabi
echo "== build-cuda.sh --stub --werror --with-tests (g++-12, -D_GLIBCXX_ASSERTIONS in the hardened build, -Werror)"
if zsh scripts/build-cuda.sh --stub --werror --with-tests --cxx g++-12 --out /build/out; then result build "ok (hardened and unchecked, lanes 8 and 16)"; else result build "FAIL (exit $?)"; fi
echo "== flags of the build:"; cat /build/out/toolchain/flags.txt 2> /dev/null

bin=/build/out/hardened/bin
for t in java_compat_test nn_test cuda_adapter_test cuda_test_emu; do
  if [ -x "$bin/$t" ]; then
    if "$bin/$t" > "/build/out/logs/run-$t.log" 2>&1; then result "test $t" "ok ($(tail -1 "/build/out/logs/run-$t.log" | cut -c1-100))"
    else result "test $t" "FAIL"; tail -8 "/build/out/logs/run-$t.log"; fi
  else result "test $t" "FAIL (not built)"; fi
done
if "$bin/cuda_test" --allow-no-device > /build/out/logs/run-cuda_test.log 2>&1; then result "test cuda_test (stub, no device)" "ok ($(head -1 /build/out/logs/run-cuda_test.log | cut -c1-100))"
else result "test cuda_test (stub, no device)" "FAIL"; fi
"$bin/nn_check_l8" --impl cuda > /dev/null 2>&1; rc=$?
[ "$rc" = 64 ] && result "nn_check --impl cuda (stub build)" "ok (exit 64: not in this build)" || result "nn_check --impl cuda (stub build)" "FAIL (exit $rc)"
for impl in reference simd simd-nofma; do
  for preset in small large; do
    if "$bin/nn_check_l8" --impl "$impl" --preset "$preset" > "/build/out/logs/run-nn_check-$impl-$preset.log" 2>&1; then
      result "nn_check --impl $impl --preset $preset" "ok ($(tail -1 "/build/out/logs/run-nn_check-$impl-$preset.log" | cut -c1-130))"
    else result "nn_check --impl $impl --preset $preset" "FAIL"; tail -5 "/build/out/logs/run-nn_check-$impl-$preset.log"; fi
  done
done
# the unchecked binaries run too (they are the "cost of the checks" rows)
if /build/out/unchecked/bin/nn_check_l8 --impl simd --preset small > /build/out/logs/run-unchecked.log 2>&1; then result "nn_check unchecked build, simd small" "ok"; else result "nn_check unchecked build, simd small" "FAIL"; fi
# the 16-lane binaries: on a CPU without 512-bit vectors they still run (vector extensions are lowered to two registers)
if /build/out/hardened/bin/nn_check_l16 --impl simd --preset small > /build/out/logs/run-l16.log 2>&1; then result "nn_check_l16 simd small" "ok"; else result "nn_check_l16 simd small" "FAIL"; fi
if /build/out/hardened/bin/nn_bench_l8 --help > /dev/null 2>&1; [ $? -le 64 ]; then result "nn_bench_l8 starts" "ok"; else result "nn_bench_l8 starts" "FAIL"; fi

# the proof of the fused multiply-add variants (built into build-cuda.sh; its output is in the build log)
grep -h 'check-fma' /build/out/logs/*.log 2> /dev/null | head -3

# the AVX-512 objects (SH_SIMD_LANES=16 with -mavx512f), compile only
if [ "$(uname -m)" = x86_64 ]; then
  flags=(-std=c++23 -O3 -ffp-contract=off -Wall -Wextra -Wpedantic -Wshadow -Wconversion -Wno-sign-conversion -Wnon-virtual-dtor -Wold-style-cast
         -Wcast-align -Wnull-dereference -Wimplicit-fallthrough -Werror -D_GLIBCXX_ASSERTIONS -Isrc/core -Isrc/nn -DSH_SIMD_LANES=16)
  ok=1
  g++-12 "${flags[@]}" -mavx512f -mfma -ffp-contract=fast -c src/nn/simd_net.cpp -o /tmp/simd_net_512.o 2> /tmp/avx512.err || ok=0
  g++-12 "${flags[@]}" -mavx512f -mfma -c src/nn/simd_net_nofma.cpp -o /tmp/simd_net_nofma_512.o 2>> /tmp/avx512.err || ok=0
  if [ "$ok" = 1 ]; then
    fused=$(objdump -d /tmp/simd_net_512.o | grep -c -E 'vfn?m(add|sub)[0-9a-z]*ps')
    zmm=$(objdump -d /tmp/simd_net_512.o | grep -c 'zmm')
    nofused=$(objdump -d /tmp/simd_net_nofma_512.o | grep -c -E 'vfn?m(add|sub)')
    result "AVX-512 objects (compile only)" "ok (-mavx512f accepted; $zmm zmm instructions and $fused fused in simd_net, $nofused fused in simd_net_nofma)"
  else result "AVX-512 objects (compile only)" "FAIL"; tail -5 /tmp/avx512.err; fi
  # the assembler of the convolution loop (README, "x86 code generation of the SIMD path"): with and without the libstdc++ assertions,
  # 8 lanes (ymm) and 16 lanes (zmm); --check fails on a jump to an assertion block, a stack operand or other than 8 fused multiply-adds
  asm_ok=1; asm_note=
  for lanes in 8 16; do
    for hard in -D_GLIBCXX_ASSERTIONS ""; do
      asm=/tmp/simd_l${lanes}${hard:+_hardened}.s
      asmflags=(-std=c++23 -O3 -march=skylake-avx512 -mprefer-vector-width=512 -ffp-contract=fast -Wno-psabi)   # simd_net.cpp only: the FMA variant
      g++-12 "${asmflags[@]}" $hard -DSH_SIMD_LANES=$lanes -Isrc/core -Isrc/nn -S src/nn/simd_net.cpp -o "$asm" 2>> /tmp/avx512.err || asm_ok=0
      perl scripts/simd-loop-report.pl --check "$asm" > "$asm.report" 2>&1 || asm_ok=0
      asm_note+=" l$lanes${hard:+/hardened}: $(perl -ne 'print "$1 instr, $2 jumps to assertions, $3 stack; " if /(\d+) instructions, 8 fma, \d+ broadcasts, \d+ conditional jumps, (\d+) jumps to an assertion block, (\d+) stack/' "$asm.report" | head -c 60)"
    done
  done
  cat /tmp/simd_l*.report
  if [ "$asm_ok" = 1 ]; then result "SIMD convolution loop (assembler)" "ok ($asm_note)"; else result "SIMD convolution loop (assembler)" "FAIL"; fi
else
  result "AVX-512 objects" "SKIP (arm64 container: no x86 flags; use --platform linux/amd64)"
fi

# CMake: the project needs 3.28 (cpp/CMakeLists.txt), Ubuntu 22.04 has 3.22. The build of the AWS instance is build-cuda.sh (no CMake), so
# this is a note, not a failure.
need=$(perl -ne 'print "$1.$2" if /cmake_minimum_required\(VERSION\s+(\d+)\.(\d+)/' /work/cpp/CMakeLists.txt)
have=$(cmake --version | perl -ne 'print "$1.$2" if /version (\d+)\.(\d+)/')
result "cmake from apt" "SKIP (apt has $have, CMakeLists.txt needs $need; the instance builds with build-cuda.sh, not CMake)"

echo "== $FAILS check(s) failed"
[ "$FAILS" = 0 ]
