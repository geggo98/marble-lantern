#!/usr/bin/env bash
# aws-spot.sh: no capacity and no quota end with exit 4 (nothing was launched) and the cleanup runs; other failures end with 1.
set -u
# shellcheck disable=SC1091
. "$(dirname "$0")/lib.sh"
make_sandbox
export STUB_PROFILES=known-profile

for code in InsufficientInstanceCapacity MaxSpotInstanceCountExceeded VcpuLimitExceeded SpotMaxPriceTooLow; do
  : > "$STUB_LOG"
  STUB_RUN_ERROR=$code PROFILE=known-profile run_spot run --instance-type g4dn.xlarge --max-price 0.35 --max-minutes 45 --yes
  assert_eq "$RC" 4 "$code: exit 4"
  log=$(cat "$STUB_LOG")
  assert_contains "$log" "delete-security-group" "$code: the security group was deleted"
  assert_contains "$log" "delete-key-pair" "$code: the key pair was deleted"
  assert_contains "$OUTTXT" "nothing left" "$code: the verification reports a clean account"
  assert_eq "$(find "$OUT" -name cost.tsv | wc -l | tr -d ' ')" 0 "$code: nothing launched, no cost.tsv"
done

echo "another error is exit 1:"
: > "$STUB_LOG"
STUB_RUN_ERROR=UnauthorizedOperation PROFILE=known-profile run_spot run --instance-type g4dn.xlarge --max-price 0.35 --max-minutes 45 --yes
assert_eq "$RC" 1 "UnauthorizedOperation: exit 1"
assert_contains "$OUTTXT" "UnauthorizedOperation" "the error text of aws is shown"
finish_test
