#!/bin/zsh
# usage: build-cuda.sh [--nvcc PATH] [--cxx PATH] [--out DIR] [--stub] [--werror] [--with-tests] [--jobs N]
#
# Builds the CUDA backend and its binaries with explicit command lines (no CMake: fewer things that can fail on paid time):
# g++-12 -std=c++23 for every .cpp, nvcc -ccbin g++-12 -std=c++20 for src/gpu/cuda/cuda_net.cu (docs/cpp-comparison-plan.md,
# "Toolchain" and WP12). Two builds of everything: hardened (-D_GLIBCXX_ASSERTIONS: the tests and the main rows) and unchecked
# (the "cost of the checks" rows). simd_net.cpp is built for SH_SIMD_LANES=8 and 16 (two nn_bench binaries), and its object code
# is checked for fused multiply-add (check-fma.sh).
#
#   --nvcc PATH   the nvcc to use (default: command -v nvcc, /usr/local/cuda/bin/nvcc, the newest /usr/local/cuda-*/bin/nvcc)
#   --cxx PATH    the C++ compiler (default: g++-12)
#   --out DIR     build directory (default build/cuda under cpp/)
#   --stub        no CUDA: cuda_net_stub.cpp in place of cuda_net.cu, nvcc is not needed (scripts/build-gcc12-stub.sh, the Mac)
#   --werror      -Werror (the stub build on the Mac proves "no warning"; the instance does not stop for a warning of another gcc)
#   --with-tests  also build java_compat_test, nn_test, cuda_adapter_test and cuda_test_emu (the stub build runs them)
#   --jobs N      parallel compiler runs (default: the number of CPUs)
#   environment SH_EXTRA_CXXFLAGS: extra g++ options for every .cpp, blank separated (the Ubuntu container build uses -Wno-psabi)
#
# Flags that the plan fixes: -ffp-contract=off everywhere except simd_net.cpp (-ffp-contract=fast, the FMA variant), and no
# fast-math option for g++ or nvcc. nvcc gets --fmad=true: what TornadoVM's NVRTC call does by default (README, "TornadoVM math
# flags"), recorded in toolchain/flags.txt.
#
# Exit codes: 0 built; 3 no nvcc or no g++-12, or nvcc cannot target compute_75 (CUDA 13.0 dropped the older ones);
# 4 a compiler or the linker failed; 5 the object-code proof of check-fma.sh failed; 64 usage error.
set -u
setopt pipefail

cd ${0:A:h:h} || exit 64     # the cpp/ directory
cpp_dir=$PWD

nvcc=; cxx=; out=build/cuda; stub=0; werror=0; with_tests=0; jobs=$(getconf _NPROCESSORS_ONLN 2>/dev/null || print 2)
while (( $# )); do
  case $1 in
    --nvcc) nvcc=${2:?--nvcc needs a path}; shift 2 ;;
    --cxx) cxx=${2:?--cxx needs a path}; shift 2 ;;
    --out) out=${2:?--out needs a directory}; shift 2 ;;
    --stub) stub=1; shift ;;
    --werror) werror=1; shift ;;
    --with-tests) with_tests=1; shift ;;
    --jobs) jobs=${2:?--jobs needs a number}; shift 2 ;;
    *) print -u2 "build-cuda.sh: unknown option $1"; exit 64 ;;
  esac
done

