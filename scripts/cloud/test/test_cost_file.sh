#!/usr/bin/env bash
# aws-spot.sh writes cloud-results/<run id>/cost.tsv: suite, launched (ISO), seconds, price_usd_per_h, estimate_usd, worst_case_usd.
set -u
# shellcheck disable=SC1091
. "$(dirname "$0")/lib.sh"
make_sandbox
export STUB_PROFILES=known-profile
write_fast_clock
clear_out() { find "${OUT:?}" -mindepth 1 -delete; }

check_fields() {  # FILE -> prints "<lines>:<fields>" of its single line
  perl -ne '$n++; @f = split /\t/, $_, -1; chomp $f[-1]; $c = scalar @f; END { print "$n:$c" }' "$1"
}

echo "spot run with a known price:"
STUB_SPOT_PRICE=0.30 PROFILE=known-profile run_spot run --instance-type g4dn.xlarge --max-price 0.35 --max-minutes 45 --suite cpp --yes
assert_eq "$RC" 0 "run ends"
f=$(find "$OUT" -name cost.tsv | head -1)
if [ -n "$f" ]; then pass "cost.tsv exists"; else fail "no cost.tsv"; f=/dev/null; fi
assert_eq "$(check_fields "$f")" "1:6" "one line with six fields"
IFS=$'\t' read -r suite launched seconds price est worst < "$f"
assert_eq "$suite" cpp "field 1: suite"
case $launched in 20??-??-??T??:??:??Z) pass "field 2: ISO time ($launched)" ;; *) fail "field 2: not an ISO time: $launched" ;; esac
case $seconds in *[!0-9]*|'') fail "field 3: not an integer: $seconds" ;; *) pass "field 3: seconds ($seconds)" ;; esac
assert_eq "$price" 0.30 "field 4: price per hour (the stub prints the real two-line output: price, then None)"
assert_eq "$worst" 0.26 "field 6: worst case 0.35 x 45 / 60 = 0.26"
if perl -e 'exit($ARGV[0] > 0 && $ARGV[0] <= $ARGV[1] ? 0 : 1)' "$est" "$worst"; then pass "field 5: the estimate ($est) is above 0 and not above the worst case"; else fail "estimate $est"; fi
assert_eq "$(cat "$(dirname "$f")/suite.txt")" cpp "suite.txt next to it"

echo "price unknown: the estimate is the worst case:"
clear_out
PROFILE=known-profile run_spot run --instance-type g4dn.xlarge --max-price 0.35 --max-minutes 45 --yes
f=$(find "$OUT" -name cost.tsv | head -1)
IFS=$'\t' read -r suite launched seconds price est worst < "$f"
assert_eq "$suite" default "default suite named"
assert_eq "$price" unknown "price: unknown"
assert_eq "$est" "$worst" "estimate = worst case"

echo "on demand: the assumed price of --max-price, marked as such:"
clear_out
PROFILE=known-profile run_spot run --instance-type g4dn.xlarge --max-price 0.53 --max-minutes 45 --on-demand --yes
f=$(find "$OUT" -name cost.tsv | head -1)
IFS=$'\t' read -r suite launched seconds price est worst < "$f"
assert_eq "$price" 0.53-assumed "price: 0.53, marked as assumed (not a measured price)"
assert_eq "$worst" 0.40 "worst case 0.53 x 45 / 60"

echo "a run that is stopped (SIGTERM) still leaves a cost.tsv:"
clear_out; rm -f "$STUB_MARK"; unset STUB_CLOCK; rm -f "$STUB_BIN/date" "$STUB_BIN/sleep"
env -u AWS_ACCESS_KEY_ID PATH="$STUB_BIN:$PATH" AWS_PROFILE=known-profile AWS_REGION=eu-central-1 RESULTS_DIR="$OUT" \
  STUB_LOG="$STUB_LOG" STUB_MARK="$STUB_MARK" STUB_SSH_WAIT=3 \
  bash "$SPOT" run --instance-type g4dn.xlarge --max-price 0.35 --max-minutes 45 --suite cpp --yes > "$T/out-term.txt" 2>&1 &
pid=$!
for _ in $(seq 1 100); do [ -e "$STUB_MARK" ] && break; sleep 0.2; done
kill -TERM "$pid"; wait "$pid" 2> /dev/null
f=$(find "$OUT" -name cost.tsv | head -1)
if [ -n "$f" ]; then assert_eq "$(check_fields "$f")" "1:6" "cost.tsv with six fields after SIGTERM"; else fail "no cost.tsv after SIGTERM"; tail -15 "$T/out-term.txt" >&2; fi
finish_test
