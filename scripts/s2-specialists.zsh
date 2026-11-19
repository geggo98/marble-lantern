#!/bin/zsh
# usage: scripts/s2-specialists.zsh [--status]
# Task S2: the single-variant specialists data/shared/spec-<v> against the shared net data/shared/gen0-shared, on the ladders
# and seeds of the shared-net results in data/shared/eval (those are reused, not replayed). A specialist is played once its
# training is over: the next "=== ... specialist" line or "P5 done" in data/shared/p5.log (candidate.safetensors is rewritten
# at every checkpoint, so its existence proves nothing). Output: $S2_DATA/<variant>/ (one directory per opponent rung).
# Resumable (a finished stage has DONE; MctsMatch and ThreeSeatMatch resume inside), one run at a time (flock, exit 75).
# Run detached:  nohup scripts/s2-specialists.zsh > $S2_DATA/s2.log 2>&1 &    (130/143 = aborted)
# Report: scripts/s2-report.zsh
set -u
root=${0:A:h:h}
sh=${S2_SHARED:-/Users/stefan/tmp/stern-halma/data/shared}
data=${S2_DATA:-$sh/s2}
threads=${S2_THREADS:-8}
mkdir -p $data
if [[ ${1:-} == --status ]]; then
  perl -MFcntl=:flock -e 'open L,">>","$ARGV[0]/.lock"; print flock(L,LOCK_EX|LOCK_NB) ? "not running\n" : "running\n"' $data
  tail -n 5 $data/s2.log 2>/dev/null; exit 0
fi
if [[ -z ${S2_LOCKED:-} ]]; then
  export S2_LOCKED=1
  exec perl -MFcntl=:flock -e 'open L,">>",shift or die; flock(L,LOCK_EX|LOCK_NB) or do { print STDERR "already running\n"; exit 75 }; exec @ARGV or die' $data/.lock $0 "$@"
fi
stop=0; trap 'stop=1' INT TERM
variants=(2p-long 3p-adjacent 3p-long)
ready() {  # $1 = next variant or "" (the last one waits for P5 done)
  if [[ -n $1 ]] && grep -q "specialist $1\$" $sh/p5.log; then return 0; fi
  grep -q '^P5 done' $sh/p5.log
}
# stage DIR COMMAND...: run once, DONE marks success
stage() {
  local d=$1; shift
  if [[ -f $d/DONE ]]; then print -u2 "SKIP $d"; return 0; fi
  mkdir -p $d; print -u2 "=== $(date +%H:%M:%S) START $d"
  devenv shell -- "$@" >$d/result.txt 2>$d/stderr.log </dev/null
  local rc=$?
  if (( rc == 0 )); then touch $d/DONE; print -u2 "DONE $d: $(tail -n 1 $d/result.txt | cut -c1-200)"
  elif (( rc == 130 || rc == 143 )); then stop=1; print -u2 "aborted rc=$rc"
  else print -u2 "ERROR rc=$rc $d, see $d/stderr.log"; fi
  return $rc
}
err=0; i=0
for v in $variants; do
  i=$((i+1)); next=${variants[$((i+1))]:-}
  until ready $next; do (( stop )) && break 2; sleep 60; done
  (( stop )) && break
  net=$sh/spec-$v/gen-0000/candidate.safetensors
  [[ -s $net ]] || { print -u2 "$v: no specialist net, skipped"; err=$((err+1)); continue; }
  if [[ $v == 2p-long ]]; then
    stage $data/$v $root/scripts/mctsmatch.sh --out $data/$v --net $net --subject mcts:sims=200 --vs ab:1600 --vs ab:6400 \
      --jump long --pairs 100 --seed 3002 --threads $threads --progress-seconds 60 || err=$((err+1))
  else
    stage $data/$v $root/scripts/threeseatmatch.sh --out $data/$v --candidate mcts:sims=200,race=0 --net $net \
      --vs ab3:nodes=5000 --vs ab3:nodes=20000 --jump ${v#3p-} --openings 100 --seed 4001 || err=$((err+1))
  fi
done
print -u2 "SUMMARY: $err error"
(( stop )) && exit 130
(( err )) && exit 1
exit 0
