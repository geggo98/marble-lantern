#!/bin/zsh
# data/net5/driver.zsh [--status]: phase 1 of the 5-row network plan, steps 1 to 7, resumable.
# Exit: 0 all steps done, 1 a step failed or a gate said no-go, 75 already running, 130/143 stopped.
set -u
setopt pipefail
cd <repository> || exit 1
D=data/net5
mkdir -p $D/logs $D/eval
lockf=$D/.driver.lock
statusf=$D/.driver.status

# Lock through perl: flock(1) does not exist on macOS. The kernel frees the lock when the process dies (also kill -9).
if [[ -z ${NET5_LOCKED:-} ]]; then
  export NET5_LOCKED=1
  exec perl -MFcntl=:flock -e '
    $^F = 10; my ($lock, $status, $mode, @cmd) = @ARGV;
    open my $lk, ">>", $lock or die "lock: $!";
    if (!flock $lk, LOCK_EX | LOCK_NB) {
      open my $s, "<", $status; my $st = <$s> // "?"; chomp $st;
      my ($pid) = $st =~ /pid=(\d+)/; my $et = $pid ? `ps -o etime= -p $pid 2>/dev/null` : ""; $et =~ s/^\s+|\s+$//g;
      print STDERR "net5: running: $st, for ", ($et || "? (process gone)"), "\n"; exit 75;
    }
    if ($mode eq "--status") { print "net5: not running\n"; exit 0 }
    exec @cmd or die "exec: $!";' -- $lockf $statusf "${1:-run}" zsh $0:A
fi

R5='ab:eval=linear-r3,pvs=1,killers=1,history=1,lmr=2,asp=1,ttrep=1,mate=1,partial=1,fut=1,futk=20,fut2=1,rows=5'
T=14                                     # threads: 16 cores, two left for the system
TEACHER=$D/teacher/r5-2p-adjacent
GEN0=$D/gen0-small/gen-0000/candidate.safetensors
EVAL='mcts:sims=200,batch=1,cpuct=1.0,noise=0,temp=0,race=0'
stop=0; trap 'stop=130' INT; trap 'stop=143' TERM
typeset -i ok=0 skip=0
t0=$SECONDS

say() { print -u2 "net5: $*  (elapsed $(( SECONDS - t0 )) s)"; }
state() { print "pid=$$ start=$start step=$1" > $statusf.tmp && mv $statusf.tmp $statusf; }
start=$(date '+%Y-%m-%dT%H:%M:%S')

# Energy measurement (scripts/energy/README.md). A failing marker or sampler never stops the training.
export ENERGY_NAME=net5 MARK_SOFT=1
mark() { python3 scripts/energy/mark.py "$@" }
python3 scripts/energy/sampler.py --detach >/dev/null 2>&1    # exit 75 = already running: fine
python3 scripts/energy/sampler.py --status >/dev/null 2>&1; (( $? == 75 )) || say "WARNING: the energy sampler is not running; no energy data"

# run STEP CMD...: the tool under +errlog; its exit code decides; 130/143 end the driver.
run() {
  local step=$1; shift
  state $step; say "$step: $*"
  mark start $step
  +errlog --dir $D/logs --tail 20 -- "$@"; local rc=$?
  mark end $step
  (( stop )) && exit $stop
  (( rc == 130 || rc == 143 )) && exit $rc
  (( rc == 0 )) || { say "$step FAILED with exit $rc (stderr file: see the [errlog] line above); rerun after the fix"; exit 1; }
  ok+=1
}

# once STEP FILE CMD...: a one-shot tool whose stdout is the result; written atomically, skipped when present.
once() {
  local step=$1 out=$2; shift 2
  if [[ -s $out ]]; then skip+=1; say "$step SKIP ($out exists)"; return; fi
  run $step "$@" > $out.tmp && mv $out.tmp $out
}

nogo() { say "NO-GO: $*"; exit 1; }

# 0 baseline: 10 idle minutes before the first step, once per run. Leave the machine alone meanwhile.
if [[ ! -e $D/.baseline-done ]]; then
  state baseline; say "baseline: 10 idle minutes; leave the machine alone"
  mark start baseline
  sleep 600 & bl=$!; wait $bl
  mark end baseline
  (( stop )) && { kill $bl 2>/dev/null; exit $stop; }
  touch $D/.baseline-done
fi

# 1 pilot: move-limit share below 1 %
once pilot $D/pilot.txt scripts/selfplay.sh pilot --rows 5 --games 20 --threads $T --seed 500 \
  --teacher-depth 2 --teacher-alpha 0.7,0.3,127
