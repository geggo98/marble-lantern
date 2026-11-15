#!/usr/bin/env bash
# spot-retry.py with a stub aws-spot.sh: retries only on exit 4, regions in turn, pre-flight drops a region, cumulative cap from cost.tsv,
# on-demand fallback inside the same cap.
set -u
# shellcheck disable=SC1091
. "$(dirname "$0")/lib.sh"
make_retry_sandbox

echo "exit codes 4, 4, 0: three attempts over two regions:"
printf '4\n4\n0\n' > "$CODES"; STUB_COST=0.10 run_retry "${RETRY_COMMON[@]}" "${RETRY_RUN[@]}"
assert_eq "$RC" 0 "exit 0 after the third attempt"
assert_eq "$(grep -c '^run ' "$STUB_LOG")" 3 "three run calls"
regions=$(grep '^run ' "$STUB_LOG" | perl -ne 'print "$1 " if /--region (\S+)/')
assert_eq "$regions" "eu-central-1 us-east-1 eu-central-1 " "regions in turn"
assert_contains "$(grep '^run ' "$STUB_LOG" | head -1)" "--suite cpp" "the suite is passed on"
assert_contains "$(grep '^run ' "$STUB_LOG" | head -1)" "--budget-usd 1.00" "first attempt: the whole cap"
assert_contains "$(grep '^run ' "$STUB_LOG" | tail -1)" "--budget-usd 1.00" "failed launches cost nothing: still the whole cap"
assert_contains "$RETOUT" "cumulative cost of the cpp suite: 0.1000 USD" "summary: cumulative cost from cost.tsv"
assert_contains "$RETOUT" "sh-20260101-000003-3" "summary names the run id"

echo "interrupted runs (exit 4 with cost.tsv) reduce the budget of the next attempt:"
rm -rf "$RES"/sh-*; : > "$STUB_LOG"; rm -f "$T/counter"
printf '4\n4\n0\n' > "$CODES"; STUB_COST_ON_4=1 STUB_COST=0.30 run_retry "${RETRY_COMMON[@]}" "${RETRY_RUN[@]}"
assert_eq "$RC" 0 "exit 0 after the third attempt"
assert_contains "$(grep '^run ' "$STUB_LOG" | sed -n 2p)" "--budget-usd 0.70" "second attempt: 1.00 - 0.30"
assert_contains "$(grep '^run ' "$STUB_LOG" | sed -n 3p)" "--budget-usd 0.40" "third attempt: 1.00 - 2 x 0.30"
assert_contains "$RETOUT" "cumulative cost of the cpp suite: 0.9000 USD" "summary: the sum of all three runs"

echo "the remaining cap is passed (rounded down):"
rm -rf "$RES"/sh-*; : > "$STUB_LOG"; rm -f "$T/counter"; mkdir -p "$RES/sh-20251231-000000-1"
printf 'cpp\t2026-01-01T00:00:00Z\t900\t0.30\t0.2049\t0.2625\n' > "$RES/sh-20251231-000000-1/cost.tsv"
printf '0\n' > "$CODES"; run_retry "${RETRY_COMMON[@]}" "${RETRY_RUN[@]}"
assert_contains "$(grep '^run ' "$STUB_LOG" | head -1)" "--budget-usd 0.79" "1.00 - 0.2049 = 0.7951, passed as 0.79"

echo "exit 1 stops at once:"
rm -rf "$RES"/sh-*; : > "$STUB_LOG"; rm -f "$T/counter"; printf '1\n' > "$CODES"
run_retry "${RETRY_COMMON[@]}" "${RETRY_RUN[@]}"
assert_eq "$RC" 1 "exit 1"
assert_eq "$(grep -c '^run ' "$STUB_LOG")" 1 "one attempt only"

echo "exit 2 or 3 of a run is returned, not retried:"
: > "$STUB_LOG"; printf '3\n' > "$CODES"; run_retry "${RETRY_COMMON[@]}" "${RETRY_RUN[@]}"
assert_eq "$RC" 3 "exit 3 returned"
assert_eq "$(grep -c '^run ' "$STUB_LOG")" 1 "no retry"

