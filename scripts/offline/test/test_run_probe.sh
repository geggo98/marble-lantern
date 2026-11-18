#!/bin/zsh
# run-probe.sh in a sandbox with stub gradlew and java: the smoke run is skipped when the real run left shards, the runner
# script is written under a temporary name and renamed, and the runner reports one status line per source and ends with
# the worst exit code.
# usage: scripts/offline/test/test_run_probe.sh      exit 0 if all pass
set -u
here=${0:A:h}
T=$(mktemp -d "${TMPDIR:-/tmp}/probetest.XXXXXX"); trap 'rm -rf $T' EXIT
mkdir -p $T/scripts/offline $T/bin $T/tables
cp ${here:h}/run-probe.sh $T/scripts/offline/
: > $T/tables/rows1.bin
printf '#!/bin/sh\nexit 0\n' > $T/gradlew
cat > $T/bin/java <<'STUB'
#!/bin/sh
echo "java $*" >> "$STUB_DIR/java.log"
echo "rankprobe: 20 items in 12s, 100 lookups" >&2
case "$*" in *"--source random"*) [ -z "${STUB_RANDOM_FAILS:-}" ] || exit 3;; esac
exit 0
STUB
chmod +x $T/gradlew $T/bin/java
run() { (cd $T && STUB_DIR=$T PATH=$T/bin:$PATH scripts/offline/run-probe.sh --table-dir $T/tables --foreground --sources best,random --games 20 "$@" 2>&1); }
fails=0 checks=0
check() { checks=$((checks+1)); if [[ $1 == "$2" ]]; then echo "  ok   $3"; else fails=$((fails+1)); echo "  FAIL $3 (expected '$2', got '$1')" >&2; fi }
has() { [[ $1 == *"$2"* ]] && echo yes || echo no; }

out=$(run); rc=$?
check $rc 0 "first run ends with 0"
check "$(has $out 'smoke run: 20 games')" yes "first run plays the smoke run"
runner=$T/build/offline/probe/run-all.zsh
check "$([[ -x $runner ]] && echo yes || echo no)" yes "runner exists and is executable"
check "$(ls $T/build/offline/probe | grep -c 'run-all.zsh.tmp')" 0 "no temporary runner name is left"
check "$(grep -c '^set -e' $runner)" 1 "runner has set -e"
check "$(has $out 'status best exit=0')" yes "status line of source best"
check "$(has $out 'status random exit=0')" yes "status line of source random"

mkdir -p $T/build/offline/probe/best; : > $T/build/offline/probe/best/rlog-best-s33-00000.rlog.gz
out=$(run); check "$(has $out 'smoke run: 20 games')" no "shards of the real run exist: no smoke run"
check "$(has $out 'smoke skipped')" yes "the skip is said"

out=$(STUB_RANDOM_FAILS=1 run); rc=$?
check $rc 3 "the worst exit code of the sources is the exit code of the runner"
check "$(has $out 'status best exit=0')" yes "best still reported"
check "$(has $out 'status random exit=3')" yes "failing source reported with its code"
echo "$(basename $0): $((checks-fails)) of $checks checks ok"; (( fails == 0 ))
