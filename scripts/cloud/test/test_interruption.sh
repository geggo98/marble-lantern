#!/usr/bin/env bash
# A spot interruption ends the instance without a word: the wait must notice it (instance state), stop at once and exit 4 (the retry
# runner may try again); while the instance runs, the partial results are copied every 5 minutes ("latest"). 2026-11-15: a run waited
# 25 minutes for an instance that had been gone since minute 5.
set -u
# shellcheck disable=SC1091
. "$(dirname "$0")/lib.sh"
make_sandbox
write_fast_clock

echo "the instance is terminated by the service:"
: > "$STUB_LOG"
STUB_INSTANCE_STATE=terminated PROFILE=known-profile run_spot run --instance-type g4dn.xlarge --max-price 0.35 --max-minutes 45 --yes
assert_eq "$RC" 4 "exit 4 (retry may follow)"
assert_contains "$OUTTXT" "before the run finished" "the message names the interruption"
assert_eq "$(grep -c 'test -f .*DONE' "$STUB_LOG")" 1 "one poll only, no waiting until the deadline"
assert_eq "$(grep -c 'terminate-instances' "$STUB_LOG")" 1 "the cleanup terminated the instance"

echo "a running instance whose DONE never appears (fast clock):"
: > "$STUB_LOG"
STUB_DONE_STATUS=none STUB_INSTANCE_STATE=running PROFILE=known-profile run_spot run --instance-type g4dn.xlarge --max-price 0.35 --max-minutes 45 --yes
assert_eq "$RC" 6 "the run ends with the deadline and no DONE: exit 6 (exit $RC)"
latest=$(grep -c '^scp .* -r .*latest/' "$STUB_LOG")
if [ "$latest" -ge 1 ]; then echo "  ok   partial results copied $latest time(s) while waiting"; else echo "  FAIL no periodic copy"; FAILED=$(( ${FAILED:-0} + 1 )); fi
finish_test
