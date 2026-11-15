#!/bin/zsh
# usage: build-gcc12-stub.sh [outdir]
# Builds and runs the Linux-compatible part of the C++ code with GCC on the Mac: the same file list and flags as
# build-cuda.sh, with cuda_net_stub.cpp in place of cuda_net.cu. It proves the language rule of the plan (C++23 mode, only
# library features that libstdc++ 12 has) before the paid AWS run.
# WP0 spike result: GCC 12.4 from the nixos-25.05 branch does not run on this macOS (dyld: duplicate LC_RPATH), so the
# fallback of the plan applies: GCC 13.5 from a pinned nixpkgs revision, ad hoc and not part of devenv.nix. GCC 13 has headers
# that libstdc++ 12 lacks (<format>); check-rules.sh rule R6 rejects them in the Linux file list.
# The engine and search code is not in this list: it runs on the Mac only and may use all of C++23 that Apple clang has.
set -eu
setopt pipefail

cd ${0:A:h:h}                              # the cpp/ directory
if [[ -z ${SH_GCC_INNER:-} ]]; then
  # nixpkgs revision of the 2026-11-15 spike (gcc 13.5.0); the pin keeps the compiler the same from run to run
  SH_GCC_INNER=1 exec nix shell github:NixOS/nixpkgs/0bb2eedc20e4739f29e8045034f2828ad7054f8f#gcc13 -c $0 "$@"
fi
g++ --version >/dev/null || { print -u2 "g++ does not run"; exit 1; }

out=${1:-build/gcc12-stub}
mkdir -p $out
print -u2 "compiler: $(g++ --version | head -1)"

# Same flags as build-cuda.sh: -ffp-contract=off everywhere except the named exception simd_net.cpp, libstdc++ assertions on.
flags=(-std=c++23 -O2 -ffp-contract=off -D_GLIBCXX_ASSERTIONS
       -Wall -Wextra -Wpedantic -Wshadow -Wconversion -Wno-sign-conversion -Wnon-virtual-dtor
       -Wold-style-cast -Wcast-align -Wnull-dereference -Wimplicit-fallthrough -Werror
       -Isrc/core -Isrc/nn -Isrc/gpu/cuda -Iapps -DSH_GOLDEN_DIR=\"$PWD/golden\")

lib=(src/core/*.cpp src/nn/*.cpp src/gpu/cuda/cuda_net_stub.cpp)
objs=()
for f in $lib; do
  o=$out/${f//\//_}.o
  extra=()
  [[ $f == src/nn/simd_net.cpp ]] && extra=(-ffp-contract=fast)     # the one named exception
  g++ $flags $extra -c $f -o $o
  objs+=$o
done

result=0
# WP10: the object code of the two SIMD variants (fused instructions in one, none in the other)
if scripts/check-fma.sh $out/src_nn_simd_net.cpp.o $out/src_nn_simd_net_nofma.cpp.o; then print -u2 "ok:   check-fma"; else print -u2 "FAIL: check-fma"; result=1; fi
for t in java_compat_test cuda_adapter_test cuda_test nn_test; do
  g++ $flags -Itests tests/$t.cpp $objs -o $out/$t
  if $out/$t; then print -u2 "ok:   $t"; else print -u2 "FAIL: $t"; result=1; fi
done
for a in nn_check nn_bench; do
  g++ $flags apps/$a.cpp $objs -o $out/$a
  print -u2 "built: $a"
done
exit $result
