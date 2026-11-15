#!/usr/bin/env bash
# Runs ON a fresh Ubuntu 22.04/24.04 x86_64 instance (as root): installs JDK 25 and the TornadoVM SDK, builds the project from a
# source tarball, runs the device tests, the Vector API tests and benchmarks and the gpuFeed rows, writes a results
# directory, and ALWAYS schedules the shutdown of the machine, also on failure (trap EXIT).
#
# usage: bootstrap.sh --backend cuda|opencl|none --hard-minutes N [--workdir DIR] [--grace-minutes N]
#                     [--feed-seconds N] [--install-nvidia 0|1] [--suite default|cpp] [--real-weights-sha256 HEX]
#   --backend        TornadoVM backend to test; none = CPU only (Vector API tests and benchmarks)
#   --hard-minutes   hard timer: "shutdown -h +N" is the first thing this script does; its deadline is stored in
#                    <workdir>/HARD_DEADLINE and the final shutdown never lies beyond it
#   --workdir        default /var/tmp/sh; expects <workdir>/src.tar.gz (the source tarball), writes <workdir>/results/
#   --grace-minutes  after the run (success or failure) the machine shuts down in this many minutes, so that the
#                    caller can fetch results (default 8), or at the hard deadline if that is sooner: a second
#                    "shutdown -h +N" REPLACES the first schedule, so the script computes min(remaining, N) itself
#   --feed-seconds   length of each gpuFeed row (default 60)
#   --install-nvidia 1 = if no NVIDIA driver is present, try "ubuntu-drivers install --gpgpu" (NOT validated: use a
#                    Deep Learning AMI instead, docs/cloud.md); default 0 = fail with a message
#   --suite          default = the earlier runs (Vector API tests and benchmarks, device tests, gpuFeed rows; unchanged);
#                    cpp = the C++ comparison (needs --backend cuda): g++-12 and nvcc, C++ build, CUDA correctness test,
#                    GATE (a failure stops the run at once), compute-sanitizer, then JDK/TornadoVM/Gradle, golden export and
#                    hash check, nn_check, anchors, nn_bench, crossBench, anchors, gpuFeed rows (docs/cpp-comparison-plan.md, WP14)
#   --real-weights-sha256 HEX
#                    cpp suite only: <workdir>/real-weights.safetensors was uploaded by aws-spot.sh; HEX is its SHA-256 from
#                    before the upload. The file is checked against it, used for the gen11 golden export and the gen11 equality
#                    check, and its hash is written to results/real-weights.txt (the file itself never leaves the instance)
#
# Why bash and not zsh: it runs on a machine we do not control, where only bash is certain (shellcheck covers it).
# Exit codes: 0 all steps ran and every test passed, 1 a step failed (the run goes on; see results/summary.txt; in the cpp suite
# a failed C++ build or CUDA test stops the run), 2 usage, 3 a prerequisite is missing (no NVIDIA driver, no NVRTC),
# 4 a download did not verify.
# shellcheck disable=SC2329  # functions run through trap and step "$@"
set -uo pipefail

# record_hard_deadline MINUTES FILE: stores the epoch second at which the hard timer fires.
record_hard_deadline() { echo $(( $(date +%s) + $1 * 60 )) > "$2"; }

# schedule_final_shutdown FILE GRACE_MINUTES: the shutdown after the run is min(remaining time of the hard deadline, GRACE).
# "shutdown -h +N" replaces an earlier schedule (systemd), so a plain "+GRACE" could move the end of the machine
# BEHIND the hard deadline. If the deadline is within the grace time the hard timer is left alone (it is the sooner one).
schedule_final_shutdown() {
  local file=$1 grace=$2 deadline now rem
  deadline=$(cat "$file" 2>/dev/null || true)
  case $deadline in ''|*[!0-9]*) echo "no hard deadline recorded: shutting down in $grace minutes"; shutdown -h "+$grace" "sternhalma bootstrap: finished"; return ;; esac
  now=$(date +%s)
  rem=$(( (deadline - now + 59) / 60 ))   # minutes, rounded up
  if [ "$rem" -le 0 ]; then
    echo "hard deadline reached: shutting down now"; shutdown -h now
  elif [ "$rem" -le "$grace" ]; then
    echo "hard timer fires in $rem minutes, sooner than the grace time of $grace: left in place"
  else
    echo "shutting down in $grace minutes (hard deadline in $rem)"; shutdown -h "+$grace" "sternhalma bootstrap: finished"
  fi
}

