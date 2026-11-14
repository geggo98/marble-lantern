#!/usr/bin/env bash
# Finding 1: the "never the default profile" guard covers every command except "run --dry-run", and a profile that is
# not defined in the AWS config is refused with an error that names the variable, not the value.
set -u
# shellcheck disable=SC1091
. "$(dirname "$0")/lib.sh"
make_sandbox
RID=sh-20260101-000000-1

echo "no profile at all:"
for args in "preflight --instance-type c7i.xlarge" "preflight --dry-run --instance-type c7i.xlarge" \
            "cleanup --run-id $RID" "cleanup --dry-run --run-id $RID" \
            "run --instance-type c7i.xlarge --max-price 0.5 --max-minutes 20 --yes"; do
  : > "$STUB_LOG"
  # shellcheck disable=SC2086
  PROFILE="" run_spot $args
  assert_eq "$RC" 2 "refused (exit 2): $args"
  assert_contains "$OUTTXT" "no AWS_PROFILE" "message names the variable"
  assert_eq "$(grep -c '^aws ' "$STUB_LOG")" 0 "no aws call: $args"
done

echo "run --dry-run is the one exception:"
: > "$STUB_LOG"; PROFILE="" run_spot run --dry-run --instance-type c7i.xlarge --max-price 0.5 --max-minutes 20
assert_eq "$RC" 0 "run --dry-run without a profile works"
assert_eq "$(grep -c '^aws ' "$STUB_LOG")" 0 "run --dry-run executes no aws call"

echo "profile not defined in the AWS config:"
export STUB_PROFILES="other-one another"
for args in "preflight --instance-type c7i.xlarge" "preflight --dry-run --instance-type c7i.xlarge" "cleanup --dry-run --run-id $RID"; do
  : > "$STUB_LOG"
  # shellcheck disable=SC2086
  PROFILE=secret-profile-value run_spot $args
  assert_eq "$RC" 2 "refused (exit 2): $args"
  assert_contains "$OUTTXT" "AWS_PROFILE" "names the variable"
  assert_not_contains "$OUTTXT" "secret-profile-value" "does not print the value"
  assert_eq "$(grep -c -v 'configure list-profiles' "$STUB_LOG")" 0 "only the local profile list was asked: $args"
done

echo "a defined profile gets through the guard:"
export STUB_PROFILES="other-one known-profile"
: > "$STUB_LOG"; PROFILE=known-profile run_spot preflight --instance-type c7i.xlarge
if [ "$RC" != 2 ]; then pass "preflight with a defined profile is not refused (exit $RC)"; else fail "defined profile refused: $OUTTXT"; fi
assert_contains "$(cat "$STUB_LOG")" "sts get-caller-identity" "pre-flight reached the identity call"
finish_test
