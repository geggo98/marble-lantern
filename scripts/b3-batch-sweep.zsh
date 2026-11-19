#!/bin/zsh
# usage: scripts/b3-batch-sweep.zsh [--status]
# MCTS leaf-batching sweep (task B3): the loop3 gen-0013 SMALL net, sims x batch grid, each cell against two ladder rungs,
# 100 pairs, seed 3001. Resumable: a finished (cell, rung) is skipped, a half-finished one resumes inside MctsMatch.
# Run detached:  nohup scripts/b3-batch-sweep.zsh > $DATA/sweep.log 2>&1 &   (exit 75 = already running, 130/143 = aborted)
set -u
root=${0:A:h:h}
data=${B3_DATA:-/Users/stefan/tmp/stern-halma/data/b3}
net=${B3_NET:-/Users/stefan/tmp/stern-halma/data/loop3/gen-0013/candidate.safetensors}
threads=${B3_THREADS:-8}
mkdir -p $data
if [[ ${1:-} == --status ]]; then
  perl -MFcntl=:flock -e 'open L,">>","$ARGV[0]/.lock"; print flock(L,LOCK_EX|LOCK_NB) ? "not running\n" : "running\n"' $data
  tail -n 5 $data/sweep.log 2>/dev/null; exit 0
fi
if [[ -z ${B3_LOCKED:-} ]]; then   # re-exec under an flock held by the whole run; the kernel frees it on any death
  export B3_LOCKED=1
  exec perl -MFcntl=:flock -e 'open L,">>",shift or die; flock(L,LOCK_EX|LOCK_NB) or do { print STDERR "already running\n"; exit 75 }; exec @ARGV or die' $data/.lock $0 "$@"
fi
stop=0; trap 'stop=1' INT TERM
# order = priority: 200 sims full sweep, then 800, then 100
cells=()
for b in 1 4 8 16 32; do cells+=("200 $b"); done
for b in 1 8 32; do cells+=("800 $b"); done
for b in 1 16; do cells+=("100 $b"); done
rungs=(25600 102400)
n=$(( ${#cells} * ${#rungs} )); i=0; ok=0; skip=0; err=0; t0=$SECONDS
for c in $cells; do
  sims=${c% *}; b=${c#* }
  for r in $rungs; do
    (( stop )) && break 2
    i=$((i+1)); out=$data/s$sims-b$b-ab$r
    if [[ -f $out/DONE ]]; then skip=$((skip+1)); print -u2 "[$i/$n] s$sims b$b ab$r SKIP"; continue; fi
    mkdir -p $out
    print -u2 "[$i/$n] s$sims b$b ab$r START elapsed $((SECONDS-t0))s ok=$ok skip=$skip err=$err"
    devenv shell -- $root/scripts/mctsmatch.sh --out $out --net $net --subject "mcts:sims=$sims,batch=$b" \
      --vs ab:$r --pairs 100 --seed 3001 --threads $threads --progress-seconds 60 >$out/result.txt 2>$out/stderr.log
    rc=$?
    if (( rc == 0 )); then touch $out/DONE; ok=$((ok+1)); print -u2 "[$i/$n] s$sims b$b ab$r DONE: $(tail -n 1 $out/result.txt | cut -c1-200)"
    elif (( rc == 130 || rc == 143 )); then stop=1; print -u2 "aborted rc=$rc"
    else err=$((err+1)); print -u2 "[$i/$n] s$sims b$b ab$r ERROR rc=$rc, see $out/stderr.log"; fi
  done
done
print -u2 "SUMMARY: $ok ok, $skip skip, $err error, $((n-ok-skip-err)) left, elapsed $((SECONDS-t0))s"
(( stop )) && exit 130
(( err )) && exit 1
exit 0
