#!/usr/bin/env bash
# Finding 2: nothing unvalidated reaches the user data (root on the instance), ssh or scp.
set -u
# shellcheck disable=SC1091
. "$(dirname "$0")/lib.sh"
make_sandbox
BASE=(run --dry-run --instance-type c7i.xlarge --max-price 0.5 --max-minutes 20)
PWN="$T/pwned"

echo "--feed-seconds:"
NL=$'\n'
for bad in "60; touch $PWN" "60\$(touch $PWN)" "\`touch $PWN\`" "60${NL}touch $PWN" "9" "601" "-5" "6.5" "" "0x10" "1e2" " 60" "60${NL}"; do
  PROFILE="" run_spot "${BASE[@]}" --feed-seconds "$bad"
  assert_eq "$RC" 2 "refused: $(printf '%q' "$bad")"
done
PROFILE="" run_spot "${BASE[@]}" --feed-seconds 90
assert_eq "$RC" 0 "90 accepted"
assert_contains "$OUTTXT" "--feed-seconds 90" "the integer is in the user data"
PROFILE="" run_spot "${BASE[@]}" --feed-seconds 060
assert_contains "$OUTTXT" "--feed-seconds 60" "leading zero normalised (no octal)"

echo "other values:"
check_refused() { PROFILE="" run_spot "$@"; assert_eq "$RC" 2 "refused: $*"; }
check_refused "${BASE[@]}" --backend 'none; touch x'
check_refused run --dry-run --instance-type c7i.xlarge --max-price 0.5 --max-minutes '20; touch x'
check_refused run --dry-run --instance-type c7i.xlarge --max-price '0.5; touch x' --max-minutes 20
check_refused run --dry-run --instance-type c7i.xlarge --max-price "0.5${NL}" --max-minutes 20
check_refused run --dry-run --instance-type 'c7i.xlarge; touch x' --max-price 0.5 --max-minutes 20
check_refused "${BASE[@]}" --region 'eu-central-1; touch x'
check_refused "${BASE[@]}" --ami 'ami-1; touch x'
check_refused cleanup --dry-run --run-id 'sh-1; touch x'
if [ ! -e "$PWN" ]; then pass "no injected command ran"; else fail "injected command ran"; fi

echo "the generated user data:"
PROFILE="" run_spot "${BASE[@]}" --feed-seconds 45 --backend none
ud=$(perl -ne 'if (/^--- user data/) { $on = 1; next } if (/^---$/) { $on = 0 } print if $on' "$T/out.txt")
printf '%s\n' "$ud" > "$T/ud.sh"
if bash -n "$T/ud.sh"; then pass "user data is valid bash"; else fail "user data does not parse"; fi
assert_contains "$ud" "shutdown -h +20" "hard timer from the validated minutes"
finish_test
