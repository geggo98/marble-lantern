#!/usr/bin/env bash
# A failing aws describe call must not read as "nothing left": the cleanup command ends non-zero and says so.
set -u
# shellcheck disable=SC1091
. "$(dirname "$0")/lib.sh"
make_sandbox
export STUB_PROFILES=known-profile

echo "describe calls work:"
PROFILE=known-profile run_spot cleanup --run-id sh-20260101-000001-1
assert_eq "$RC" 0 "exit 0"
assert_contains "$OUTTXT" "nothing left" "reports nothing left"

echo "every describe call fails (expired credentials):"
STUB_DESCRIBE_FAIL=1 VERIFY_TRIES=2 PROFILE=known-profile run_spot cleanup --run-id sh-20260101-000001-1
assert_eq "$RC" 1 "exit 1"
assert_contains "$OUTTXT" "verification FAILED" "the message names the failed verification"
assert_not_contains "$OUTTXT" "nothing left" "no false all-clear"
finish_test