find_nvrtc() {
  ldconfig -p 2>/dev/null | perl -ne 'print "$1\n" if m{=>\s*(\S*libnvrtc\.so\S*)}' | head -1
  find /usr/local/cuda* /usr/lib/x86_64-linux-gnu /opt -name 'libnvrtc.so*' 2>/dev/null | head -1
}

# find_nvcc [ROOT]: the nvcc of the newest toolkit directory ROOT/cuda-X.Y (default ROOT /usr/local), else ROOT/cuda/bin/nvcc;
# prints nothing if there is none. Several toolkits can be installed side by side on the Deep Learning AMI.
find_nvcc() {
  local root=${1:-/usr/local} best
  best=$(for c in "$root"/cuda-*/bin/nvcc; do [ -x "$c" ] && echo "$c"; done | perl -e '
    # input line: /usr/local/cuda-13.0/bin/nvcc  ->  sorts by the numeric parts of the version
    my @f = map { chomp; $_ } <STDIN>;
    sub ver { my @v = $_[0] =~ m{/cuda-(\d+(?:\.\d+)*)/} ? split(/\./, $1) : (0); @v }
    @f = sort { my @a = ver($a); my @b = ver($b); my $c = 0;
                for my $i (0 .. ($#a > $#b ? $#a : $#b)) { $c = ($a[$i] // 0) <=> ($b[$i] // 0) and last } $c } @f;
    print $f[-1] if @f;')
  if [ -n "$best" ]; then echo "$best"; elif [ -x "$root/cuda/bin/nvcc" ]; then echo "$root/cuda/bin/nvcc"; fi
}

# sha256_of FILE: the hex digest (sha256sum on the instance)
sha256_of() { sha256sum "$1" | cut -d' ' -f1; }

# sourced (tests): only the functions above
[ "${BASH_SOURCE[0]}" = "$0" ] || return 0

# --- pinned downloads (verified 2026-11-14: fetched with curl, SHA-256 computed; docs/cloud.md) -----------------------
JDK_URL=https://cdn.azul.com/zulu/bin/zulu25.34.17-ca-jdk25.0.3-linux_x64.tar.gz
JDK_SHA256=3a93235f4e5b313232647916219e91dc5cca0b9835c9b52a404ba58c43571697

BACKEND=""
HARD_MIN=""
WORKDIR=/var/tmp/sh
GRACE_MIN=8
FEED_SECONDS=60
INSTALL_NVIDIA=0
SUITE=default
REAL_SHA=

usage() { sed -n '2,25p' "$0" >&2; exit 2; }

while [ $# -gt 0 ]; do
  case $1 in
    --backend) BACKEND=${2:-}; shift 2 ;;
    --hard-minutes) HARD_MIN=${2:-}; shift 2 ;;
    --workdir) WORKDIR=${2:-}; shift 2 ;;
    --grace-minutes) GRACE_MIN=${2:-}; shift 2 ;;
    --feed-seconds) FEED_SECONDS=${2:-}; shift 2 ;;
    --install-nvidia) INSTALL_NVIDIA=${2:-}; shift 2 ;;
    --suite) SUITE=${2:-}; shift 2 ;;
    --real-weights-sha256) REAL_SHA=${2:-}; shift 2 ;;
    *) usage ;;
  esac
