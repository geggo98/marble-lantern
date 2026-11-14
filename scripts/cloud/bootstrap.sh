#!/usr/bin/env bash
# Runs ON a fresh Ubuntu 22.04/24.04 x86_64 instance (as root): installs JDK 25 and the TornadoVM SDK, builds the project from a
# source tarball, runs the device tests, the Vector API tests and benchmarks and the gpuFeed rows, writes a results
# directory, and ALWAYS schedules the shutdown of the machine, also on failure (trap EXIT).
#
# usage: bootstrap.sh --backend cuda|opencl|none --hard-minutes N [--workdir DIR] [--grace-minutes N]
#                     [--feed-seconds N] [--install-nvidia 0|1]
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
#
# Why bash and not zsh: it runs on a machine we do not control, where only bash is certain (shellcheck covers it).
# Exit codes: 0 all steps ran and every test passed, 1 a step failed (the run goes on; see results/summary.txt),
# 2 usage, 3 a prerequisite is missing (no NVIDIA driver, no NVRTC), 4 a download did not verify.
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

usage() { sed -n '2,17p' "$0" >&2; exit 2; }

while [ $# -gt 0 ]; do
  case $1 in
    --backend) BACKEND=${2:-}; shift 2 ;;
    --hard-minutes) HARD_MIN=${2:-}; shift 2 ;;
    --workdir) WORKDIR=${2:-}; shift 2 ;;
    --grace-minutes) GRACE_MIN=${2:-}; shift 2 ;;
    --feed-seconds) FEED_SECONDS=${2:-}; shift 2 ;;
    --install-nvidia) INSTALL_NVIDIA=${2:-}; shift 2 ;;
    *) usage ;;
  esac
done
case $BACKEND in cuda|opencl|none) ;; *) usage ;; esac
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

# --- JDK 25 --------------------------------------------------------------------------------------------------------
JDK_HOME=${JDK_HOME:-/opt/jdk25}
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

# --- source --------------------------------------------------------------------------------------------------------
[ -f "$WORKDIR/src.tar.gz" ] || { say "missing $WORKDIR/src.tar.gz"; exit 3; }
mkdir -p "$SRC"
tar -xzf "$WORKDIR/src.tar.gz" -C "$SRC" || exit 3
cd "$SRC" || exit 3

# --- NVIDIA driver and NVRTC (cuda backend only) -------------------------------------------------------------------
find_nvrtc() {
  ldconfig -p 2>/dev/null | perl -ne 'print "$1\n" if m{=>\s*(\S*libnvrtc\.so\S*)}' | head -1
  find /usr/local/cuda* /usr/lib/x86_64-linux-gnu /opt -name 'libnvrtc.so*' 2>/dev/null | head -1
}
apt_nvidia() {
  apt_retry install -y -qq ubuntu-drivers-common "linux-headers-$(uname -r)" && ubuntu-drivers install --gpgpu && modprobe nvidia
}
if [ "$BACKEND" = cuda ]; then
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
  [ -d /usr/local/cuda ] && export CUDA_PATH=/usr/local/cuda
fi

# --- TornadoVM SDK -------------------------------------------------------------------------------------------------
if [ "$BACKEND" != none ]; then
  step tornado-setup env DEVENV_ROOT="$SRC" zsh "$SRC/scripts/setup-tornado.sh" "$BACKEND"
  export TORNADOVM_HOME=$SRC/tornado-sdk/current
  export PATH=$TORNADOVM_HOME/bin:$PATH
  export TORNADO_BACKEND=$BACKEND
  step tornado-devices tornado --devices
fi

# --- build ---------------------------------------------------------------------------------------------------------
GRADLE=(./gradlew --no-build-cache --max-workers=3 --console=plain)
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

say "all steps ran"
exit "$STATUS"
