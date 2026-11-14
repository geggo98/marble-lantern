#!/usr/bin/env bash
# Finding 6: results/ is fetched about 3 minutes before the hard deadline and once more at the end; --dry-run fetches nothing.
set -u
# shellcheck disable=SC1091
. "$(dirname "$0")/lib.sh"
make_sandbox

echo "dry run:"
PROFILE="" run_spot run --dry-run --instance-type c7i.xlarge --max-price 0.5 --max-minutes 20
assert_eq "$RC" 0 "dry run ok"
assert_eq "$(grep -c '^scp ' "$STUB_LOG")" 0 "dry run copies nothing"
assert_contains "$OUTTXT" "DRY-RUN" "dry run only prints"

echo "a run whose DONE never appears (a hung step), fast clock:"
write_fast_clock
: > "$STUB_LOG"; PROFILE=known-profile run_spot run --instance-type c7i.xlarge --max-price 0.5 --max-minutes 20 --yes
assert_eq "$RC" 0 "run ends (exit $RC)"
dirs=$(grep '^scp .* -r ' "$STUB_LOG" | perl -ne 'print "$1 " if m{(last-chance|final)/\s*$}')
assert_eq "$dirs" "last-chance final " "results/ fetched at last-chance, then at the end"
assert_contains "$(grep '^scp .* -r ' "$STUB_LOG" | head -1)" "ServerAliveCountMax" "scp has keep-alive limits"
finish_test