share=$(perl -ne 'print $1 if /move limit (\d+)% of/' $D/pilot.txt)
[[ -n $share ]] || nogo "pilot.txt has no move-limit share: $(cat $D/pilot.txt)"
(( share < 1 )) || nogo "pilot: $share % of the games reached the move limit (gate: below 1 %)"

# 2 ladder sanity check: ab:6400 beats ab:1600 with a score above 0.6
once ladder $D/ladder-check.txt scripts/arena.sh --a "$R5,nodes=6400" --b "$R5,nodes=1600" --pairs 100 \
  --seed 4101 --threads $T
score=$(perl -ne 'print $1 if /^score\(A\)=([0-9.]+)/' $D/ladder-check.txt)
[[ -n $score ]] || nogo "ladder-check.txt has no score line"
perl -e 'exit !($ARGV[0] > 0.6)' $score || nogo "ladder: ab:6400 scored $score against ab:1600 (gate: above 0.6)"

# 3 teacher data (resumable; a rerun skips finished shards)
run teacher scripts/selfplay.sh run --out $TEACHER --rows 5 --games 8000 --seed 501 --threads $T --nodes 5000 \
  --teacher-depth 2 --teacher-alpha 0.7,0.3,127

# 4 Gen0 (resumable from its newest checkpoint)
run gen0 scripts/gen0.sh run $TEACHER $D/gen0-small --planes classic1 --rows 5 --preset small --trainer gpu \
  --steps 40000 --batch 256 --seed 1 --checkpoint-steps 200

# 5 E1: score >= 0.6 against ab:6400 (pairs files make it resumable; the summary is printed again on a rerun)
run e1 scripts/mctsmatch.sh --out $D/eval/e1 --net $GEN0 --subject $EVAL --vs ab:1600 --vs ab:6400 --vs ab:25600 \
  --pairs 200 --seed 3001 --threads $T --rows 5 > $D/e1.txt
e1=$(perl -ne 'print $1 if /vs ab:6400: .*score ([0-9.]+)/' $D/e1.txt)
[[ -n $e1 ]] || nogo "e1.txt has no line for ab:6400"
perl -e 'exit !($ARGV[0] >= 0.6)' $e1 || nogo "E1: Gen0 scored $e1 against ab:6400 (gate: at least 0.6)"

# 6 loop, one generation per call, with the stop rule
decision() { perl -ne 'print $1 if /"decision":"(\w+)"/' $D/loop-small/gen-$(printf %04d $1)/decision.json 2>/dev/null; }
for g in {1..10}; do
  run loop-$g scripts/loop.sh run $D/loop-small --init $GEN0 --rows 5 --race 0 --s1 0 --ladder-spec "$R5" \
    --generations $g --games 2500 --sims 200 --lr 3e-4 --lr-final 1e-5 --reuse 20 --window 1500000 \
    --gate-pairs 200 --promote-z 1.28 --baseline-rung 25600 --baseline-pairs 100 --teacher $TEACHER \
    --trainer gpu --evaluator gpu --threads $T --ladder 1600,6400,25600 --strength-pairs 100
  if (( g >= 6 )) && [[ $(decision $g) == rejected && $(decision $(( g - 1 ))) == rejected ]]; then
    say "loop: generations $(( g - 1 )) and $g rejected; stop rule reached"; break
  fi
done

# 7 E2: the best loop network against Gen0, 400 pairs
best=0
for f in $D/loop-small/gen-*/decision.json; do
  [[ $(perl -ne 'print $1 if /"decision":"(\w+)"/' $f) == promoted ]] && best=${${f:h:t}#gen-}
done
BEST=$D/loop-small/gen-$best/candidate.safetensors
if [[ $best == 0 || $best == 0000 ]]; then
  say "E2: no generation was promoted; the Gen0 network stays the result"
else
  run e2 scripts/mctsmatch.sh --out $D/eval/e2 --net $BEST --subject $EVAL --vs "$EVAL,net=$GEN0" \
    --pairs 400 --seed 3002 --threads $T --rows 5 > $D/e2.txt
  say "E2 ($BEST against Gen0): $(cat $D/e2.txt)"
fi
say "done: $ok ok, $skip skip, 0 error; nothing more to do"
python3 scripts/energy/report.py || say "WARNING: the energy report failed (data/energy/net5.csv is intact; call report.py again)"
python3 scripts/energy/sampler.py --stop
rm -f $statusf
