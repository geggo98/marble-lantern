#!/usr/bin/env bash
# spot-retry.py: SIGTERM and SIGINT are forwarded to the running aws-spot.sh (its cleanup runs), the summary is printed, exit 143 / 130;
# between attempts the flag stops the loop at once.
set -u
# shellcheck disable=SC1091
. "$(dirname "$0")/lib.sh"
make_retry_sandbox
RETRY=(python3 -I "$SRC_CLOUD/spot-retry.py" --aws-spot "$SPOTSTUB")
wait_for() { for _ in $(seq 1 100); do [ -e "$1" ] && return 0; sleep 0.1; done; return 1; }

for sig in TERM INT; do
  echo "signal $sig during an attempt:"
  rm -f "$T/release" "$T/in-run" "$T/cleanup-ran" "$T/counter"; : > "$STUB_LOG"; printf '0\n' > "$CODES"
  STUB_HANG=1 "${RETRY[@]}" "${RETRY_COMMON[@]}" "${RETRY_RUN[@]}" > "$T/out-$sig.txt" 2>&1 &
  pid=$!
  wait_for "$T/in-run" || fail "the attempt did not start"
  kill -"$sig" "$pid"
  RC=0; wait "$pid" || RC=$?
  case $sig in TERM) want=143 ;; *) want=130 ;; esac
  assert_eq "$RC" "$want" "exit code after $sig"
  if [ -e "$T/cleanup-ran" ]; then pass "the stub aws-spot.sh received SIGTERM and ran its cleanup"; else fail "the stub never saw SIGTERM"; fi
  assert_contains "$(cat "$T/out-$sig.txt")" "summary:" "summary printed"
  assert_contains "$(cat "$T/out-$sig.txt")" "exit code $want" "summary names the exit code"
  assert_eq "$(grep -c '^run ' "$STUB_LOG")" 1 "no further attempt"
done

echo "signal while waiting between attempts:"
rm -f "$T/release" "$T/in-run" "$T/cleanup-ran" "$T/counter"; : > "$STUB_LOG"; printf '4\n4\n' > "$CODES"
"${RETRY[@]}" --regions eu-central-1 --attempts 6 --wait 60 --budget-usd 1.00 --suite cpp "${RETRY_RUN[@]}" > "$T/out-wait.txt" 2>&1 &
pid=$!
for _ in $(seq 1 100); do grep -q 'waiting 60 s' "$T/out-wait.txt" 2> /dev/null && break; sleep 0.1; done
start=$SECONDS; kill -TERM "$pid"
RC=0; wait "$pid" || RC=$?
assert_eq "$RC" 143 "exit 143"
if [ $((SECONDS - start)) -le 5 ]; then pass "stopped within 5 s, not after the 60 s wait"; else fail "took $((SECONDS - start)) s"; fi
assert_eq "$(grep -c '^run ' "$STUB_LOG")" 1 "no second attempt"
finish_test