echo "no capacity at all: exit 4 after the last attempt:"
: > "$STUB_LOG"; printf '4\n4\n4\n' > "$CODES"; run_retry --regions eu-central-1 --attempts 3 --wait 0 --budget-usd 1.00 --suite cpp "${RETRY_RUN[@]}"
assert_eq "$RC" 4 "exit 4"
assert_eq "$(grep -c '^run ' "$STUB_LOG")" 3 "three attempts"
assert_contains "$RETOUT" "stop and ask the owner" "says what to do"

echo "budget reached by earlier cost.tsv files:"
rm -rf "$RES"/sh-*; : > "$STUB_LOG"; rm -f "$T/counter"
mkdir -p "$RES/sh-20251231-000000-1" "$RES/sh-20251231-000001-2" "$RES/sh-20251231-000002-3"
printf 'cpp\t2026-01-01T00:00:00Z\t900\t0.30\t0.45\t0.2625\n' > "$RES/sh-20251231-000000-1/cost.tsv"
printf 'cpp\t2026-01-01T00:00:00Z\t900\t0.30\t0.45\t0.2625\n' > "$RES/sh-20251231-000001-2/cost.tsv"
printf 'default\t2026-01-01T00:00:00Z\t900\t0.30\t5.00\t0.2625\n' > "$RES/sh-20251231-000002-3/cost.tsv"   # another suite: not counted
printf '0\n' > "$CODES"; run_retry "${RETRY_COMMON[@]}" "${RETRY_RUN[@]}"
assert_eq "$RC" 5 "exit 5"
assert_eq "$(grep -c '^run ' "$STUB_LOG")" 0 "no run was started"
assert_contains "$RETOUT" "refused" "says so"
assert_contains "$RETOUT" "0.9000 USD over 2 run(s)" "only the two cpp runs counted"

echo "a cpp run directory without cost.tsv counts with the worst case:"
rm -rf "$RES"/sh-*; mkdir -p "$RES/sh-20251231-000000-1"; echo cpp > "$RES/sh-20251231-000000-1/suite.txt"
mkdir -p "$RES/sh-20251231-000005-5/results"; printf 'suite: cpp\n' > "$RES/sh-20251231-000005-5/results/summary.txt"
mkdir -p "$RES/sh-20251231-000006-6"; echo default > "$RES/sh-20251231-000006-6/suite.txt"
: > "$STUB_LOG"; run_retry --regions eu-central-1 --attempts 1 --wait 0 --budget-usd 0.70 --suite cpp "${RETRY_RUN[@]}"
assert_eq "$RC" 5 "2 x 0.2625 + 0.2625 above 0.70: refused"
assert_contains "$RETOUT" "over 2 run(s)" "two directories of the suite"

echo "a region whose pre-flight returns 3 is never tried:"
rm -rf "$RES"/sh-*; : > "$STUB_LOG"; rm -f "$T/counter"; echo 3 > "$T/pre-eu-central-1"; printf '4\n0\n' > "$CODES"
run_retry "${RETRY_COMMON[@]}" "${RETRY_RUN[@]}"
assert_eq "$RC" 0 "run ok in the other region"
assert_eq "$(grep '^run ' "$STUB_LOG" | grep -c eu-central-1)" 0 "the dropped region is not used"
assert_contains "$RETOUT" "region eu-central-1 dropped" "and named in the log"
echo 3 > "$T/pre-us-east-1"; : > "$STUB_LOG"
run_retry "${RETRY_COMMON[@]}" "${RETRY_RUN[@]}"
assert_eq "$RC" 3 "no region left: exit 3"
assert_eq "$(grep -c '^run ' "$STUB_LOG")" 0 "nothing launched"
rm -f "$T"/pre-*

