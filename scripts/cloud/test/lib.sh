# shellcheck shell=bash
# shellcheck disable=SC2034,SC2054  # variables are used by the sourcing tests; commas inside array elements are intended
# Shared helpers of the cloud script tests. Sourced, never run. Nothing here talks to AWS: every external command
# (aws, ssh, scp, curl, git) is a stub on the PATH that only appends its arguments to $STUB_LOG.

TEST_HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
SRC_CLOUD=$(cd "$TEST_HERE/.." && pwd)
FAILS=0
CHECKS=0

pass() { CHECKS=$((CHECKS + 1)); echo "  ok   $*"; }
fail() { CHECKS=$((CHECKS + 1)); FAILS=$((FAILS + 1)); echo "  FAIL $*" >&2; }
assert_eq() { if [ "$1" = "$2" ]; then pass "$3"; else fail "$3 (expected '$2', got '$1')"; fi; }
assert_contains() { case $1 in *"$2"*) pass "$3" ;; *) fail "$3 (no '$2' in: $1)" ;; esac; }
assert_not_contains() { case $1 in *"$2"*) fail "$3 (found '$2' in: $1)" ;; *) pass "$3" ;; esac; }
finish_test() { echo "$(basename "$0"): $((CHECKS - FAILS)) of $CHECKS checks ok"; [ "$FAILS" = 0 ]; }

# sandbox: a copy of the scripts in a fresh tree (so that no .env of the real checkout is read) and a bin/ with stubs.
# Sets T (root), SPOT (aws-spot.sh), STUB_LOG, STUB_BIN, OUT (results dir).
make_sandbox() {
  T=$(mktemp -d "${TMPDIR:-/tmp}/cloudtest.XXXXXX")
  mkdir -p "$T/repo/scripts/cloud" "$T/bin" "$T/out"
  cp "$SRC_CLOUD/aws-spot.sh" "$SRC_CLOUD/bootstrap.sh" "$T/repo/scripts/cloud/"
  SPOT=$T/repo/scripts/cloud/aws-spot.sh
  STUB_LOG=$T/stub.log; STUB_BIN=$T/bin; OUT=$T/out
  : > "$STUB_LOG"
  write_stubs
  trap 'rm -rf "$T"' EXIT
}

STUB_IMAGE_SUFFIX=0fedcba9876543210   # a made-up image id, assembled at run time so that no id-shaped literal is in the file
export STUB_IMAGE_SUFFIX
export VERIFY_SLEEP=0   # the leftover verification retries; do not sleep in tests
write_stubs() {
  cat > "$STUB_BIN/aws" <<'STUB'
#!/bin/bash
echo "aws $*" >> "$STUB_LOG"
logged() { grep -q -- "$1" "$STUB_LOG"; }
case "$1 $2" in
  "configure list") case " ${STUB_PROFILES:-known-profile} " in *" ${3#--profile} "*|*" $4 "*) echo "      Name       Value"; exit 0 ;; esac; echo "The config profile could not be found" >&2; exit 255 ;;
  "sts get-caller-identity") echo "arn:aws:iam::000000000000:user/stub"; exit 0 ;;
  "ssm get-parameter")
    [ -z "${STUB_SSM_FAIL:-}" ] || { echo "An error occurred (ParameterNotFound) when calling the GetParameter operation" >&2; exit 254; }
    echo ami-0123456789abcdef0; exit 0 ;;
  "ec2 describe-images") echo "${STUB_IMAGE_ID:-ami-$STUB_IMAGE_SUFFIX}"; exit 0 ;;
  "ec2 describe-spot-price-history")
    [ -z "${STUB_SPOT_PRICE:-}" ] || { case "$*" in *max-items*) echo "$STUB_SPOT_PRICE" ;; *) printf 'eu-stub-1a\t%s\n' "$STUB_SPOT_PRICE" ;; esac; }
    exit 0 ;;
  "ec2 run-instances")
    case "$*" in *--dry-run*) echo "An error occurred (DryRunOperation) when calling the RunInstances operation"; exit 254 ;; esac
    [ -z "${STUB_RUN_ERROR:-}" ] || { echo "An error occurred ($STUB_RUN_ERROR) when calling the RunInstances operation" >&2; exit 254; }
    echo i-0stub0001; exit 0 ;;
  "ec2 describe-instance-types") echo 4; exit 0 ;;
  "ec2 describe-vpcs") echo vpc-0stub; exit 0 ;;
  "ec2 create-security-group") echo sg-0stub; exit 0 ;;
  "ec2 describe-instances")
    case "$*" in
      *PublicIpAddress*) echo 198.51.100.7 ;;
      *Availability*) echo eu-stub-1a ;;
      *"Instances[0].State.Name"*) echo "${STUB_INSTANCE_STATE:-running}" ;;
      *) logged "terminate-instances" || echo i-0stub0001 ;;
    esac; exit 0 ;;
  "ec2 describe-spot-instance-requests") logged "cancel-spot-instance-requests" || echo sir-0stub; exit 0 ;;
  "ec2 describe-security-groups") logged "delete-security-group" || echo sg-0stub; exit 0 ;;
  "ec2 describe-key-pairs") logged "delete-key-pair" || echo key-stub; exit 0 ;;
