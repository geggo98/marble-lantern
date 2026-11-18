#!/bin/zsh
# run-arena-variants.sh in a sandbox with stub gradlew and java: a result with another header is refused (exit 2), a
# result with the same header is reused, --force plays again, and the exit code 130/143 of the Java process is passed on.
# usage: scripts/offline/test/test_run_arena_variants.sh      exit 0 if all pass
set -u
here=${0:A:h}
src=${here:h}/run-arena-variants.sh
strat=${here:h:h:h}/search/src/main/java/sternhalma/search/Strategies.java
T=$(mktemp -d "${TMPDIR:-/tmp}/arenatest.XXXXXX"); trap 'rm -rf $T' EXIT
mkdir -p $T/scripts/offline $T/search/src/main/java/sternhalma/search $T/bin $T/tables $T/build/offline/arena
cp $src $T/scripts/offline/; cp $strat $T/search/src/main/java/sternhalma/search/
: > $T/tables/rows1.bin
printf '#!/bin/sh\nexit 0\n' > $T/gradlew
printf '#!/bin/sh\necho "java $*" >> "%s/java.log"\nif [ -n "${STUB_JAVA_RC:-}" ]; then exit $STUB_JAVA_RC; fi\necho "RESULT stub"\n' $T > $T/bin/java
chmod +x $T/gradlew $T/bin/java
run() { (cd $T && PATH=$T/bin:$PATH STERNHALMA_TABLE_DIR=$T/tables scripts/offline/run-arena-variants.sh "$@" 2>&1); }
fails=0 checks=0
check() { checks=$((checks+1)); if [[ $1 == "$2" ]]; then echo "  ok   $3"; else fails=$((fails+1)); echo "  FAIL $3 (expected '$2', got '$1')" >&2; fi }
javacalls() { [[ -f $T/java.log ]] && wc -l < $T/java.log | tr -d ' ' || echo 0; }

out=$(run t rows2 5 1); check $? 0 "first run plays"; check "$(javacalls)" 1 "java ran once"
out=$(run t rows2 5 1); check $? 0 "same parameters: reused"; check "$(javacalls)" 1 "java not run again"
out=$(run t estimate2 5 1); rc=$?; check $rc 2 "other VARIANT: exit 2"; [[ $out == *"other parameters"* ]]; check $? 0 "message names the cause"
out=$(run --play exact t rows2 5 1); check $? 2 "other --play: exit 2"
out=$(run --nodes 999 t rows2 5 1); check $? 2 "other --nodes: exit 2"
out=$(run --threads 2 t rows2 5 1); check $? 2 "other --threads: exit 2"
check "$(javacalls)" 1 "java still ran once"
out=$(run --force --nodes 999 t rows2 5 1); check $? 0 "--force plays again"; check "$(javacalls)" 2 "java ran a second time"
for c in 130 143; do
  rm -f $T/build/offline/arena/*(N)
  out=$(STUB_JAVA_RC=$c run u rows2 5 1); check $? $c "java exit $c is passed on"
done
echo "$(basename $0): $((checks-fails)) of $checks checks ok"; (( fails == 0 ))