echo "on-demand fallback after K spot attempts, same cap:"
rm -rf "$RES"/sh-*; : > "$STUB_LOG"; rm -f "$T/counter"; printf '4\n4\n0\n' > "$CODES"
STUB_COST=0.20 run_retry "${RETRY_COMMON[@]}" --on-demand-after 2 --on-demand-price us-east-1=0.53,eu-central-1=0.66 "${RETRY_RUN[@]}"
assert_eq "$RC" 0 "ok"
assert_not_contains "$(grep '^run ' "$STUB_LOG" | head -2)" "--on-demand" "the first two attempts are spot"
last=$(grep '^run ' "$STUB_LOG" | tail -1)
assert_contains "$last" "--on-demand" "the third is on-demand"
assert_contains "$last" "--max-price 0.66" "with the on-demand price of ITS region (the third attempt is eu-central-1) for the budget check"

echo "on-demand without a price for every region is refused (no guessed default):"
: > "$STUB_LOG"
run_retry "${RETRY_COMMON[@]}" --on-demand-after 2 "${RETRY_RUN[@]}"; assert_eq "$RC" 2 "no --on-demand-price: exit 2"
run_retry "${RETRY_COMMON[@]}" --on-demand-after 2 --on-demand-price us-east-1=0.53 "${RETRY_RUN[@]}"; assert_eq "$RC" 2 "a region without a price: exit 2"
assert_contains "$RETOUT" "eu-central-1" "the missing region is named"
run_retry "${RETRY_COMMON[@]}" --on-demand-after 2 --on-demand-price 0.53 "${RETRY_RUN[@]}"; assert_eq "$RC" 2 "a bare number: exit 2"
assert_eq "$(grep -c '^run ' "$STUB_LOG")" 0 "nothing was launched"

echo "exit 6 (no DONE before the deadline) is returned, not retried:"
rm -rf "$RES"/sh-*; : > "$STUB_LOG"; rm -f "$T/counter"; printf '6\n0\n' > "$CODES"
run_retry "${RETRY_COMMON[@]}" "${RETRY_RUN[@]}"
assert_eq "$RC" 6 "exit 6"
assert_eq "$(grep -c '^run ' "$STUB_LOG")" 1 "one attempt only"

echo "a cost.tsv that is not one line with six fields is not read:"
rm -rf "$RES"/sh-*; mkdir -p "$RES/sh-20251231-000000-1"; echo cpp > "$RES/sh-20251231-000000-1/suite.txt"
printf 'cpp\t2026-01-01T00:00:00Z\t900\t0.30\nNone\t0.1000\t0.2625\n' > "$RES/sh-20251231-000000-1/cost.tsv"
: > "$STUB_LOG"; printf '0\n' > "$CODES"; rm -f "$T/counter"
run_retry "${RETRY_COMMON[@]}" "${RETRY_RUN[@]}"
assert_contains "$RETOUT" "0.2625 USD over 1 run(s)" "the two-line file counts with the worst case, not with its second line"

echo "validation:"
run_retry --regions "eu-central-1;x" --attempts 1 --wait 0 --budget-usd 1 --suite cpp "${RETRY_RUN[@]}"; assert_eq "$RC" 2 "bad region refused"
run_retry --regions eu-central-1 --attempts 1 --wait 0 --budget-usd 1 --suite default "${RETRY_RUN[@]}"; assert_eq "$RC" 2 "suite other than cpp refused"
run_retry "${RETRY_COMMON[@]}" -- --instance-type g4dn.xlarge --max-price 0.35 --max-minutes 45 --region us-east-1; assert_eq "$RC" 2 "--region after -- refused"
echo "the uv shebang (when uv is installed):"
if command -v uv > /dev/null; then
  RC=0; out=$("$SRC_CLOUD/spot-retry.py" --status 2>&1) || RC=$?
  assert_eq "$out" "not running" "run through its shebang with uv --frozen --script"
else
  echo "  (uv not installed: skipped)"
fi
finish_test
