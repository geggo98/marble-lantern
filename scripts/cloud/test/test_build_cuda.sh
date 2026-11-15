#!/usr/bin/env bash
# cpp/scripts/build-cuda.sh with a fake nvcc, a fake g++-12 and a fake objdump: they only log their arguments and create the
# output file, so the test needs no CUDA, no compiler and no network. It checks the command lines that the paid build would run
# (docs/cpp-comparison-plan.md, WP12) and the failure modes (exit 3: no nvcc, no g++-12, no compute_75).
set -u
# shellcheck disable=SC1091
. "$(dirname "$0")/lib.sh"

REPO=$(cd "$SRC_CLOUD/../.." && pwd)
CPP=$REPO/cpp
command -v zsh > /dev/null || { echo "zsh is not installed: cannot test build-cuda.sh"; exit 1; }

T=$(mktemp -d "${TMPDIR:-/tmp}/buildcuda.XXXXXX")
trap 'rm -rf "$T"' EXIT
BIN=$T/bin; LOG=$T/calls.log
mkdir -p "$BIN"

# fake tools --------------------------------------------------------------------------------------------------------------
write_fakes() {   # write_fakes ARCHLIST   (the output of nvcc --list-gpu-arch)
  cat > "$BIN/nvcc" <<EOF
#!/usr/bin/env bash
echo "nvcc \$*" >> "$LOG"
case "\$1" in
  --version) echo "nvcc: NVIDIA (R) Cuda compiler driver"; echo "Cuda compilation tools, release 13.0, V13.0.48"; exit 0 ;;
  --list-gpu-arch) printf '%s\n' $1; exit 0 ;;
esac
while [ \$# -gt 0 ]; do [ "\$1" = -o ] && { : > "\$2"; exit 0; }; shift; done
exit 0
EOF
  cat > "$BIN/g++-12" <<EOF
#!/usr/bin/env bash
echo "g++-12 \$*" >> "$LOG"
case "\$1" in --version) echo "g++-12 (Ubuntu 12.3.0-1ubuntu1~22.04) 12.3.0"; exit 0 ;; esac
while [ \$# -gt 0 ]; do [ "\$1" = -o ] && { : > "\$2"; exit 0; }; shift; done
exit 0
EOF
  # objdump: the "fma" object has fused instructions, the "nofma" one has none (the object name tells)
  cat > "$BIN/objdump" <<'EOF'
#!/usr/bin/env bash
for last; do :; done
for i in $(seq 1 150); do printf '  %x:\t1e2e0c20 \tfadd\ts0, s1, s2\n' $((i * 4)); done
case "$last" in *nofma*) [ -n "${FAKE_FUSED_IN_NOFMA:-}" ] && printf '  a00:\t1e2e0c20 \tfmadd\ts0, s1, s2, s3\n' ;;
  *) printf '  a04:\t1e2e0c20 \tfmadd\ts0, s1, s2, s3\n' ;; esac
EOF
  chmod +x "$BIN/nvcc" "$BIN/g++-12" "$BIN/objdump"
}
write_fakes "compute_75 compute_80 compute_86"

run_build() {   # run_build OUTDIR ARGS...   (stub tools first on the PATH; the real nvcc and g++-12 must not be reachable)
  RC=0; local out=$1; shift
  : > "$LOG"
  env PATH="$BIN:/usr/bin:/bin:/usr/sbin:/sbin" zsh "$CPP/scripts/build-cuda.sh" --out "$out" --jobs 4 "$@" > "$T/out.txt" 2>&1 || RC=$?
  OUTTXT=$(cat "$T/out.txt")
}

