#!/usr/bin/env bash
# aws-spot.sh: when the SSM parameter cannot be read and no --ami is given, the newest Deep Learning AMI by name is used (cuda only).
set -u
# shellcheck disable=SC1091
. "$(dirname "$0")/lib.sh"
make_sandbox
export STUB_PROFILES=known-profile

STUB_SSM_FAIL=1 PROFILE=known-profile run_spot preflight --instance-type g4dn.xlarge --backend cuda
assert_contains "$OUTTXT" "ami-$STUB_IMAGE_SUFFIX" "preflight: the AMI of the by-name search"
assert_contains "$(cat "$STUB_LOG")" "describe-images --owners amazon" "describe-images was called"
assert_contains "$(grep describe-images "$STUB_LOG")" "Deep Learning Base OSS Nvidia Driver GPU AMI (Ubuntu 22.04)*" "with the name pattern"
assert_contains "$(grep describe-images "$STUB_LOG")" "sort_by(Images,&CreationDate)[-1]" "newest first"

: > "$STUB_LOG"
PROFILE=known-profile run_spot preflight --instance-type g4dn.xlarge --backend cuda
assert_not_contains "$(cat "$STUB_LOG")" "describe-images" "the SSM parameter is read first: no search when it works"

: > "$STUB_LOG"
STUB_SSM_FAIL=1 PROFILE=known-profile run_spot preflight --instance-type c7i.xlarge --backend none
assert_not_contains "$(cat "$STUB_LOG")" "describe-images" "the none backend has no by-name search"
assert_contains "$OUTTXT" "could not read the AMI parameter" "and reports the finding"

: > "$STUB_LOG"
STUB_SSM_FAIL=1 STUB_IMAGE_ID=None PROFILE=known-profile run_spot preflight --instance-type g4dn.xlarge --backend cuda
assert_contains "$OUTTXT" "could not read the AMI parameter" "no image found: a finding"
assert_eq "$RC" 3 "exit 3"
finish_test
