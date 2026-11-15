#!/bin/zsh
# usage: build-gcc12-stub.sh [outdir]
# Builds and runs the Linux-compatible part of the C++ code with GCC on the Mac: build-cuda.sh with --stub (the same file list,
# flags and command lines as on the instance, cuda_net_stub.cpp in place of cuda_net.cu, -Werror). It proves the language rule of
# the plan (C++23 mode, only library features that libstdc++ 12 has) before the paid AWS run.
# WP0 spike result: GCC 12.4 from the nixos-25.05 branch does not run on this macOS (dyld: duplicate LC_RPATH), so the
# fallback of the plan applies: GCC 13.5 from a pinned nixpkgs revision, ad hoc and not part of devenv.nix. GCC 13 has headers
# that libstdc++ 12 lacks (<format>); check-rules.sh rule R6 rejects them in the Linux file list.
# The engine and search code is not in this list: it runs on the Mac only and may use all of C++23 that Apple clang has.
# Runs afterwards (hardened binaries): java_compat_test, nn_test, cuda_adapter_test, cuda_test (stub: no device), cuda_test_emu
# (cuda_test against the emulated device: the real kernels on the CPU), and nn_check for the reference and both SIMD variants on
# SMALL and LARGE (needs the golden files).
set -eu
setopt pipefail

cd ${0:A:h:h}                              # the cpp/ directory
if [[ -z ${SH_GCC_INNER:-} ]]; then
  # nixpkgs revision of the 2026-11-15 spike (gcc 13.5.0); the pin keeps the compiler the same from run to run
  SH_GCC_INNER=1 exec nix shell github:NixOS/nixpkgs/0bb2eedc20e4739f29e8045034f2828ad7054f8f#gcc13 -c $0 "$@"
fi
g++ --version >/dev/null || { print -u2 "g++ does not run"; exit 1; }

out=${1:-build/gcc12-stub}
print -u2 "compiler: $(g++ --version | head -1)"
scripts/build-cuda.sh --stub --werror --with-tests --cxx g++ --out $out || { print -u2 "FAIL: build-cuda.sh --stub exited with $?"; exit 1 }

result=0
bin=$out/hardened/bin
for t in java_compat_test nn_test cuda_adapter_test cuda_test_emu; do
  if $bin/$t > $out/logs/run-$t.log 2>&1; then print -u2 "ok:   $t ($(tail -1 $out/logs/run-$t.log))"; else print -u2 "FAIL: $t (see $out/logs/run-$t.log)"; tail -5 $out/logs/run-$t.log >&2; result=1; fi
done
if $bin/cuda_test --allow-no-device > $out/logs/run-cuda_test.log 2>&1; then print -u2 "ok:   cuda_test ($(head -1 $out/logs/run-cuda_test.log))"; else print -u2 "FAIL: cuda_test"; result=1; fi
if $bin/nn_check_l8 --impl cuda > /dev/null 2>&1; rc=$?; (( rc == 64 )); then print -u2 "ok:   nn_check --impl cuda answers 64 in the stub build"; else print -u2 "FAIL: nn_check --impl cuda exited with $rc"; result=1; fi
if [[ -f golden/MANIFEST.tsv ]]; then
  for impl in reference simd simd-nofma; do
    for preset in small large; do
      if $bin/nn_check_l8 --impl $impl --preset $preset > $out/logs/run-nn_check-$impl-$preset.log 2>&1; then
        print -u2 "ok:   nn_check --impl $impl --preset $preset: $(tail -1 $out/logs/run-nn_check-$impl-$preset.log | cut -c1-150)"
      else
        print -u2 "FAIL: nn_check --impl $impl --preset $preset"; tail -3 $out/logs/run-nn_check-$impl-$preset.log >&2; result=1
      fi
    done
  done
else
  print -u2 "note: no golden files in golden/: nn_check not run"
fi
exit $result
