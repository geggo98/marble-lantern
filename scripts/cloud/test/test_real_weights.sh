#!/usr/bin/env bash
# aws-spot.sh --real-weights: validated, hashed here, uploaded, hashed again on the instance; a difference stops the run; the file is
# never named in the user data or the results.
set -u
# shellcheck disable=SC1091
. "$(dirname "$0")/lib.sh"
make_sandbox
export STUB_PROFILES=known-profile
W=$T/weights; mkdir -p "$W"
head -c 5000 /dev/urandom > "$W/candidate.safetensors"
SHA=$(perl -MDigest::SHA -e 'print Digest::SHA->new(256)->addfile($ARGV[0])->hexdigest' "$W/candidate.safetensors")
BASE=(run --instance-type g4dn.xlarge --max-price 0.35 --max-minutes 45 --suite cpp)
clear_out() { find "${OUT:?}" -mindepth 1 -delete; }

echo "dry run:"
PROFILE="" run_spot "${BASE[@]}" --dry-run --real-weights "$W/candidate.safetensors"
assert_eq "$RC" 0 "accepted"
assert_contains "$OUTTXT" "--real-weights-sha256 $SHA" "the user data carries the hash"
assert_contains "$OUTTXT" "real-weights.safetensors" "the upload is in the plan"
assert_not_contains "$(perl -ne 'if (/^--- user data/) { $on = 1; next } if (/^---$/) { $on = 0 } print if $on' "$T/out.txt")" "$W" "the path is not in the user data"
assert_eq "$(grep -c '^scp ' "$STUB_LOG")" 0 "a dry run uploads nothing"

echo "refused:"
check_refused() { PROFILE="" run_spot "$@"; assert_eq "$RC" 2 "refused: ${*: -2}"; }
check_refused "${BASE[@]}" --dry-run --real-weights "$W/missing.safetensors"
cp "$W/candidate.safetensors" "$W/candidate.bin"; check_refused "${BASE[@]}" --dry-run --real-weights "$W/candidate.bin"
: > "$W/empty.safetensors"; check_refused "${BASE[@]}" --dry-run --real-weights "$W/empty.safetensors"
ln -s "$W/candidate.safetensors" "$W/link.safetensors"; check_refused "${BASE[@]}" --dry-run --real-weights "$W/link.safetensors"
head -c 17000000 /dev/zero > "$W/big.safetensors"; check_refused "${BASE[@]}" --dry-run --real-weights "$W/big.safetensors"
check_refused run --dry-run --instance-type g4dn.xlarge --max-price 0.35 --max-minutes 45 --real-weights "$W/candidate.safetensors"   # no --suite cpp

echo "run with equal hashes:"
write_fast_clock
: > "$STUB_LOG"
STUB_REMOTE_SHA=$SHA PROFILE=known-profile run_spot "${BASE[@]}" --real-weights "$W/candidate.safetensors" --yes
assert_eq "$RC" 0 "run ok"
assert_contains "$(grep '^scp ' "$STUB_LOG")" "real-weights.safetensors" "uploaded to the instance"
assert_contains "$(grep '^ssh ' "$STUB_LOG")" "sha256sum" "hashed again on the instance"
f=$(find "$OUT" -name real-weights.sha256 | head -1)
if [ -n "$f" ]; then pass "real-weights.sha256 written"; else fail "no real-weights.sha256"; f=/dev/null; fi
assert_contains "$(cat "$f")" "$SHA" "it holds the hash"
assert_not_contains "$(cat "$f")" "$W" "and no path"
assert_not_contains "$(cat "$T/out.txt")" "$W" "no path in the output"

echo "run with a hash that differs after the upload:"
clear_out; : > "$STUB_LOG"
STUB_REMOTE_SHA=1111111111111111111111111111111111111111111111111111111111111111 PROFILE=known-profile run_spot "${BASE[@]}" --real-weights "$W/candidate.safetensors" --yes
assert_eq "$RC" 1 "the run is stopped: exit 1"
assert_contains "$OUTTXT" "differs" "says why"
assert_contains "$(cat "$STUB_LOG")" "terminate-instances" "the instance is terminated"
assert_contains "$OUTTXT" "nothing left" "and nothing is left"
assert_eq "$(grep -c 'UPLOADED' "$STUB_LOG")" 0 "the instance was never told to start"
finish_test
