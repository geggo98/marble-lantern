# shellcheck shell=bash
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

write_stubs() {
  cat > "$STUB_BIN/aws" <<'STUB'
#!/bin/bash
echo "aws $*" >> "$STUB_LOG"
logged() { grep -q -- "$1" "$STUB_LOG"; }
case "$1 $2" in
  "configure list-profiles") printf '%s\n' ${STUB_PROFILES:-known-profile}; exit 0 ;;
  "sts get-caller-identity") echo "arn:aws:iam::000000000000:user/stub"; exit 0 ;;
  "ssm get-parameter") echo ami-0123456789abcdef0; exit 0 ;;
  "ec2 run-instances")
    case "$*" in *--dry-run*) echo "An error occurred (DryRunOperation) when calling the RunInstances operation"; exit 254 ;; esac
    echo i-0stub0001; exit 0 ;;
  "ec2 describe-instance-types") echo 4; exit 0 ;;
  "ec2 describe-vpcs") echo vpc-0stub; exit 0 ;;
  "ec2 create-security-group") echo sg-0stub; exit 0 ;;
  "ec2 describe-instances")
    case "$*" in
      *PublicIpAddress*) echo 198.51.100.7 ;;
      *Availability*) echo eu-stub-1a ;;
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
