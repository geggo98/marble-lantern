#!/bin/zsh
# pack-tables.py with the real rows2.bin ($STERNHALMA_TABLE_DIR/rows2.bin; the test is skipped, exit 0 with a note, if there is none):
# --verify compares the packed file against the source it is given, a wrong call prints the usage and exits 2.
# usage: scripts/offline/test/test_pack_tables.sh      exit 0 if all pass
set -u
here=${0:A:h}
pack=${here:h}/pack-tables.py
src=${STERNHALMA_TABLE_DIR:-}/rows2.bin
[[ -f $src ]] || { echo "skipped: no rows2.bin (set STERNHALMA_TABLE_DIR)"; exit 0; }
T=$(mktemp -d "${TMPDIR:-/tmp}/packtest.XXXXXX"); trap 'rm -rf $T' EXIT
fails=0 checks=0
check() { checks=$((checks+1)); if [[ $1 == "$2" ]]; then echo "  ok   $3"; else fails=$((fails+1)); echo "  FAIL $3 (expected '$2', got '$1')" >&2; fi }

$pack $src --out $T/out >/dev/null; check $? 0 "pack"
$pack --verify $src --out $T/out >/dev/null 2>&1; check $? 0 "verify against the source it was packed from"
$pack --verify --out $T/out >/dev/null 2>&1; check $? 0 "verify without a source"
perl -e 'open F, "<", $ARGV[0]; binmode F; local $/; $_ = <F>; substr($_, 100, 1) = chr(ord(substr($_, 100, 1)) ^ 1); open O, ">", $ARGV[1]; binmode O; print O $_' $src $T/other.bin
msg=$($pack --verify $T/other.bin --out $T/out 2>&1 >/dev/null); rc=$?
check $rc 1 "verify against another file is a difference"
check "$([[ $msg == *"other.bin has sha256"* ]] && echo yes || echo no)" yes "the message names the file"
msg=$($pack --verify a b --out $T/out 2>&1 >/dev/null); rc=$?
check $rc 2 "wrong call: exit 2"
check "$([[ $msg == *"usage: pack-tables.py"* ]] && echo yes || echo no)" yes "wrong call: usage is printed"
echo "$(basename $0): $((checks-fails)) of $checks checks ok"; (( fails == 0 ))