echo "a normal build:"
run_build "$T/out1"
assert_eq "$RC" "0" "exit code"
CALLS=$(cat "$LOG")
NVCC_LINES=$(grep '^nvcc ' "$LOG" | grep -e '-c src/gpu/cuda/cuda_net.cu')   # the compile lines (the link lines are checked below)
GXX_LINES=$(grep '^g++-12 ' "$LOG" | grep -v -e '--version')
assert_contains "$NVCC_LINES" "-ccbin $BIN/g++-12" "nvcc gets -ccbin with the g++-12 that was found"
assert_contains "$NVCC_LINES" "-std=c++20" "nvcc compiles as C++20"
assert_contains "$NVCC_LINES" "-gencode arch=compute_75,code=sm_75" "nvcc: gencode for sm_75"
assert_contains "$NVCC_LINES" "-gencode arch=compute_75,code=compute_75" "nvcc: gencode for the PTX of compute_75"
assert_contains "$NVCC_LINES" "--fmad=true" "nvcc: fmad is set explicitly (as NVRTC does by default)"
assert_contains "$NVCC_LINES" "-Xcompiler -ffp-contract=off" "nvcc: the host part has no contraction either"
assert_contains "$NVCC_LINES" "src/gpu/cuda/cuda_net.cu" "nvcc compiles cuda_net.cu"
assert_eq "$(echo "$NVCC_LINES" | grep -c -e '-c src/gpu/cuda/cuda_net.cu')" "2" "cuda_net.cu is compiled twice (hardened and unchecked)"
assert_not_contains "$CALLS" "use_fast_math" "no --use_fast_math anywhere"
assert_not_contains "$CALLS" "ffast-math" "no -ffast-math anywhere"
assert_eq "$(echo "$GXX_LINES" | grep -c -v -e '-std=c++23')" "0" "every g++-12 line has -std=c++23"
assert_eq "$(echo "$GXX_LINES" | grep -c -v -e '-ffp-contract=')" "0" "every g++-12 line sets -ffp-contract"
FAST=$(echo "$GXX_LINES" | grep -e '-ffp-contract=fast')
assert_eq "$(echo "$FAST" | grep -c -v 'simd_net\.cpp')" "0" "-ffp-contract=fast only on simd_net.cpp lines"
assert_eq "$(echo "$FAST" | grep -c .)" "4" "-ffp-contract=fast: simd_net.cpp, for 2 lane counts and 2 modes"
assert_eq "$(echo "$GXX_LINES" | grep -e 'simd_net_nofma' | grep -c -e '-ffp-contract=fast')" "0" "simd_net_nofma.cpp is not built with fast contraction"
ORDER=$(echo "$FAST" | perl -ne '$o = index($_, "-ffp-contract=off"); $f = index($_, "-ffp-contract=fast"); $bad++ unless $o >= 0 && $f > $o; END { print $bad // 0 }')
assert_eq "$ORDER" "0" "on a simd_net.cpp line the later flag, which wins, is -ffp-contract=fast"
assert_contains "$GXX_LINES" "-DSH_SIMD_LANES=8" "lanes 8 built"
assert_contains "$GXX_LINES" "-DSH_SIMD_LANES=16" "lanes 16 built"
ARCH=-march=native; [ "$(uname -m)" = arm64 ] && ARCH=-mcpu=native
assert_contains "$GXX_LINES" "$ARCH" "CPU-native flag of this machine ($ARCH)"
assert_eq "$(grep -c '^nvcc .*-Xcompiler -D_GLIBCXX_ASSERTIONS' "$LOG")" "1" "nvcc: assertions on the host part of the hardened object only"
assert_eq "$(echo "$GXX_LINES" | grep -e '-c src/core/sha256.cpp' | grep -c -e '-D_GLIBCXX_ASSERTIONS')" "1" "sha256.cpp: one of its two builds is hardened"
assert_eq "$(echo "$GXX_LINES" | grep -e '-c src/core/sha256.cpp' | grep -c .)" "2" "sha256.cpp is built twice"
assert_not_contains "$GXX_LINES" "-Werror" "no -Werror on the instance without --werror"
assert_contains "$CALLS" "nvcc -ccbin $BIN/g++-12 -std=c++20" "nvcc links the binaries"
for b in nn_check_l8 nn_check_l16 nn_bench_l8 nn_bench_l16 cuda_test; do
  if [ -e "$T/out1/hardened/bin/$b" ] && [ -e "$T/out1/unchecked/bin/$b" ]; then pass "binary $b in both builds"; else fail "binary $b missing"; fi
