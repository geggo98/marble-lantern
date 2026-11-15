#!/usr/bin/env bash
# bootstrap.sh --suite cpp with stub commands (no machine, no network): the order of the steps, the gate after the C++ build and
# the CUDA test (a failure stops the run and shuts the machine down), compute-sanitizer missing = SKIP, a missing nvcc, the real
# weights (hash check, gen11 export and check), and the default suite unchanged.
# shellcheck disable=SC2016,SC2015,SC2086  # stub scripts use single quotes on purpose; pass or fail by test; "$bad" is split into arguments on purpose
set -u
# shellcheck disable=SC1091
. "$(dirname "$0")/lib.sh"
T=$(mktemp -d "${TMPDIR:-/tmp}/cloudtest.XXXXXX"); trap 'rm -rf "$T"' EXIT
export STUB_LOG=$T/stub.log

# --- the stubs on the PATH -------------------------------------------------------------------------------------------------
BIN=$T/bin; mkdir -p "$BIN"
mkstub() { printf '#!/bin/bash\n%s\n' "$2" > "$BIN/$1"; chmod +x "$BIN/$1"; }
mkstub shutdown 'echo "$*" >> "$T_SHUTDOWN"'
mkstub apt-get 'echo "apt-get $*" >> "$STUB_LOG"; exit 0'
mkstub lscpu 'echo "Model name: stub"'
mkstub free 'echo "stub"'
mkstub ldconfig 'echo "	libnvrtc.so.13 (libc6,x86-64) => /stub/libnvrtc.so.13"'
mkstub nproc 'echo 4'
mkstub timeout 'shift; exec "$@"'
mkstub sha256sum 'perl -MDigest::SHA -e '"'"'for (@ARGV) { printf "%s  %s\n", Digest::SHA->new(256)->addfile($_)->hexdigest, $_ }'"'"' "$@"'
mkstub nvidia-smi 'case "$*" in -L) echo "GPU 0: stub (UUID: stub)" ;; *query-gpu=name*) echo "Tesla stub, 595.00" ;; *query-gpu=clocks*) echo "1590 MHz, 5001 MHz, 40, 0 %, 30 W" ;; *) echo "stub smi" ;; esac'
mkstub g++-12 'echo "g++-12 (stub) 12.3.0"'
mkstub nvcc 'case "$*" in *--list-gpu-arch*) printf "compute_75\ncompute_80\n" ;; *) echo "nvcc: stub release 13.0, V13.0.0" ;; esac'
mkstub compute-sanitizer '[ "$1" = --version ] && { echo "NVIDIA (R) Compute Sanitizer stub"; exit 0; }; echo "compute-sanitizer $*" >> "$STUB_LOG"; echo "========= ERROR SUMMARY: 0 errors"; exit "${STUB_SANITIZER_RC:-0}"'
mkdir -p "$T/jdk/bin"; printf '#!/bin/bash\necho "openjdk stub" >&2\n' > "$T/jdk/bin/java"; chmod +x "$T/jdk/bin/java"

# --- the source tarball of the stub run ------------------------------------------------------------------------------------
S=$T/srcdir; mkdir -p "$S/scripts" "$S/cpp/scripts"
cat > "$S/gradlew" <<'G'
#!/bin/bash
echo "gradlew $*" >> "$STUB_LOG"
case "$*" in
  *printRunCommand*) mkdir -p cpp/build; echo "$STUB_JAVA" > cpp/build/java-nn.cmd ;;
  *goldenExport*)
    out=$(printf '%s\n' "$@" | perl -ne 'print $1 if /^-Psternhalma.golden.out=(.*)/')
    mkdir -p "$out"
    for f in nn-weights-small-u5 nn-weights-large-u5 nn-inputs nn-cases; do echo "$f" > "$out/$f.safetensors"; done
    for f in nn-ref-small-u5 nn-ref-large-u5; do echo "ref $RANDOM" > "$out/$f.safetensors"; done
    case "$*" in *golden.real=true*) echo "gen11 ref" > "$out/nn-ref-large-gen11.safetensors"; echo "data dir: $STERNHALMA_DATA_DIR" >> "$STUB_LOG" ;; esac ;;
  *gpuFeed*) echo "feed row" ;;