esac
exit 0
STUB
  cat > "$STUB_BIN/ssh" <<'STUB'
#!/bin/bash
echo "ssh $*" >> "$STUB_LOG"
case "$*" in
  *"test -f"*DONE*) : > "$STUB_MARK"; sleep "${STUB_SSH_WAIT:-0}"; exit 1 ;;
  *sha256sum*) echo "${STUB_REMOTE_SHA:-0000000000000000000000000000000000000000000000000000000000000000}  real-weights.safetensors"; exit 0 ;;
esac
exit 0
STUB
  cat > "$STUB_BIN/scp" <<'STUB'
#!/bin/bash
echo "scp $*" >> "$STUB_LOG"
exit 0
STUB
  cat > "$STUB_BIN/curl" <<'STUB'
#!/bin/bash
echo 203.0.113.9
STUB
  cat > "$STUB_BIN/git" <<'STUB'
#!/bin/bash
# only "git archive ... -o FILE": creates the file
echo "git $*" >> "$STUB_LOG"
while [ $# -gt 0 ]; do [ "$1" = -o ] && { : > "$2"; break; }; shift; done
exit 0
STUB
  chmod +x "$STUB_BIN"/*
  export STUB_LOG STUB_MARK=$T/waiting
}

# fast clock: "date +%s" advances 100 s per call, "sleep" returns at once (for the long waiting loop)
write_fast_clock() {
  echo 1700000000 > "$T/clock"
  cat > "$STUB_BIN/date" <<'STUB'
#!/bin/bash
if [ "$*" = "+%s" ]; then n=$(( $(cat "$STUB_CLOCK") + 100 )); echo "$n" > "$STUB_CLOCK"; echo "$n"; exit 0; fi
exec /bin/date "$@"
STUB
  printf '#!/bin/bash\nexit 0\n' > "$STUB_BIN/sleep"
  chmod +x "$STUB_BIN/date" "$STUB_BIN/sleep"
  export STUB_CLOCK=$T/clock
}

# run_spot ARGS...: runs the sandboxed aws-spot.sh with the stubs first on the PATH and no credentials in the
# environment (PROFILE=name sets AWS_PROFILE); stdout+stderr go to $T/out.txt and OUTTXT, the exit code is in $RC.
run_spot() {
  RC=0
  env -u AWS_PROFILE -u AWS_ACCESS_KEY_ID -u AWS_REGION -u AWS_DEFAULT_REGION PATH="$STUB_BIN:$PATH" \
    ${PROFILE:+AWS_PROFILE=$PROFILE} AWS_REGION=eu-central-1 RESULTS_DIR="$OUT" \
    bash "$SPOT" "$@" > "$T/out.txt" 2>&1 || RC=$?
  OUTTXT=$(cat "$T/out.txt")
}

# --- stubs for spot-retry.py (no AWS: the "aws-spot.sh" is a script of this directory) ---------------------------------------------
# make_retry_sandbox: sets T, RES (results dir), SPOTSTUB (the stub aws-spot.sh), STUB_LOG, CODES (a file: one exit code per
# "run" call, taken from the top; empty = 0). Stub behaviour through files and variables:
#   $T/pre-<region>   exit code of the pre-flight of that region (default 0)
#   STUB_COST=<usd>   a run that did not end with 4 writes cloud-results/<run id>/cost.tsv with that estimate
#   STUB_HANG=1       "run" waits for $T/release; on SIGTERM it writes $T/cleanup-ran and exits 143
make_retry_sandbox() {
  T=$(mktemp -d "${TMPDIR:-/tmp}/retrytest.XXXXXX")
  RES=$T/cloud-results; mkdir -p "$RES"
  STUB_LOG=$T/stub.log; CODES=$T/codes; SPOTSTUB=$T/aws-spot-stub.sh
  : > "$STUB_LOG"; : > "$CODES"
  cat > "$SPOTSTUB" <<'STUB'
#!/bin/bash
echo "$*" >> "$STUB_LOG"
cmd=$1; shift
region=""; prev=""
for a in "$@"; do [ "$prev" = --region ] && region=$a; prev=$a; done
if [ "$cmd" = preflight ]; then
  [ -f "$STUB_DIR/pre-$region" ] && exit "$(cat "$STUB_DIR/pre-$region")"
  echo "pre-flight in $region: ok"; exit 0
fi
n=$(( $(cat "$STUB_DIR/counter" 2>/dev/null || echo 0) + 1 )); echo "$n" > "$STUB_DIR/counter"
id=$(printf 'sh-20260101-%06d-%d' "$n" "$n")
echo "[00:00:00] run $id: suite cpp, g4dn.xlarge, in $region"
if [ -n "${STUB_HANG:-}" ]; then
  trap 'echo cleanup > "$STUB_DIR/cleanup-ran"; exit 143' TERM
  : > "$STUB_DIR/in-run"
  for _ in $(seq 1 150); do [ -f "$STUB_DIR/release" ] && break; sleep 0.2; done
fi
rc=$(head -1 "$CODES" 2>/dev/null); rc=${rc:-0}
[ -s "$CODES" ] && tail -n +2 "$CODES" > "$CODES.n" && mv "$CODES.n" "$CODES"
if [ "$rc" != 4 ] && [ -n "${STUB_COST:-}" ]; then
  mkdir -p "$RESULTS_DIR/$id"
  printf 'cpp\t2026-01-01T00:00:00Z\t100\t0.30\t%s\t0.2625\n' "$STUB_COST" > "$RESULTS_DIR/$id/cost.tsv"
fi
exit "$rc"
STUB
  chmod +x "$SPOTSTUB"
  export STUB_DIR=$T STUB_LOG CODES RESULTS_DIR=$RES
  trap 'rm -rf "$T"' EXIT
}
# run_retry ARGS...: spot-retry.py with the stub; output in $T/retry.out and RETOUT, exit code in $RC
run_retry() {
  RC=0
  python3 -I "$SRC_CLOUD/spot-retry.py" --aws-spot "$SPOTSTUB" "$@" > "$T/retry.out" 2>&1 || RC=$?
  RETOUT=$(cat "$T/retry.out")
}
RETRY_COMMON=(--regions eu-central-1,us-east-1 --attempts 6 --wait 0 --budget-usd 1.00 --suite cpp)
RETRY_RUN=(-- --instance-type g4dn.xlarge --max-price 0.35 --max-minutes 45 --backend cuda --feed-seconds 30 --yes)
