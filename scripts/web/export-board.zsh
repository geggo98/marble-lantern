#!/bin/zsh
# Writes web/src/board/cells.generated.json: the 121 cells with id and axial (q, r).
# Source of truth: sternhalma.engine.Geometry. The rule is the same there: (q, r) is on the board if
# max(q, r, s) <= 4 or min(q, r, s) >= -4 with s = -q - r; ids run over r, then q, from -8 to 8.
# Usage: scripts/web/export-board.zsh            write the file
#        scripts/web/export-board.zsh --check    compare the committed file with a fresh one (exit 1 on a difference)
set -eu
root=${0:A:h:h:h}
out=$root/web/src/board/cells.generated.json

generate() {
  perl -e '
    my $id = 0; my @rows;
    for my $r (-8 .. 8) { for my $q (-8 .. 8) {
      my $s = -$q - $r;
      my ($max, $min) = ($q, $q);
      for ($r, $s) { $max = $_ if $_ > $max; $min = $_ if $_ < $min }
      next unless $max <= 4 or $min >= -4;
      push @rows, sprintf(qq({"id":%d,"q":%d,"r":%d}), $id++, $q, $r);
    } }
    die "expected 121 cells, got $id\n" unless $id == 121;
    print "[\n  ", join(",\n  ", @rows), "\n]\n";
  '
}

if [[ ${1:-} == --check ]]; then
  generate | cmp -s - $out && { print -u2 "cells.generated.json is up to date"; exit 0; }
  print -u2 "cells.generated.json differs from a fresh export"; exit 1
fi
mkdir -p ${out:h}
tmp=$out.tmp.$$
generate > $tmp
mv $tmp $out
print -u2 "wrote $out ($(perl -ne '$n++ if /"id"/; END { print $n }' $out) cells)"
