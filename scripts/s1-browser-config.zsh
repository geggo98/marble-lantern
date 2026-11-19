#!/bin/zsh
# usage: scripts/s1-browser-config.zsh [--status]
# Task S1: strength of the browser MCTS configuration (loop3 gen-0013 SMALL net, race module off) against ladder rungs.
# Cell = "sims batch race rung"; 100 pairs, seed 3001, as data/b3 (race=1 cells there are the default-player baselines).
# Resumable: a finished cell has DONE, a half-finished one resumes inside MctsMatch. One run at a time (flock, exit 75).
# Run detached:  nohup scripts/s1-browser-config.zsh > $S1_DATA/sweep.log 2>&1 &    (130/143 = aborted)
set -u
root=${0:A:h:h}
data=${S1_DATA:-/Users/stefan/tmp/stern-halma/data/s1}
net=${S1_NET:-/Users/stefan/tmp/stern-halma/data/loop3/gen-0013/candidate.safetensors}
threads=${S1_THREADS:-8}
mkdir -p $data
if [[ ${1:-} == --status ]]; then
  perl -MFcntl=:flock -e 'open L,">>","$ARGV[0]/.lock"; print flock(L,LOCK_EX|LOCK_NB) ? "not running\n" : "running\n"' $data
  tail -n 5 $data/sweep.log 2>/dev/null; exit 0
fi
if [[ -z ${S1_LOCKED:-} ]]; then   # re-exec under an flock held by the whole run; the kernel frees it on any death
  export S1_LOCKED=1
  exec perl -MFcntl=:flock -e 'open L,">>",shift or die; flock(L,LOCK_EX|LOCK_NB) or do { print STDERR "already running\n"; exit 75 }; exec @ARGV or die' $data/.lock $0 "$@"
fi
stop=0; trap 'stop=1' INT TERM
# order = priority. sims batch race rung
cells=(
  "200 1 0 25600" "200 1 0 102400" "200 4 0 25600" "200 4 0 102400"
  "200 1 0 409600" "200 4 0 409600" "200 1 1 409600"
  "50 1 0 6400" "50 1 0 25600" "50 1 0 102400"
  "800 1 0 102400" "800 1 0 409600" "800 1 0 1638400"
)
n=${#cells}; i=0; ok=0; skip=0; err=0; t0=$SECONDS
for c in $cells; do
  set -- ${=c}; sims=$1; b=$2; race=$3; r=$4
  (( stop )) && break
  i=$((i+1)); out=$data/s$sims-b$b-race$race-ab$r
  if [[ -f $out/DONE ]]; then skip=$((skip+1)); print -u2 "[$i/$n] $c SKIP"; continue; fi
  mkdir -p $out
  print -u2 "[$i/$n] $c START elapsed $((SECONDS-t0))s ok=$ok skip=$skip err=$err"
  devenv shell -- $root/scripts/mctsmatch.sh --out $out --net $net --subject "mcts:sims=$sims,batch=$b,race=$race" \
    --vs ab:$r --pairs 100 --seed 3001 --threads $threads --progress-seconds 60 >$out/result.txt 2>$out/stderr.log
  rc=$?
  if (( rc == 0 )); then touch $out/DONE; ok=$((ok+1)); print -u2 "[$i/$n] $c DONE: $(tail -n 1 $out/result.txt | cut -c1-200)"
  elif (( rc == 130 || rc == 143 )); then stop=1; print -u2 "aborted rc=$rc"
  else err=$((err+1)); print -u2 "[$i/$n] $c ERROR rc=$rc, see $out/stderr.log"; fi
done
print -u2 "SUMMARY: $ok ok, $skip skip, $err error, $((n-ok-skip-err)) left, elapsed $((SECONDS-t0))s"
(( stop )) && exit 130
(( err )) && exit 1
exit 0
