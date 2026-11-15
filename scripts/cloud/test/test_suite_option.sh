#!/usr/bin/env bash
# aws-spot.sh --suite: only default and cpp; the user data carries it; cpp needs the cuda backend.
# shellcheck disable=SC2016  # the stub scripts are written with single quotes on purpose
set -u
# shellcheck disable=SC1091
. "$(dirname "$0")/lib.sh"
make_sandbox
BASE=(run --dry-run --instance-type g4dn.xlarge --max-price 0.35 --max-minutes 45)

for ok in default cpp; do
  PROFILE="" run_spot "${BASE[@]}" --suite "$ok"
  assert_eq "$RC" 0 "--suite $ok accepted"
done
NL=$'\n'
for bad in "cpp;rm" "cpp${NL}" "CPP" "" "cpp x" '$(touch x)'; do
  PROFILE="" run_spot "${BASE[@]}" --suite "$bad"
  assert_eq "$RC" 2 "refused: $(printf '%q' "$bad")"
done
PROFILE="" run_spot "${BASE[@]}" --suite cpp
assert_contains "$OUTTXT" "--suite cpp" "the user data contains --suite cpp"
ud=$(perl -ne 'if (/^--- user data/) { $on = 1; next } if (/^---$/) { $on = 0 } print if $on' "$T/out.txt")
printf '%s\n' "$ud" > "$T/ud.sh"
if bash -n "$T/ud.sh"; then pass "user data with the suite is valid bash"; else fail "user data does not parse"; fi
PROFILE="" run_spot "${BASE[@]}"
assert_contains "$OUTTXT" "--suite default" "default suite when none is given"
PROFILE="" run_spot run --dry-run --instance-type c7i.xlarge --max-price 0.5 --max-minutes 20 --suite cpp
assert_eq "$RC" 2 "--suite cpp needs the cuda backend"
finish_test