# ---- tools ----
[[ -n $cxx ]] || cxx=$(command -v g++-12) || { print -u2 "build-cuda.sh: g++-12 not found (apt install g++-12, or --cxx PATH)"; exit 3 }
[[ -x $cxx ]] || cxx=$(command -v $cxx) || { print -u2 "build-cuda.sh: $cxx is not executable"; exit 3 }
if (( ! stub )); then
  if [[ -z $nvcc ]]; then
    nvcc=$(command -v nvcc) || nvcc=
    [[ -n $nvcc ]] || { [[ -x /usr/local/cuda/bin/nvcc ]] && nvcc=/usr/local/cuda/bin/nvcc }
    if [[ -z $nvcc ]]; then
      newest=(/usr/local/cuda-*/bin/nvcc(NOn[1]))      # (n) numeric order, (On) descending: the newest toolkit first
      (( $#newest )) && nvcc=$newest[1]
    fi
  fi
  [[ -n $nvcc && -x $nvcc ]] || { print -u2 "build-cuda.sh: nvcc not found (--nvcc PATH, PATH, /usr/local/cuda*/bin)"; exit 3 }
fi

mkdir -p $out/toolchain $out/logs || exit 64
tool=$out/toolchain
$cxx --version > $tool/cxx-version.txt 2>&1
if (( ! stub )); then
  $nvcc --version > $tool/nvcc-version.txt 2>&1
  $nvcc --list-gpu-arch > $tool/nvcc-arch-list.txt 2>&1
  # CUDA 13.0 removed offline compilation below compute capability 7.5; the T4 is 7.5
  grep -q 'compute_75' $tool/nvcc-arch-list.txt || { print -u2 "build-cuda.sh: nvcc cannot target compute_75 ($(tr '\n' ' ' < $tool/nvcc-arch-list.txt))"; exit 3 }
fi
print -u2 "cxx:  $(head -1 $tool/cxx-version.txt)"
(( stub )) || print -u2 "nvcc: $(grep -i release $tool/nvcc-version.txt | head -1)"

# ---- flags ----
arch=-march=native
[[ $(uname -m) == (arm64|aarch64) ]] && arch=-mcpu=native      # the same CPU-native rule as CMakeLists.txt
cxxflags=(-std=c++23 -O3 $arch -ffp-contract=off
          -Wall -Wextra -Wpedantic -Wshadow -Wconversion -Wno-sign-conversion -Wnon-virtual-dtor
          -Wold-style-cast -Wcast-align -Wnull-dereference -Wimplicit-fallthrough
          -Isrc/core -Isrc/nn -Isrc/gpu/cuda -Iapps -DSH_GOLDEN_DIR=\"$cpp_dir/golden\")
(( werror )) && cxxflags+=(-Werror)
# SH_EXTRA_CXXFLAGS: extra g++ options (blank separated), for example -Wno-psabi on an x86-64 CPU without AVX-512 (container builds)
[[ -n ${SH_EXTRA_CXXFLAGS:-} ]] && cxxflags+=(${=SH_EXTRA_CXXFLAGS})
# --fmad=true as NVRTC does by default; -lineinfo for compute-sanitizer; no --use_fast_math
nvccflags=(-std=c++20 -ccbin $cxx -O3 -lineinfo --fmad=true
           -gencode arch=compute_75,code=sm_75 -gencode arch=compute_75,code=compute_75
           -Xcompiler -ffp-contract=off -Isrc/gpu/cuda)
{
  print "cxx:      $cxx $cxxflags"
  print "nvcc:     ${nvcc:-none (stub build)} $nvccflags"
  print "fma:      simd_net.cpp -ffp-contract=fast, everything else -ffp-contract=off"
  print "lanes:    SH_SIMD_LANES=8 and 16 (two nn_bench binaries), nn_bench and nn_check with the native default"
} > $tool/flags.txt

# ---- parallel runs: logs per job, the log is printed only on a failure ----
pids=(); names=(); failed=0
reap() {   # waits for the oldest job
  wait $pids[1] || { failed=1; print -u2 "FAILED: $names[1]"; tail -40 $out/logs/${names[1]}.log >&2 }
  pids=(${pids[2,-1]}); names=(${names[2,-1]})
}
launch() {   # launch NAME COMMAND...   (the name is also the log file name)
  local name=$1; shift
  ( "$@" > $out/logs/$name.log 2>&1 ) &
  pids+=$!; names+=$name
  (( $#pids >= jobs )) && reap
}
drain() { while (( $#pids )); do reap; done }

lane_values=(8 16)
sources_common=(src/core/*.cpp src/nn/*.cpp)
sources_common=(${sources_common:#src/nn/simd_net.cpp})
sources_common=(${sources_common:#src/nn/simd_net_nofma.cpp})
simd_sources=(src/nn/simd_net.cpp src/nn/simd_net_nofma.cpp)
if (( stub )); then cuda_obj_src=src/gpu/cuda/cuda_net_stub.cpp; else cuda_obj_src=src/gpu/cuda/cuda_net.cu; fi

objname() { print -r -- ${1//\//_}.o }

build_mode() {   # build_mode NAME EXTRA-FLAGS...   ->  $out/NAME/{obj,bin}
  local mode=$1; shift
  local extra=($@)
  local dir=$out/$mode
  mkdir -p $dir/obj $dir/bin
  local f
  for f in $sources_common; do
    launch $mode-$(objname $f) $cxx $cxxflags $extra -c $f -o $dir/obj/$(objname $f)
  done
  if (( stub )); then
    launch $mode-$(objname $cuda_obj_src) $cxx $cxxflags $extra -c $cuda_obj_src -o $dir/obj/$(objname $cuda_obj_src)
  else
    local nvcc_extra=()
    for f in $extra; do nvcc_extra+=(-Xcompiler $f); done
    launch $mode-$(objname $cuda_obj_src) $nvcc $nvccflags $nvcc_extra -c $cuda_obj_src -o $dir/obj/$(objname $cuda_obj_src)
  fi
  local lanes
  for lanes in $lane_values; do
    mkdir -p $dir/obj/lanes$lanes
    for f in $simd_sources; do
      local fast=()
      [[ $f == src/nn/simd_net.cpp ]] && fast=(-ffp-contract=fast)      # the one named exception
      launch $mode-lanes$lanes-$(objname $f) $cxx $cxxflags $extra $fast -DSH_SIMD_LANES=$lanes -c $f -o $dir/obj/lanes$lanes/$(objname $f)
    done
  done
}

# One binary = one application source compiled for a lane count (the header simd_net.hpp is lane dependent: the application and
# the simd objects must agree) and linked with the common objects of its mode and the simd objects of that lane count.
# bin_table entries: name:lanes:source[:extra flags, separated by blanks]
bin_table=(nn_check_l8:8:apps/nn_check.cpp nn_check_l16:16:apps/nn_check.cpp
           nn_bench_l8:8:apps/nn_bench.cpp nn_bench_l16:16:apps/nn_bench.cpp
           cuda_test:8:tests/cuda_test.cpp)
# --with-tests: the tests that run on the Mac, and cuda_test against the emulated device (tests/cuda_emu.hpp; "a test of the test")
(( with_tests )) && bin_table+=(java_compat_test:8:tests/java_compat_test.cpp nn_test:8:tests/nn_test.cpp:-Itests
                               cuda_adapter_test:8:tests/cuda_adapter_test.cpp "cuda_test_emu:8:tests/cuda_test.cpp:-DSH_CUDA_TEST_EMULATION -Itests")

compile_apps() {   # compile_apps MODE FLAGS...
  local mode=$1; shift
  local entry parts
  mkdir -p $out/$mode/app
  for entry in $bin_table; do
    parts=("${(@s.:.)entry}")
    launch $mode-app-$parts[1] $cxx $cxxflags $@ -DSH_SIMD_LANES=$parts[2] ${=parts[4]:-} -c $parts[3] -o $out/$mode/app/$parts[1].o
  done
}

link_apps() {   # link_apps MODE
  local mode=$1
  local dir=$out/$mode
  local entry parts common=($dir/obj/*.o(N))
  mkdir -p $dir/bin
  for entry in $bin_table; do
    parts=("${(@s.:.)entry}")
    local objs=($common $dir/obj/lanes$parts[2]/*.o(N) $dir/app/$parts[1].o)
    if (( stub )); then
      launch $mode-link-$parts[1] $cxx $objs -pthread -o $dir/bin/$parts[1]
    else
      launch $mode-link-$parts[1] $nvcc -ccbin $cxx -std=c++20 $objs -o $dir/bin/$parts[1]
    fi
  done
}

mode_assert=(-D_GLIBCXX_ASSERTIONS)
build_mode hardened $mode_assert
build_mode unchecked
drain
if (( failed )); then print -u2 "build-cuda.sh: a compile failed"; exit 4; fi

# ---- the object-code proof of the FMA variants (check-fma.sh), per lane count ----
fma_failed=0
for lanes in $lane_values; do
  d=$out/hardened/obj/lanes$lanes
  if scripts/check-fma.sh $d/$(objname src/nn/simd_net.cpp) $d/$(objname src/nn/simd_net_nofma.cpp) > $out/logs/check-fma-lanes$lanes.log 2>&1; then
    print -u2 "ok:   check-fma lanes=$lanes: $(grep 'fused' $out/logs/check-fma-lanes$lanes.log | tr '\n' ';')"
  else
    rc=$?
    if (( rc == 77 )); then print -u2 "note: check-fma lanes=$lanes skipped (no disassembler)"; else
      print -u2 "FAIL: check-fma lanes=$lanes"; cat $out/logs/check-fma-lanes$lanes.log >&2; fma_failed=1
    fi
  fi
done
(( fma_failed )) && exit 5

# ---- binaries ----
compile_apps hardened -D_GLIBCXX_ASSERTIONS
compile_apps unchecked
drain
if (( failed )); then print -u2 "build-cuda.sh: a compile failed"; exit 4; fi
link_apps hardened
link_apps unchecked
drain
if (( failed )); then print -u2 "build-cuda.sh: a link failed"; exit 4; fi

print -u2 "built (hardened): $(ls $out/hardened/bin | tr '\n' ' ')"
print -u2 "built (unchecked): $(ls $out/unchecked/bin | tr '\n' ' ')"
print -u2 "build-cuda.sh: ok ($out)"
exit 0
