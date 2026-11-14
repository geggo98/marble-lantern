#!/usr/bin/env bash
# Finding 3: HUP and TERM (as INT) run the cleanup: cancel, terminate, delete security group and key pair, verify.
# (INT cannot be sent to a background job of a non-interactive shell, so HUP and TERM are tested.)
set -u
# shellcheck disable=SC1091
. "$(dirname "$0")/lib.sh"
make_sandbox

for sig in HUP TERM; do
  echo "signal $sig during the wait for the run:"
  : > "$STUB_LOG"; rm -f "$STUB_MARK"
  # the ssh stub blocks 3 s in the wait loop; a trap runs when that foreground command returns
  env -u AWS_ACCESS_KEY_ID PATH="$STUB_BIN:$PATH" AWS_PROFILE=known-profile AWS_REGION=eu-central-1 RESULTS_DIR="$OUT" \
    STUB_LOG="$STUB_LOG" STUB_MARK="$STUB_MARK" STUB_SSH_WAIT=3 \
    bash "$SPOT" run --instance-type c7i.xlarge --max-price 0.5 --max-minutes 20 --yes > "$T/out-$sig.txt" 2>&1 &
  pid=$!
  for _ in $(seq 1 100); do [ -e "$STUB_MARK" ] && break; sleep 0.2; done
  if [ -e "$STUB_MARK" ]; then pass "the run reached the wait loop"; else fail "never reached the wait loop"; fi
  kill -"$sig" "$pid"
  rc=0; wait "$pid" || rc=$?
  case $sig in HUP) want=129 ;; *) want=143 ;; esac
  assert_eq "$rc" "$want" "exit code after $sig"
  log=$(cat "$STUB_LOG")
  for call in cancel-spot-instance-requests terminate-instances delete-security-group delete-key-pair; do
    assert_contains "$log" "$call" "$sig: $call ran"
  done
  assert_contains "$(cat "$T/out-$sig.txt")" "nothing left" "$sig: verification reports a clean account"
done
finish_test
