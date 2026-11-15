#!/bin/zsh
# Stub test of bench-mac.sh: Java and C++ stubs print the log lines that the session checks; the result file must exist only for a valid
# session (equal games), never under the final name after a failed check, and must not overwrite an earlier result of the same name.
# usage: cpp/scripts/test_bench_mac.sh        (macOS: the script reads sysctl and pmset)
emulate -L zsh
setopt no_unset pipe_fail
HERE=${0:A:h}
T=$(mktemp -d "${TMPDIR:-/tmp}/bench-mac-test.XXXXXX") || exit 1
trap 'rm -rf $T' EXIT
fails=0; checks=0
check() { (( checks++ )); if [[ $1 == $2 ]]; then print "  ok   $3"; else (( fails++ )); print -u2 "  FAIL $3 (expected '$2', got '$1')"; fi }

mkdir -p $T/cpp/scripts $T/cpp/build/release-hardened $T/cpp/build/release $T/cpp/golden $T/cpp/results $T/tmp
cp $HERE/bench-mac.sh $T/cpp/scripts/
: > $T/cpp/golden/MANIFEST.tsv
for p in release-hardened release; do : > $T/cpp/build/$p/CMakeCache.txt; done
stub() {   # stub <file> <nodes expression>
  cat > $1 <<STUB
#!/bin/sh
echo "rep 1: A 1000 nodes/CPU-s"; echo "rep 2: A 1000 nodes/CPU-s"; echo "rep 3: A 1000 nodes/CPU-s"
echo "RESULT nps_a=1000 cpu_ms_a=1.0 nps_b=1000 cpu_ms_b=1.0 ratio=1.00 identical=true"
echo "golden: side A checked against golden"; echo "golden: side B checked against golden"
echo "A games: $2 nodes, 100 moves, 5 CPU-ms, 5 wall-ms: 1000 nodes/CPU-s, 1.0 CPU-ms/move"
STUB
  chmod +x $1
}
stub $T/java-stub 5000
print -r -- "$T/java-stub" > $T/cpp/build/java-search.cmd
run() {   # run <nodes of the C++ stubs>; sets rc, out
  stub $T/cpp/build/release-hardened/search_bench $1; stub $T/cpp/build/release/search_bench $1
  rc=0; log=$(env TMPDIR=$T/tmp zsh $T/cpp/scripts/bench-mac.sh --no-gate --games 2 --rounds 1 --specs best --out $T/result.txt 2>&1) || rc=$?
}

print "equal games:"
run 5000
check $rc 0 "exit 0"
[[ -s $T/result.txt ]] && check ok ok "the result file exists" || check missing ok "the result file exists"
left=($T/result.txt.tmp.*(N)); check ${#left} 0 "no temporary file is left"
print "valid" > $T/result.txt

print "different games (C++ played other nodes than Java):"
run 4999
check $rc 4 "exit 4: the session is invalid"
check "$(<$T/result.txt)" valid "the earlier result file is untouched"
left=($T/result.txt.tmp.*(N)); check ${#left} 0 "no temporary file is left"
[[ $log == *"result written"* ]] && check "written" "not written" "no 'result written' message" || check ok ok "no 'result written' message"
rm -f $T/result.txt
run 4999
[[ -e $T/result.txt ]] && check exists "absent" "no result file under the final name" || check absent absent "no result file under the final name"

print "bench-mac test: $((checks - fails)) of $checks checks ok"
(( fails == 0 ))
