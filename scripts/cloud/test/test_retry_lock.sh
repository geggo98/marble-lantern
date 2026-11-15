#!/usr/bin/env bash
# spot-retry.py: flock lock (exit 75 with the PID of the holder), --status during and after a run, --detach.
set -u
# shellcheck disable=SC1091
. "$(dirname "$0")/lib.sh"
make_retry_sandbox
RETRY=(python3 -I "$SRC_CLOUD/spot-retry.py" --aws-spot "$SPOTSTUB")

wait_for() { for _ in $(seq 1 100); do [ -e "$1" ] && return 0; sleep 0.1; done; return 1; }

echo "a run that holds the lock (the stub waits for $T/release):"
printf '0\n' > "$CODES"
STUB_HANG=1 "${RETRY[@]}" "${RETRY_COMMON[@]}" "${RETRY_RUN[@]}" > "$T/first.out" 2>&1 &
pid=$!
if wait_for "$T/in-run"; then pass "the first run is in its attempt"; else fail "the first run never reached the attempt"; fi

RC=0; out=$("${RETRY[@]}" "${RETRY_COMMON[@]}" "${RETRY_RUN[@]}" 2>&1) || RC=$?
assert_eq "$RC" 75 "a second start: exit 75"
assert_contains "$out" "already running: pid=$pid" "the message names the PID of the holder"
assert_eq "$(grep -c '^run ' "$STUB_LOG")" 1 "the second start launched nothing"

RC=0; out=$("${RETRY[@]}" --status 2>&1) || RC=$?
assert_eq "$RC" 75 "--status during the run: exit 75"
assert_contains "$out" "running: pid=$pid" "--status names the PID"
assert_contains "$out" "attempt 1/6" "--status names the attempt"
assert_contains "$out" "region eu-central-1" "--status names the region"
assert_contains "$out" "run sh-20260101-000001-1" "--status names the run id once the attempt is known"

touch "$T/release"
RC=0; wait "$pid" || RC=$?
assert_eq "$RC" 0 "the first run ends with 0"
RC=0; out=$("${RETRY[@]}" --status 2>&1) || RC=$?
assert_eq "$RC" 0 "--status after the run: exit 0"
assert_eq "$out" "not running" "and says so (a status file is only a leftover)"

echo "kill -9 of the holder frees the lock at once (no stale state):"
rm -f "$T/release" "$T/in-run"; printf '0\n' > "$CODES"
STUB_HANG=1 "${RETRY[@]}" "${RETRY_COMMON[@]}" "${RETRY_RUN[@]}" > "$T/second.out" 2>&1 &
pid=$!
wait_for "$T/in-run" || fail "the run did not start"
kill -9 "$pid"; wait "$pid" 2> /dev/null
RC=0; out=$("${RETRY[@]}" --status 2>&1) || RC=$?
assert_eq "$out" "not running" "free after kill -9"
pkill -f "$SPOTSTUB" 2> /dev/null; sleep 0.3

echo "--detach:"
rm -f "$T/release" "$T/in-run" "$T/counter"; printf '0\n' > "$CODES"
RC=0; out=$(STUB_HANG=1 "${RETRY[@]}" --detach "${RETRY_COMMON[@]}" "${RETRY_RUN[@]}" 2>&1) || RC=$?
assert_eq "$RC" 0 "returns at once with 0"
assert_contains "$out" "detached: log " "prints the log path"
assert_contains "$out" " pid " "and the PID"
logf=$(printf '%s\n' "$out" | perl -ne 'print $1 if /log (\S+)/')
dpid=$(printf '%s\n' "$out" | perl -ne 'print $1 if /pid (\d+)/')
wait_for "$T/in-run" || fail "the detached run did not start"
RC=0; out2=$("${RETRY[@]}" "${RETRY_COMMON[@]}" "${RETRY_RUN[@]}" 2>&1) || RC=$?
assert_eq "$RC" 75 "a start while the detached run holds the lock: exit 75"
assert_contains "$out2" "already running: pid=$dpid" "names the detached PID"
touch "$T/release"
for _ in $(seq 1 100); do grep -q 'exit code' "$logf" 2> /dev/null && break; sleep 0.1; done
assert_contains "$(cat "$logf")" "summary:" "the log holds the summary"
assert_contains "$(cat "$logf")" "exit code 0" "and the exit code"
sleep 0.3
RC=0; out=$("${RETRY[@]}" --status 2>&1) || RC=$?
assert_eq "$out" "not running" "--status: not running after the detached run"
finish_test
