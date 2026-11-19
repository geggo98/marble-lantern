#!/bin/zsh
# usage: scripts/b3-batch-report.zsh   -- per finished cell: result line, and PairedReport against batch 1 (same sims, same rung)
set -u
root=${0:A:h:h}
data=${B3_DATA:-/Users/stefan/tmp/stern-halma/data/b3}
for d in $data/s*-b*-ab*(N/); do
  [[ -f $d/DONE && ! -f $d/SKIPPED-by-budget ]] || continue
  name=${d:t}; sims=${name%%-*}; rung=${name##*-}; b=${${name#*-b}%%-*}
  print "== $name"; tail -n 1 $d/result.txt | cut -c1-400
  base=$data/$sims-b1-$rung; base=$data/${sims}-b1-${rung}
  if [[ $b != 1 && -f $base/DONE ]]; then
    $root/scripts/threeseatmatch.sh paired $base/pairs-*.jsonl $d/pairs-*.jsonl 2>&1 | sed 's/^/   paired (batch1 - batch'$b'): /'
  fi
done