esac
exit "${STUB_GRADLE_RC:-0}"
G
cat > "$S/scripts/setup-tornado.sh" <<'G'
#!/bin/zsh
echo "setup-tornado $*" >> "$STUB_LOG"
mkdir -p "$DEVENV_ROOT/tornado-sdk/current/bin"
printf '#!/bin/bash\necho "tornado $*" >> "$STUB_LOG"\n' > "$DEVENV_ROOT/tornado-sdk/current/bin/tornado"
chmod +x "$DEVENV_ROOT/tornado-sdk/current/bin/tornado"
G
cat > "$S/cpp/scripts/build-cuda.sh" <<'G'
#!/bin/zsh
echo "build-cuda $*" >> "$STUB_LOG"
[[ -n ${STUB_BUILD_RC:-} && $STUB_BUILD_RC != 0 ]] && { print -u2 "build-cuda: stub failure"; exit $STUB_BUILD_RC }
out=${${@[(i)--out]+1}:-}
for (( i = 1; i <= $#; i++ )); do [[ $@[i] == --out ]] && out=$@[i+1]; done
for kind in hardened unchecked; do
  mkdir -p $out/$kind/bin
  for b in cuda_test nn_check_l8 nn_check_l16 nn_bench_l8 nn_bench_l16; do
    printf '#!/bin/bash\necho "%s/%s $*" >> "$STUB_LOG"\n[ "%s" = cuda_test ] && exit "${STUB_CUDA_TEST_RC:-0}"\necho "stub row"\nexit 0\n' $kind $b $b > $out/$kind/bin/$b
    chmod +x $out/$kind/bin/$b
  done
done
G
cat > "$T/java-stub.sh" <<'G'
#!/bin/bash
echo "java-cross $*" >> "$STUB_LOG"
echo "java row"
G
chmod +x "$T/java-stub.sh"; export STUB_JAVA=$T/java-stub.sh
# the expected hashes: file, sha256, kind (the stub export writes the weights and inputs with these contents)
exp=$S/cpp/expected-golden-sha256.tsv; : > "$exp"
for f in nn-weights-small-u5 nn-weights-large-u5 nn-inputs nn-cases; do
  printf '%s.safetensors\t%s\tmust\n' "$f" "$(printf '%s\n' "$f" | perl -MDigest::SHA -ne 'print Digest::SHA::sha256_hex($_)')" >> "$exp"
done
printf 'nn-ref-small-u5.safetensors\t%s\tinfo\n' "$(printf 'x' | perl -MDigest::SHA -ne 'print Digest::SHA::sha256_hex($_)')" >> "$exp"
chmod +x "$S/gradlew" "$S/scripts/setup-tornado.sh" "$S/cpp/scripts/build-cuda.sh"
tar -czf "$T/src.tar.gz" -C "$S" .

# run_boot ARGS...: a fresh workdir, bootstrap.sh with the stubs; sets RC, SUMMARY (summary.txt), W (workdir)
run_boot() {
  W=$T/work; rm -rf "$W"; mkdir -p "$W"; cp "$T/src.tar.gz" "$W/"
  : > "$STUB_LOG"; : > "$T/shutdown.log"
  RC=0
  env PATH="$BIN:$PATH" JDK_HOME="$T/jdk" T_SHUTDOWN="$T/shutdown.log" STUB_LOG="$STUB_LOG" STUB_JAVA="$STUB_JAVA" \
    bash "$SRC_CLOUD/bootstrap.sh" --backend cuda --hard-minutes 45 --workdir "$W" --feed-seconds 30 "$@" > "$T/boot.out" 2>&1 || RC=$?
  SUMMARY=$(cat "$W/results/summary.txt" 2> /dev/null)
  LOG=$(cat "$STUB_LOG")
}
steps_of() { printf '%s\n' "$SUMMARY" | perl -ne 'print "$1 " if /^([\w-]+): /' ; }
# pos TEXT: the line number of the first line of $LOG that contains TEXT (0 if none)
pos() { printf '%s\n' "$LOG" | grep -n -F -- "$1" | head -1 | cut -d: -f1; }

echo "suite cpp, everything works:"
run_boot --suite cpp
assert_eq "$RC" 0 "exit 0 (output: $(tail -n 3 "$T/boot.out" | tr '\n' ' '))"
assert_eq "$(steps_of)" "backend suite seconds apt-packages cpp-toolchain cpp-build cpp-cuda-test gate cpp-sanitizer tornado-setup tornado-devices build print-run-command golden golden-hash cpp-check anchors-start cpp-bench java-crossbench anchors-end gpufeed-small gpufeed-large " "summary: the steps in the order of the plan"
assert_contains "$SUMMARY" "suite: cpp" "summary names the suite"
assert_contains "$SUMMARY" "gate: passed" "the gate passed"
assert_not_contains "$SUMMARY" "FAIL" "no step failed"
# the order of the stub calls
[ "$(pos 'build-cuda')" -gt 0 ] && [ "$(pos 'build-cuda')" -lt "$(pos 'hardened/cuda_test')" ] && pass "build-cuda before cuda_test" || fail "build-cuda / cuda_test order"
[ "$(pos 'hardened/cuda_test')" -lt "$(pos 'compute-sanitizer')" ] && pass "cuda_test before the sanitizer" || fail "cuda_test / sanitizer order"
[ "$(pos 'compute-sanitizer')" -lt "$(pos 'gradlew')" ] && pass "the sanitizer before the first Gradle call" || fail "sanitizer / gradle order"
[ "$(pos 'hardened/cuda_test')" -lt "$(pos 'setup-tornado')" ] && pass "cuda_test before the TornadoVM setup" || fail "cuda_test / setup-tornado order"
assert_contains "$LOG" "compute-sanitizer --tool memcheck --error-exitcode 1" "the sanitizer: memcheck"
assert_contains "$LOG" "cuda_test --batches 1,67" "on cuda_test --batches 1,67"
assert_contains "$LOG" "--nvcc $BIN/nvcc" "build-cuda gets the nvcc that was found (the stub on the PATH)"
assert_contains "$LOG" "--cxx g++-12" "and g++-12"
assert_contains "$(cat "$W/results/nvcc-version.txt")" "release 13.0" "nvcc-version.txt"
assert_contains "$(cat "$W/results/toolchain.txt")" "compute-sanitizer: $BIN/compute-sanitizer" "toolchain.txt names the sanitizer"
assert_contains "$(cat "$W/results/toolchain.txt")" "compute_75" "toolchain.txt lists the GPU architectures"
assert_contains "$(cat "$W/results/golden-sha256.txt")" "equal" "golden hashes compared"
# the anchors: C++, Java, C++, Java
order=$(grep -E '^#' "$W/results/anchors.tsv" | perl -ne 'print "$1 " if /anchors-start round \d: (\w+\+*)/' | tr -d '\n')
assert_eq "$order" "C++ Java C++ Java " "anchors-start: C++, Java, C++, Java"
assert_eq "$(grep -c '^# anchors-end' "$W/results/anchors.tsv")" 4 "anchors-end: four blocks"
assert_eq "$(grep -c anchors-start "$W/results/nvidia-smi.csv")" 1 "an nvidia-smi line at the start of the anchors"
assert_contains "$(cat "$W/results/nvidia-smi.csv")" "cpp-bench" "an nvidia-smi line before the C++ block"
assert_contains "$(cat "$W/results/nvidia-smi.csv")" "java-crossbench" "an nvidia-smi line before the Java block"
assert_contains "$LOG" "nn_check_l8 --golden" "nn_check ran"
assert_not_contains "$LOG" "--preset gen11" "no gen11 check without real weights"
assert_not_contains "$LOG" "golden.real=true" "no real-weights export without real weights"
assert_not_contains "$LOG" "vectorBenchmark" "the Vector API benchmarks are not in the cpp suite"
assert_not_contains "$LOG" "TornadoNetworkTest" "nor the device tests"
assert_contains "$LOG" "nn_bench_l8 --golden" "nn_bench ran"
assert_contains "$LOG" "java-cross --golden" "crossBench ran with the command of printRunCommand"
# cold start: --cold measures one case per process, so each case needs --presets, --batches and --impls (the first run had none of
# the last two and measured one case only); 3 rounds x 2 presets x 2 batches x 3 C++ impls, and x 2 Java impls
cold_cpp=$(printf '%s\n' "$LOG" | grep -c 'hardened/nn_bench_l8 .*--cold')
cold_java=$(printf '%s\n' "$LOG" | grep -c 'java-cross .*--cold')
assert_eq "$cold_cpp" 36 "cold start C++: 36 processes (3 rounds x 2 presets x 2 batches x simd, cuda-pageable, cuda-pinned)"
assert_eq "$cold_java" 24 "cold start Java: 24 processes (3 rounds x 2 presets x 2 batches x vector, tornado)"
assert_eq "$(printf '%s\n' "$LOG" | grep -- '--cold' | grep -c -- '--presets [a-z]* --batches [0-9]* --impls [a-z-]* --cold')" 60 "every cold process names preset, batch and one impl"
assert_eq "$(printf '%s\n' "$LOG" | grep -- '--cold' | grep -c -E -- '--impls [a-z-]*,|--presets [a-z]*,|--batches [0-9]*,')" 0 "no cold process takes a list (a list would measure only the first case)"
for impl in simd cuda-pageable cuda-pinned; do for preset in small large; do for batch in 1 256; do
  assert_eq "$(printf '%s\n' "$LOG" | grep -c -- "nn_bench_l8 --golden .* --presets $preset --batches $batch --impls $impl --cold")" 3 "cold start C++ $impl $preset batch $batch: three rounds"
done; done; done
for impl in vector tornado; do for preset in small large; do for batch in 1 256; do
  assert_eq "$(printf '%s\n' "$LOG" | grep -c -- "java-cross --golden .* --presets $preset --batches $batch --impls $impl --cold")" 3 "cold start Java $impl $preset batch $batch: three rounds"
done; done; done
assert_contains "$(cat "$W/results/nn-bench.tsv")" "# hardened, cold start round 3: cuda-pinned large batch 256" "nn-bench.tsv labels every cold process"
assert_contains "$(cat "$W/results/crossbench.tsv")" "# cold start round 3: tornado large batch 256" "crossbench.tsv labels every cold process"
assert_contains "$LOG" "gpuFeed --args=--size small --batches 256 --seconds 30" "gpuFeed rows with the feed seconds"
assert_contains "$(cat "$T/shutdown.log")" "-h +8" "the normal end: the grace time"

echo "a failing cpp-build stops the run at the gate:"
STUB_BUILD_RC=4 run_boot --suite cpp
assert_eq "$RC" 1 "exit 1"
assert_contains "$SUMMARY" "cpp-build: FAIL (4)" "summary names the failing step"
assert_contains "$SUMMARY" "gate: STOP after the failure of cpp-build" "and the gate"
assert_contains "$SUMMARY" "cpp-cuda-test: SKIP" "the CUDA test could not run"
assert_not_contains "$LOG" "gradlew" "no Gradle call after the gate"
assert_not_contains "$LOG" "setup-tornado" "no TornadoVM setup after the gate"
assert_not_contains "$LOG" "compute-sanitizer" "no sanitizer after the gate"
assert_contains "$(cat "$T/shutdown.log")" "-h +2" "shutdown scheduled in 2 minutes (not the 8 of a normal end)"
[ -f "$W/results.tgz" ] && pass "results.tgz was written" || fail "no results.tgz"
[ -f "$W/DONE" ] && pass "DONE marker written (the caller fetches at once)" || fail "no DONE"

echo "a failing cuda_test stops the run at the gate:"
STUB_CUDA_TEST_RC=1 run_boot --suite cpp
assert_eq "$RC" 1 "exit 1"
assert_contains "$SUMMARY" "cpp-cuda-test: FAIL (1)" "summary names cpp-cuda-test"
assert_contains "$SUMMARY" "gate: STOP after the failure of cpp-cuda-test" "gate"
assert_not_contains "$LOG" "gradlew" "no Gradle call"
assert_not_contains "$LOG" "compute-sanitizer" "no sanitizer"

echo "no nvcc: the toolchain step fails, the gate stops, the build is not tried:"
rm -f "$BIN/nvcc"
run_boot --suite cpp
assert_eq "$RC" 1 "exit 1"
assert_contains "$SUMMARY" "cpp-toolchain: FAIL" "toolchain failed"
assert_contains "$SUMMARY" "cpp-build: SKIP" "build skipped"
assert_contains "$SUMMARY" "gate: STOP after the failure of cpp-toolchain" "gate"
assert_not_contains "$LOG" "build-cuda" "build-cuda.sh was not called"
assert_contains "$(cat "$W/results/nvcc-version.txt")" "not found" "nvcc-version.txt says so"
mkstub nvcc 'case "$*" in *--list-gpu-arch*) printf "compute_75\n" ;; *) echo "nvcc: stub release 13.0" ;; esac'

echo "no compute-sanitizer: SKIP, the run goes on:"
rm -f "$BIN/compute-sanitizer"
run_boot --suite cpp
assert_eq "$RC" 0 "exit 0"
assert_contains "$SUMMARY" "cpp-sanitizer: SKIP (compute-sanitizer not found" "SKIP with the reason"
assert_contains "$SUMMARY" "gpufeed-large: OK" "later steps ran"
assert_contains "$(cat "$W/results/compute-sanitizer.log")" "not found" "compute-sanitizer.log names the reason"
mkstub compute-sanitizer '[ "$1" = --version ] && { echo "NVIDIA (R) Compute Sanitizer stub"; exit 0; }; echo "compute-sanitizer $*" >> "$STUB_LOG"; exit "${STUB_SANITIZER_RC:-0}"'

echo "compute-sanitizer reports errors: FAIL, the run goes on:"
STUB_SANITIZER_RC=1 run_boot --suite cpp
assert_contains "$SUMMARY" "cpp-sanitizer: FAIL (1)" "FAIL"
assert_contains "$SUMMARY" "gate: passed" "the gate had passed"
assert_contains "$SUMMARY" "gpufeed-large: OK" "later steps ran"
assert_eq "$RC" 1 "the exit status is 1 (a step failed)"

echo "--cold-rounds:"
run_boot --suite cpp --cold-rounds 1
assert_eq "$RC" 0 "exit 0 with one cold round"
assert_eq "$(printf '%s\n' "$LOG" | grep -c 'hardened/nn_bench_l8 .*--cold')" 12 "one round: 12 cold processes of C++"
assert_eq "$(printf '%s\n' "$LOG" | grep -c 'java-cross .*--cold')" 8 "one round: 8 cold processes of Java"
run_boot --suite cpp --cold-rounds 0
assert_eq "$RC" 2 "--cold-rounds 0 is a usage error (exit 2)"
run_boot --suite cpp --cold-rounds x
assert_eq "$RC" 2 "--cold-rounds x is a usage error (exit 2)"

echo "real weights:"
head -c 3000 /dev/urandom > "$T/weights.bin"
SHA=$(perl -MDigest::SHA -e 'print Digest::SHA->new(256)->addfile($ARGV[0])->hexdigest' "$T/weights.bin")
run_boot_w() { W=$T/work; rm -rf "$W"; mkdir -p "$W"; cp "$T/src.tar.gz" "$W/"; cp "$1" "$W/real-weights.safetensors"; shift; run_boot_keep "$@"; }
run_boot_keep() {   # like run_boot but keeps the workdir that run_boot_w prepared
  : > "$STUB_LOG"; : > "$T/shutdown.log"; RC=0
  env PATH="$BIN:$PATH" JDK_HOME="$T/jdk" T_SHUTDOWN="$T/shutdown.log" STUB_LOG="$STUB_LOG" STUB_JAVA="$STUB_JAVA" \
    bash "$SRC_CLOUD/bootstrap.sh" --backend cuda --hard-minutes 45 --workdir "$W" --feed-seconds 30 "$@" > "$T/boot.out" 2>&1 || RC=$?
  SUMMARY=$(cat "$W/results/summary.txt" 2> /dev/null); LOG=$(cat "$STUB_LOG")
}
run_boot_w "$T/weights.bin" --suite cpp --real-weights-sha256 "$SHA"
assert_eq "$RC" 0 "exit 0"
assert_contains "$SUMMARY" "real-weights: OK" "the hash check passed"
assert_contains "$LOG" "-Psternhalma.golden.real=true" "the golden export is told to use the real weights"
assert_contains "$LOG" "data dir: $W/real-data" "STERNHALMA_DATA_DIR points to the copy on the instance"
cmp -s "$T/weights.bin" "$W/real-data/loop4/gen-0011/candidate.safetensors" && pass "the file is where the exporter reads it" || fail "file not in place"
assert_contains "$LOG" "nn_check_l8 --golden $W/src/cpp/golden --impl cuda --preset gen11" "nn_check cuda on gen11"
assert_contains "$LOG" "--impl simd-nofma --preset gen11" "and simd-nofma on gen11"
rw=$(cat "$W/results/real-weights.txt")
assert_eq "$(printf '%s\n' "$rw" | grep -c "$SHA")" 3 "real-weights.txt: the hash before the upload, after the upload and after the run"
assert_not_contains "$rw" "weights.bin" "and no file name"
assert_not_contains "$(ls -R "$W/results")" "candidate" "the weights are not in the results directory"
assert_contains "$(cat "$W/results/golden-sha256.txt")" "nn-ref-large-gen11" "the gen11 reference hash is recorded"

echo "real weights with a wrong hash: not used, the run goes on:"
run_boot_w "$T/weights.bin" --suite cpp --real-weights-sha256 "$(printf '%064d' 7)"
assert_contains "$SUMMARY" "real-weights: FAIL" "the check failed"
assert_not_contains "$LOG" "golden.real=true" "the weights are not used"
assert_not_contains "$LOG" "--preset gen11" "no gen11 check"
assert_contains "$SUMMARY" "gpufeed-large: OK" "the run went on"
[ ! -e "$W/real-data" ] && pass "nothing was put in place" || fail "real-data exists"

echo "arguments:"
for bad in "--suite cpp --real-weights-sha256 abc" "--suite other" "--real-weights-sha256 $SHA"; do
  run_boot $bad
  assert_eq "$RC" 2 "refused: $bad"
done
RC=0; env PATH="$BIN:$PATH" T_SHUTDOWN="$T/shutdown.log" bash "$SRC_CLOUD/bootstrap.sh" --backend none --suite cpp --hard-minutes 10 --workdir "$T/w2" > /dev/null 2>&1 || RC=$?
assert_eq "$RC" 2 "--suite cpp needs --backend cuda"

echo "suite default is unchanged (order of the earlier runs):"
run_boot
assert_contains "$SUMMARY" "suite: default" "suite default"
assert_eq "$(steps_of)" "backend suite seconds apt-packages tornado-setup tornado-devices build vector-tests vector-benchmark-default device-tests gpufeed-small gpufeed-large " "default: apt, TornadoVM, build, Vector API, device tests, gpuFeed"
assert_not_contains "$LOG" "build-cuda" "no C++ build in the default suite"
finish_test