done
case $BACKEND in cuda|opencl|none) ;; *) usage ;; esac
case $SUITE in default) ;; cpp) [ "$BACKEND" = cuda ] || usage ;; *) usage ;; esac
case $REAL_SHA in '') ;; *) [ "$SUITE" = cpp ] && [ "${#REAL_SHA}" = 64 ] && [ -z "${REAL_SHA//[0-9a-f]/}" ] || usage ;; esac
case $HARD_MIN in ''|*[!0-9]*) usage ;; esac
case $GRACE_MIN in ''|*[!0-9]*) usage ;; esac
case $FEED_SECONDS in ''|*[!0-9]*) usage ;; esac
HARD_MIN=$((10#$HARD_MIN)); GRACE_MIN=$((10#$GRACE_MIN)); FEED_SECONDS=$((10#$FEED_SECONDS))
[ "$HARD_MIN" -ge 5 ] || usage

# 1. The hard timer first: from here on the machine ends within HARD_MIN minutes whatever happens below.
RESULTS=$WORKDIR/results
SRC=$WORKDIR/src
HARD_FILE=$WORKDIR/HARD_DEADLINE
mkdir -p "$RESULTS"
record_hard_deadline "$HARD_MIN" "$HARD_FILE"
shutdown -h "+$HARD_MIN" "sternhalma bootstrap: hard timer" 2>/dev/null || true
exec > >(tee -a "$RESULTS/bootstrap.log") 2>&1

START=$(date +%s)
STATUS=0
declare -a STEPS=()

stamp() { printf '[%s +%ss] ' "$(date -u +%H:%M:%S)" "$(( $(date +%s) - START ))"; }
say() { stamp; echo "$*"; }

# 2. Always shut down: write the summary and schedule the shutdown, on any exit.
finish() {
  code=$?
  [ "$code" -ne 0 ] && STATUS=$code
  {
    echo "backend: $BACKEND"
    echo "suite: $SUITE"
    echo "exit status: $STATUS"
    echo "seconds: $(( $(date +%s) - START ))"
    printf '%s\n' "${STEPS[@]}"
  } > "$RESULTS/summary.txt"
  tar -czf "$WORKDIR/results.tgz" -C "$WORKDIR" results 2>/dev/null
  echo "$STATUS" > "$WORKDIR/DONE"
  say "done (status $STATUS)"
  schedule_final_shutdown "$HARD_FILE" "$GRACE_MIN" 2>&1 || shutdown -h now
}
trap finish EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

# step NAME COMMAND...: runs a command, logs its output to results/NAME.log, records OK/FAIL, never aborts the run.
step() {
  name=$1; shift
  say "step $name: $*"
  if "$@" > "$RESULTS/$name.log" 2>&1; then
    STEPS+=("$name: OK")
  else
    rc=$?
    STEPS+=("$name: FAIL ($rc)")
    STATUS=1
    say "step $name FAILED ($rc); last lines:"
    tail -n 15 "$RESULTS/$name.log"
  fi
}

# --- machine facts -------------------------------------------------------------------------------------------------
{
  uname -a
  lscpu | grep -E 'Model name|^CPU\(s\)|Flags' | perl -pe 's/(Flags:\s+).*/$1(avx lines below)/'
  grep -o -w -E 'avx|avx2|fma|avx512f|avx512bw|avx512vl|avx512_vnni|amx_tile' /proc/cpuinfo | sort -u | tr '\n' ' '
  echo
  free -m | head -2
  nvidia-smi 2>&1 | head -15 || true
} > "$RESULTS/system.txt" 2>&1
cat "$RESULTS/system.txt"

# --- prerequisites ------------------------------------------------------------------------------------------------
export DEBIAN_FRONTEND=noninteractive
apt_retry() {
  for i in 1 2 3 4 5 6; do
    apt-get "$@" && return 0
    say "apt-get $1 failed (attempt $i), waiting for the lock"; sleep 10
  done
  return 1
}
apt_base() { apt_retry update -qq && apt_retry install -y -qq zsh curl ca-certificates tar gzip perl python3 unzip; }
step apt-packages apt_base

# =====================================================================================================================
# The setup blocks, as functions so that the two suites can run them in different orders. The "default" suite runs them
# in the order of the earlier runs: JDK, source, NVIDIA/NVRTC, TornadoVM. The "cpp" suite needs the source and the GPU
# driver first and the JDK only after the gate.
# =====================================================================================================================

# --- JDK 25 --------------------------------------------------------------------------------------------------------
JDK_HOME=${JDK_HOME:-/opt/jdk25}
setup_jdk() {
  if [ ! -x "$JDK_HOME/bin/java" ]; then
    say "download JDK: $JDK_URL"
    mkdir -p "$WORKDIR/dl" "$JDK_HOME"
    curl -fL --retry 3 --retry-delay 2 -sS -o "$WORKDIR/dl/jdk.tar.gz" "$JDK_URL" || { say "JDK download failed"; exit 4; }
    actual=$(sha256sum "$WORKDIR/dl/jdk.tar.gz" | cut -d' ' -f1)
    if [ "$actual" != "$JDK_SHA256" ]; then
      say "JDK SHA-256 mismatch: expected $JDK_SHA256, got $actual"; exit 4
    fi
    tar -xzf "$WORKDIR/dl/jdk.tar.gz" -C "$JDK_HOME" --strip-components=1
    rm -f "$WORKDIR/dl/jdk.tar.gz"
  fi
  export JAVA_HOME=$JDK_HOME
  export PATH=$JAVA_HOME/bin:$PATH
  java -version 2>&1 | tee "$RESULTS/java-version.txt"
}

# --- source --------------------------------------------------------------------------------------------------------
setup_source() {
  [ -f "$WORKDIR/src.tar.gz" ] || { say "missing $WORKDIR/src.tar.gz"; exit 3; }
  mkdir -p "$SRC"
  tar -xzf "$WORKDIR/src.tar.gz" -C "$SRC" || exit 3
  cd "$SRC" || exit 3
}

# --- NVIDIA driver and NVRTC (cuda backend only) -------------------------------------------------------------------
apt_nvidia() {
  apt_retry install -y -qq ubuntu-drivers-common "linux-headers-$(uname -r)" && ubuntu-drivers install --gpgpu && modprobe nvidia
}
setup_nvidia() {
  [ "$BACKEND" = cuda ] || return 0
  if ! nvidia-smi -L > "$RESULTS/nvidia-smi-L.txt" 2>&1; then
    if [ "$INSTALL_NVIDIA" = 1 ]; then
      say "no NVIDIA driver: trying ubuntu-drivers (not validated)"
      step nvidia-driver apt_nvidia
    fi
    if ! nvidia-smi -L > "$RESULTS/nvidia-smi-L.txt" 2>&1; then
      say "no working NVIDIA driver (nvidia-smi -L failed). Use a Deep Learning AMI (docs/cloud.md)."; exit 3
    fi
  fi
  cat "$RESULTS/nvidia-smi-L.txt"
  NVRTC=$(find_nvrtc | head -1)
  if [ -z "$NVRTC" ]; then
    say "no libnvrtc.so on this machine: the CUDA backend of TornadoVM compiles kernels with NVRTC (CUDA Toolkit)."; exit 3
  fi
  say "NVRTC: $NVRTC"
  NVRTC_DIR=$(dirname "$(readlink -f "$NVRTC")")
  export LD_LIBRARY_PATH=$NVRTC_DIR${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}
  if [ -d /usr/local/cuda ]; then export CUDA_PATH=/usr/local/cuda; fi
}

# --- TornadoVM SDK -------------------------------------------------------------------------------------------------
setup_tornado() {
  [ "$BACKEND" != none ] || return 0
  step tornado-setup env DEVENV_ROOT="$SRC" zsh "$SRC/scripts/setup-tornado.sh" "$BACKEND"
  export TORNADOVM_HOME=$SRC/tornado-sdk/current
  export PATH=$TORNADOVM_HOME/bin:$PATH
  export TORNADO_BACKEND=$BACKEND
  step tornado-devices tornado --devices
}

# --- build ---------------------------------------------------------------------------------------------------------
GRADLE=(./gradlew --no-build-cache --max-workers=3 --console=plain)

# skip NAME REASON: a step that was not run, recorded as SKIP (not an error)
skip() {
  STEPS+=("$1: SKIP ($2)")
  say "step $1 skipped: $2"
  echo "$2" > "$RESULTS/$1.log"
}

# step_failed NAME: true if the step ended with FAIL
step_failed() { printf '%s\n' "${STEPS[@]}" | grep -q "^$1: FAIL"; }

# =====================================================================================================================
# suite default: the earlier runs, unchanged
# =====================================================================================================================
run_default_suite() {
  setup_jdk
  setup_source
  setup_nvidia
  setup_tornado

  step build "${GRADLE[@]}" :nn:testClasses :nn-tornado:testClasses

  # --- Vector API: tests, then benchmarks ----------------------------------------------------------------------------
  step vector-tests "${GRADLE[@]}" --continue :nn:test --tests 'sternhalma.nn.VectorSpeciesTest' \
    --tests 'sternhalma.nn.VectorNetworkTest' --tests 'sternhalma.nn.VectorSupportTest'
  mkdir -p "$RESULTS/xml"
  cp nn/build/test-results/test/*.xml "$RESULTS/xml/" 2>/dev/null || true
  step vector-benchmark-default "${GRADLE[@]}" :nn:vectorBenchmark -PvectorSeconds=2
  if grep -q -w avx512f /proc/cpuinfo; then
    step vector-benchmark-avx2 "${GRADLE[@]}" :nn:vectorBenchmark -PvectorSeconds=2 -PvectorJvmArgs="-XX:UseAVX=2"
    step vector-benchmark-avx512-512bit "${GRADLE[@]}" :nn:vectorBenchmark -PvectorSeconds=2 -PvectorJvmArgs="-XX:UseAVX=3 -XX:MaxVectorSize=64"
  fi

  # --- device tests and gpuFeed --------------------------------------------------------------------------------------
  if [ "$BACKEND" != none ]; then
    TESTS=(TornadoSupportTest GenericPathTest DeviceMathTest DeviceExpLogTest DeviceFiniteTest DeviceDoubleTest
      KernelsHexConvTest KernelsDenseTest OpsKernelsTest OpsHeadsTest BatchedMatMulTest DeviceOptimizerTest
      TornadoNetworkTest TornadoTrainerTest)
    ARGS=()
    for t in "${TESTS[@]}"; do ARGS+=(--tests "sternhalma.tornado.$t"); done
    rm -rf nn-tornado/build/test-results
    step device-tests timeout 1500 "${GRADLE[@]}" --continue :nn-tornado:test "${ARGS[@]}"
    mkdir -p "$RESULTS/xml-device"
    cp nn-tornado/build/test-results/test/*.xml "$RESULTS/xml-device/" 2>/dev/null || true
    # one line per class: tests, skipped, failures, errors
    perl -ne 'print "$1 tests=$2 skipped=$3 failures=$4 errors=$5\n" if /<testsuite name="([^"]+)" tests="(\d+)" skipped="(\d+)" failures="(\d+)" errors="(\d+)"/' \
      "$RESULTS"/xml-device/*.xml > "$RESULTS/device-tests-per-class.txt" 2>/dev/null || true
    if perl -ne '$bad++ if /(failures|errors)=[1-9]/; END { exit($bad ? 0 : 1) }' "$RESULTS/device-tests-per-class.txt"; then
      STATUS=1; STEPS+=("device-tests: failing classes, see device-tests-per-class.txt")
    fi
    THREADS=$(nproc)
    for size in small large; do
      step "gpufeed-$size" timeout 600 "${GRADLE[@]}" :nn-tornado:gpuFeed \
        --args="--size $size --batches 256 --seconds $FEED_SECONDS --threads $THREADS --cohorts 2"
    done
  fi
}

# =====================================================================================================================
# suite cpp (docs/cpp-comparison-plan.md, WP14 item 1): the risky C++ steps first, then the gate
# =====================================================================================================================
CPP_BUILD_DIR=cpp/build/cuda       # relative to $SRC: the default output of build-cuda.sh
GOLDEN_DIR_REL=cpp/golden
REAL_OK=0
NVCC=""
SANITIZER=""

# cpp-toolchain: g++-12 from apt, nvcc, compute-sanitizer and nvidia-smi found and recorded
cpp_toolchain() {
  local status=0 f
  apt_retry install -y -qq g++-12 || status=1
  NVCC=$(find_nvcc /usr/local)
  [ -n "$NVCC" ] || NVCC=$(command -v nvcc || true)
  SANITIZER=$(command -v compute-sanitizer || true)
  if [ -z "$SANITIZER" ] && [ -n "$NVCC" ]; then
    f=$(dirname "$NVCC")/compute-sanitizer
    [ -x "$f" ] && SANITIZER=$f
  fi
  if [ -z "$SANITIZER" ]; then
    for f in /usr/local/cuda*/bin/compute-sanitizer; do [ -x "$f" ] && SANITIZER=$f; done
  fi
  {
    if [ -n "$NVCC" ]; then "$NVCC" --version; else echo "nvcc: not found"; fi
  } > "$RESULTS/nvcc-version.txt" 2>&1
  {
    echo "g++-12: $(command -v g++-12 || echo 'not found')"
    g++-12 --version 2>&1 | head -1
    echo "nvcc: ${NVCC:-not found}"
    [ -z "$NVCC" ] || "$NVCC" --list-gpu-arch 2>&1 | tr '\n' ' '
    echo
    echo "compute-sanitizer: ${SANITIZER:-not found}"
    [ -z "$SANITIZER" ] || "$SANITIZER" --version 2>&1 | tail -1
    echo "nvidia-smi: $(command -v nvidia-smi || echo 'not found')"
    nvidia-smi --query-gpu=name,driver_version --format=csv,noheader 2>&1 | head -1
  } > "$RESULTS/toolchain.txt" 2>&1
  cat "$RESULTS/toolchain.txt"
  [ -n "$NVCC" ] || { echo "no nvcc on this machine"; status=1; }
  return "$status"
}

cpp_build() { zsh "$SRC/cpp/scripts/build-cuda.sh" --nvcc "$NVCC" --cxx g++-12 --out "$SRC/$CPP_BUILD_DIR" --jobs "$(nproc)"; }
cpp_cuda_test() { "$SRC/$CPP_BUILD_DIR/hardened/bin/cuda_test"; }

# gate_cpp: the first three steps decide whether the paid time goes on. A failure ends the run here: summary.txt names the step,
# the instance is shut down after 2 minutes (aws-spot.sh fetches the results at once and shuts it down earlier).
gate_cpp() {
  local name
  for name in cpp-toolchain cpp-build cpp-cuda-test; do
    if step_failed "$name"; then
      STEPS+=("gate: STOP after the failure of $name; nothing after it was run")
      say "GATE: $name failed: stopping the run and shutting down"
      GRACE_MIN=2
      exit 1
    fi
  done
  STEPS+=("gate: passed")
}

cpp_golden_hash() {
  local f="$SRC/cpp/expected-golden-sha256.tsv" status=0 name sha kind actual out="$RESULTS/golden-sha256.txt"
  [ -f "$f" ] || { echo "missing cpp/expected-golden-sha256.tsv"; return 1; }
  : > "$out"
  # line: <file><TAB><sha256><TAB>must|info   (must: weights and inputs; info: reference outputs of the JVM, recorded only)
  while IFS=$'\t' read -r name sha kind; do
    case $name in ''|'#'*) continue ;; esac
    if [ ! -f "$SRC/$GOLDEN_DIR_REL/$name" ]; then
      printf '%s\tMISSING\t%s\n' "$name" "$kind" >> "$out"; [ "$kind" = must ] && status=1; continue
    fi
    actual=$(sha256_of "$SRC/$GOLDEN_DIR_REL/$name")
    if [ "$actual" = "$sha" ]; then printf '%s\t%s\tequal\t%s\n' "$name" "$actual" "$kind" >> "$out"
    else printf '%s\t%s\tDIFFERENT\t%s\n' "$name" "$actual" "$kind" >> "$out"; [ "$kind" = must ] && status=1; fi
  done < "$f"
  # the gen11 reference output (real weights): its hash is recorded, nothing to compare with
  if [ -f "$SRC/$GOLDEN_DIR_REL/nn-ref-large-gen11.safetensors" ]; then
    printf 'nn-ref-large-gen11.safetensors\t%s\trecorded\tinfo\n' "$(sha256_of "$SRC/$GOLDEN_DIR_REL/nn-ref-large-gen11.safetensors")" >> "$out"
  fi
  cat "$out"
  return "$status"
}

# real-weights: the uploaded file against the hash from before the upload; then in place for the exporter (STERNHALMA_DATA_DIR)
cpp_real_weights() {
  local f=$WORKDIR/real-weights.safetensors actual
  [ -f "$f" ] || { echo "no uploaded file $f"; return 1; }
  actual=$(sha256_of "$f")
  {
    echo "sha256 before the upload (on the machine that sent it): $REAL_SHA"
    echo "sha256 after the upload (on this instance):             $actual"
  } > "$RESULTS/real-weights.txt"
  cat "$RESULTS/real-weights.txt"
  [ "$actual" = "$REAL_SHA" ] || { echo "the hashes differ: the file is not used"; return 1; }
  mkdir -p "$WORKDIR/real-data/loop4/gen-0011" && cp "$f" "$WORKDIR/real-data/loop4/gen-0011/candidate.safetensors"
}

cpp_golden_export() {
  local args=("-Psternhalma.golden.out=$SRC/$GOLDEN_DIR_REL")
  if [ "$REAL_OK" = 1 ]; then args+=("-Psternhalma.golden.real=true"); fi
  if [ "$REAL_OK" = 1 ]; then
    STERNHALMA_DATA_DIR="$WORKDIR/real-data" "${GRADLE[@]}" :nn:goldenExport "${args[@]}"
  else
    "${GRADLE[@]}" :nn:goldenExport "${args[@]}"
  fi
}

# the lane count of the SIMD objects follows the CPU: 16 lanes with AVX-512, else 8
cpp_bin_l() { if grep -q -w avx512f /proc/cpuinfo 2>/dev/null; then echo l16; else echo l8; fi; }

cpp_check() {
  local bin="$SRC/$CPP_BUILD_DIR/hardened/bin" status=0 impl preset presets=(small large)
  [ "$REAL_OK" = 0 ] || presets+=(gen11)
  export STERNHALMA_GOLDEN_DIR="$SRC/$GOLDEN_DIR_REL" STERNHALMA_GOLDEN_REQUIRED=1
  [ "$REAL_OK" = 0 ] || export STERNHALMA_DATA_DIR="$WORKDIR/real-data"
  for impl in cuda simd simd-nofma; do
    for preset in "${presets[@]}"; do
      echo "== nn_check_l8 --impl $impl --preset $preset"
      "$bin/nn_check_l8" --golden "$SRC/$GOLDEN_DIR_REL" --impl "$impl" --preset "$preset" || status=1
    done
  done
  if [ "$(cpp_bin_l)" = l16 ]; then      # the second lane build (AVX-512)
    for preset in "${presets[@]}"; do
      echo "== nn_check_l16 --impl simd --preset $preset"
      "$bin/nn_check_l16" --golden "$SRC/$GOLDEN_DIR_REL" --impl simd --preset "$preset" || status=1
    done
  fi
  echo "lane build of the anchors: $(cpp_bin_l)"
  return "$status"
}

# nvidia-smi line with a label, appended to results/nvidia-smi.csv
smi_line() {
  [ -f "$RESULTS/nvidia-smi.csv" ] || echo "label,clocks.sm,clocks.mem,temperature.gpu,utilization.gpu,power.draw" > "$RESULTS/nvidia-smi.csv"
  echo "$1,$(nvidia-smi --query-gpu=clocks.sm,clocks.mem,temperature.gpu,utilization.gpu,power.draw --format=csv,noheader 2>&1 | head -1)" >> "$RESULTS/nvidia-smi.csv"
}

# java_cross ARGS...: the Java side with the command line that printRunCommand wrote
java_cross() { bash -c "$(cat "$SRC/cpp/build/java-nn.cmd") \"\$@\"" crossbench "$@"; }

# anchors LABEL: C++, Java, C++, Java (the plan's order); stdout of the programs (TSV) goes to anchors.tsv
cpp_anchors() {
  local label=$1 bin="$SRC/$CPP_BUILD_DIR/hardened/bin" lanes round status=0
  lanes=$(cpp_bin_l)
  smi_line "$label"
  for round in 1 2; do
    echo "# $label round $round: C++" >> "$RESULTS/anchors.tsv"
    "$bin/nn_bench_$lanes" --golden "$SRC/$GOLDEN_DIR_REL" --anchors --impls cuda-pinned,simd >> "$RESULTS/anchors.tsv" || status=1
    echo "# $label round $round: Java" >> "$RESULTS/anchors.tsv"
    java_cross --golden "$SRC/$GOLDEN_DIR_REL" --anchors --impls tornado,vector >> "$RESULTS/anchors.tsv" || status=1
  done
  return "$status"
}

cpp_bench() {
  local hard="$SRC/$CPP_BUILD_DIR/hardened/bin" unchecked="$SRC/$CPP_BUILD_DIR/unchecked/bin" status=0 lanes out="$RESULTS/nn-bench.tsv"
  local g=(--golden "$SRC/$GOLDEN_DIR_REL")
  smi_line cpp-bench
  for lanes in l8 l16; do
    echo "# hardened, $lanes: SMALL and LARGE" >> "$out"
    "$hard/nn_bench_$lanes" "${g[@]}" --presets small,large --batches 1,16,64,256,512,1024 \
      --impls simd,simd-nofma,cuda-pageable,cuda-pinned >> "$out" || status=1
  done
  echo "# hardened, kernels (LARGE)" >> "$out"
  "$hard/nn_bench_l8" "${g[@]}" --presets large --batches 1,64,512 --kernels >> "$out" || status=1
  echo "# hardened, cold start" >> "$out"
  "$hard/nn_bench_l8" "${g[@]}" --presets small,large --cold >> "$out" || status=1
  for lanes in l8 l16; do
    echo "# unchecked, $lanes: batch 256" >> "$out"
    "$unchecked/nn_bench_$lanes" "${g[@]}" --presets small,large --batches 256 \
      --impls simd,simd-nofma,cuda-pageable,cuda-pinned >> "$out" || status=1
  done
  return "$status"
}

java_crossbench() {
  local status=0 out="$RESULTS/crossbench.tsv"
  smi_line java-crossbench
  echo "# vector and tornado" >> "$out"
  java_cross --golden "$SRC/$GOLDEN_DIR_REL" --presets small,large --batches 1,16,64,256,512,1024 --impls vector,tornado >> "$out" || status=1
  echo "# cold start" >> "$out"
  java_cross --golden "$SRC/$GOLDEN_DIR_REL" --presets small,large --cold --impls vector,tornado >> "$out" || status=1
  return "$status"
}

cpp_gpufeed() {
  local size THREADS
  THREADS=$(nproc)
  for size in small large; do
    step "gpufeed-$size" timeout 600 "${GRADLE[@]}" :nn-tornado:gpuFeed \
      --args="--size $size --batches 256 --seconds $FEED_SECONDS --threads $THREADS --cohorts 2"
  done
}

run_cpp_suite() {
  setup_source
  setup_nvidia
  step cpp-toolchain cpp_toolchain
  # the build and the test can only run if the toolchain did; a failed toolchain step is a failed gate
  if step_failed cpp-toolchain; then STEPS+=("cpp-build: SKIP (no toolchain)" "cpp-cuda-test: SKIP (no toolchain)")
  else
    step cpp-build cpp_build
    if step_failed cpp-build; then STEPS+=("cpp-cuda-test: SKIP (no build)"); else step cpp-cuda-test cpp_cuda_test; fi
  fi
  gate_cpp
  if [ -z "$SANITIZER" ]; then
    skip cpp-sanitizer "compute-sanitizer not found on this machine"
    cp "$RESULTS/cpp-sanitizer.log" "$RESULTS/compute-sanitizer.log"
  else
    step cpp-sanitizer timeout 300 "$SANITIZER" --tool memcheck --error-exitcode 1 "$SRC/$CPP_BUILD_DIR/hardened/bin/cuda_test" --batches 1,67
    cp "$RESULTS/cpp-sanitizer.log" "$RESULTS/compute-sanitizer.log"
  fi
  setup_jdk
  setup_tornado
  step build "${GRADLE[@]}" :nn:testClasses :nn-tornado:testClasses
  step print-run-command "${GRADLE[@]}" :nn-tornado:printRunCommand
  if [ -n "$REAL_SHA" ]; then
    step real-weights cpp_real_weights
    step_failed real-weights || REAL_OK=1
  fi
  step golden cpp_golden_export
  step golden-hash cpp_golden_hash
  step cpp-check cpp_check
  step anchors-start cpp_anchors anchors-start
  step cpp-bench cpp_bench
  step java-crossbench java_crossbench
  step anchors-end cpp_anchors anchors-end
  cpp_gpufeed
  if [ "$REAL_OK" = 1 ]; then
    echo "sha256 after the run (on this instance):                $(sha256_of "$WORKDIR/real-weights.safetensors")" >> "$RESULTS/real-weights.txt"
  fi
}

if [ "$SUITE" = cpp ]; then run_cpp_suite; else run_default_suite; fi

say "all steps ran"
exit "$STATUS"