done
assert_contains "$(cat "$T/out1/toolchain/nvcc-arch-list.txt")" "compute_75" "the architecture list is kept in the result directory"
assert_contains "$(cat "$T/out1/toolchain/nvcc-version.txt")" "release 13.0" "the nvcc version is kept in the result directory"
assert_contains "$(cat "$T/out1/toolchain/cxx-version.txt")" "12.3.0" "the compiler version is kept in the result directory"
assert_contains "$(cat "$T/out1/toolchain/flags.txt")" "fmad=true" "the flags are recorded"
assert_contains "$OUTTXT" "check-fma lanes=8" "the object-code proof ran for lanes 8"
assert_contains "$OUTTXT" "check-fma lanes=16" "the object-code proof ran for lanes 16"

echo "--werror:"
run_build "$T/out2" --werror
assert_eq "$RC" "0" "exit code"
assert_contains "$(grep '^g++-12 ' "$LOG" | grep -c -e '-Werror')" "" "-Werror is passed"
assert_eq "$(grep '^g++-12 ' "$LOG" | grep -v -e '--version' | grep -c -v -e '-Werror')" "0" "every g++-12 line has -Werror"

echo "the stub build needs no nvcc:"
run_build "$T/out3" --stub
assert_eq "$RC" "0" "exit code"
assert_not_contains "$(cat "$LOG")" "nvcc" "nvcc is not run"
assert_contains "$(cat "$LOG")" "cuda_net_stub.cpp" "the stub replaces cuda_net.cu"
assert_not_contains "$(cat "$LOG")" "cuda_net.cu" "cuda_net.cu is not compiled"

echo "no nvcc:"
mv "$BIN/nvcc" "$BIN/nvcc.off"
run_build "$T/out4"
assert_eq "$RC" "3" "exit code 3 without nvcc"
assert_contains "$OUTTXT" "nvcc not found" "the message names nvcc"
mv "$BIN/nvcc.off" "$BIN/nvcc"

echo "no g++-12:"
mv "$BIN/g++-12" "$BIN/g++-12.off"
run_build "$T/out5"
assert_eq "$RC" "3" "exit code 3 without g++-12"
assert_contains "$OUTTXT" "g++-12 not found" "the message names g++-12"
mv "$BIN/g++-12.off" "$BIN/g++-12"

echo "an nvcc without compute_75:"
write_fakes "compute_80 compute_86 compute_90"
run_build "$T/out6"
assert_eq "$RC" "3" "exit code 3 without compute_75"
assert_contains "$OUTTXT" "cannot target compute_75" "the message names compute_75"
assert_eq "$(grep -c -e '-c ' "$LOG")" "0" "nothing was compiled"

echo "--nvcc and --cxx:"
write_fakes "compute_75"
cp "$BIN/nvcc" "$T/my-nvcc"; cp "$BIN/g++-12" "$T/my-gxx"
run_build "$T/out7" --nvcc "$T/my-nvcc" --cxx "$T/my-gxx"
assert_eq "$RC" "0" "exit code"
assert_contains "$(grep '^nvcc ' "$LOG" | tail -1)" "-ccbin $T/my-gxx" "-ccbin names the g++ that was given"

echo "the proof of the FMA variants:"
FAKE_FUSED_IN_NOFMA=1 run_build "$T/out8"
assert_eq "$RC" "5" "exit code 5 if the no-FMA object has a fused instruction"
assert_contains "$OUTTXT" "check-fma" "the message names the proof"

echo "usage:"
run_build "$T/out9" --nonsense
assert_eq "$RC" "64" "exit code 64 for an unknown option"

finish_test
