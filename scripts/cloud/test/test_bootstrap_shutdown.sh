#!/usr/bin/env bash
# Finding 5: the shutdown after the run never lies behind the hard deadline ("shutdown -h +N" REPLACES the schedule).
set -u
# shellcheck disable=SC1091
. "$(dirname "$0")/lib.sh"
T=$(mktemp -d "${TMPDIR:-/tmp}/cloudtest.XXXXXX"); trap 'rm -rf "$T"' EXIT
SDLOG=$T/shutdown.log; DL=$T/HARD_DEADLINE

# the script's functions only (it returns when sourced); date and shutdown are shell functions that win over the commands
# shellcheck disable=SC1091
. "$SRC_CLOUD/bootstrap.sh"
NOW=1000
date() { echo "$NOW"; }
shutdown() { echo "$*" >> "$SDLOG"; }

record_hard_deadline 60 "$DL"
assert_eq "$(cat "$DL")" 4600 "deadline = now + 60 min"

try() {  # NOW -> what was scheduled
  NOW=$1; : > "$SDLOG"
  schedule_final_shutdown "$DL" 8 > "$T/msg" 2>&1
  tr '\n' '|' < "$SDLOG"
}
assert_eq "$(try 1000)" "-h +8 sternhalma bootstrap: finished|" "60 min left: grace 8 min"
assert_eq "$(try $((4600 - 481)))" "-h +8 sternhalma bootstrap: finished|" "8 min 1 s left (9 rounded up): grace 8 min"
assert_eq "$(try $((4600 - 480)))" "" "exactly 8 min left: the hard timer stays"
assert_eq "$(try $((4600 - 180)))" "" "3 min left: the hard timer stays (no +8 that would replace it)"
assert_contains "$(cat "$T/msg")" "left in place" "and says so"
assert_eq "$(try $((4600 - 20)))" "" "20 s left: the hard timer stays"
assert_eq "$(try 4650)" "-h now|" "deadline passed: now"
rm -f "$DL"
assert_eq "$(try 1000)" "-h +8 sternhalma bootstrap: finished|" "no deadline file: grace time"

echo "argument validation of the whole script (before anything runs):"
mkdir -p "$T/bin"; printf '#!/bin/bash\necho "$*" >> "%s"\n' "$SDLOG" > "$T/bin/shutdown"; chmod +x "$T/bin/shutdown"
for bad in "--feed-seconds 6;x" "--grace-minutes 1;x" "--hard-minutes 4" "--hard-minutes 1;x"; do
  : > "$SDLOG"
  rc=0
  # shellcheck disable=SC2086
  PATH="$T/bin:$PATH" bash "$SRC_CLOUD/bootstrap.sh" --backend none --hard-minutes 10 $bad > /dev/null 2>&1 || rc=$?
  assert_eq "$rc" 2 "refused: $bad"
  assert_eq "$(wc -c < "$SDLOG" | tr -d ' ')" 0 "no shutdown was scheduled by a refused call: $bad"
done
finish_test
