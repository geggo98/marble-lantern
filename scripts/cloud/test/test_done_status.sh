#!/usr/bin/env bash
# aws-spot.sh reads the status that bootstrap.sh wrote into DONE: 0 -> exit 0, other status -> exit 1, no DONE before the deadline -> exit 6.
set -u
# shellcheck disable=SC1091
. "$(dirname "$0")/lib.sh"
make_sandbox
export STUB_PROFILES=known-profile
write_fast_clock
ARGS=(run --instance-type g4dn.xlarge --max-price 0.35 --max-minutes 45 --yes)

echo "DONE holds 0:"
STUB_DONE_STATUS=0 PROFILE=known-profile run_spot "${ARGS[@]}"
assert_eq "$RC" 0 "exit 0"

echo "DONE holds 1 (the gate failed):"
STUB_DONE_STATUS=1 PROFILE=known-profile run_spot "${ARGS[@]}"
assert_eq "$RC" 1 "exit 1"
assert_contains "$OUTTXT" "status 1" "the message names the status"

echo "no DONE before the deadline:"
STUB_DONE_STATUS=none PROFILE=known-profile run_spot "${ARGS[@]}"
assert_eq "$RC" 6 "exit 6"
assert_contains "$OUTTXT" "no DONE" "the message names the missing DONE"
finish_test
