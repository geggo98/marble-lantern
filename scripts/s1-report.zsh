#!/bin/zsh
# usage: scripts/s1-report.zsh
# Task S1: one line per finished cell (data/s1: race=0 browser configuration; data/b3: default player, race=1) and, where
# both exist for the same sims, batch and rung, the paired difference race=1 - race=0 (PairedReport: mean(A - B), A = race on).
set -u
root=${0:A:h:h}
s1=${S1_DATA:-/Users/stefan/tmp/stern-halma/data/s1}
b3=${B3_DATA:-/Users/stefan/tmp/stern-halma/data/b3}
print "== cells"
for d in $b3/s(200|800|50)-b(1|4)-ab*(N/) $s1/s*-b*-race*-ab*(N/); do
  [[ -f $d/DONE && -s $d/result.txt ]] || continue
  print "${d:h:t}/${d:t}: $(tail -n 1 $d/result.txt | cut -c1-120)"
done
print; print "== race on (b3) minus race off (s1), paired by pair index, same seed 3001"
for d in $s1/s*-b*-race0-ab*(N/); do
  [[ -f $d/DONE ]] || continue
  n=${d:t}; sims=${${n%%-*}#s}; b=${${n#*-b}%%-*}; r=${n##*-}
  on=$b3/s$sims-b$b-$r
  [[ -f $on/DONE ]] || on=$s1/s$sims-b$b-race1-$r
  [[ -f $on/DONE ]] || continue
  print -- "-- sims=$sims batch=$b $r"
  $root/scripts/threeseatmatch.sh paired $on/pairs-*.jsonl $d/pairs-*.jsonl 2>&1
done
