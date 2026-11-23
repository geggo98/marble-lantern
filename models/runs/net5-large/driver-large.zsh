#!/bin/zsh
# data/net5/driver-large.zsh [--status]: the LARGE (64x6) 5-row network, modelled on data/net5/driver.zsh, resumable.
# Steps: baseline (10 idle min), Gen0 LARGE on the teacher data, E1 gate, loop (up to 10 generations, stop rule), E2 (best
# against Gen0), H2H (best LARGE against SMALL gen-0009), E3 (best LARGE against the ab ladder up to 102,400 nodes).
# Exit: 0 all steps done, 1 a step failed or a gate said no-go, 75 already running, 130/143 stopped.
set -u
setopt pipefail
cd <repository> || exit 1
D=data/net5
mkdir -p $D/logs $D/eval
lockf=$D/.driver-large.lock
statusf=$D/.driver-large.status

# Lock through perl: flock(1) does not exist on macOS. The kernel frees the lock when the process dies (also kill -9).
if [[ -z ${NET5L_LOCKED:-} ]]; then
  export NET5L_LOCKED=1
  exec perl -MFcntl=:flock -e '
    $^F = 10; my ($lock, $status, $mode, @cmd) = @ARGV;
    open my $lk, ">>", $lock or die "lock: $!";
    if (!flock $lk, LOCK_EX | LOCK_NB) {
      open my $s, "<", $status; my $st = <$s> // "?"; chomp $st;
      my ($pid) = $st =~ /pid=(\d+)/; my $et = $pid ? `ps -o etime= -p $pid 2>/dev/null` : ""; $et =~ s/^\s+|\s+$//g;
      print STDERR "net5-large: running: $st, for ", ($et || "? (process gone)"), "\n"; exit 75;
    }
    if ($mode eq "--status") { print "net5-large: not running\n"; exit 0 }
    exec @cmd or die "exec: $!";' -- $lockf $statusf "${1:-run}" zsh $0:A
fi

ROOT=<repository>
# Gen0 needs --preset large, which main does not have yet: the worktree holds main b30673c plus that one switch case
# (uncommitted; the diff is data/net5-cpupower/gen0-large.patch). Every other step runs the scripts of this checkout.
WT=$ROOT/.worktrees/gen0-large
R5='ab:eval=linear-r3,pvs=1,killers=1,history=1,lmr=2,asp=1,ttrep=1,mate=1,partial=1,fut=1,futk=20,fut2=1,rows=5'
T=14                                     # threads: 16 cores, two left for the system
TEACHER=$D/teacher/r5-2p-adjacent
GEN0=$D/gen0-large/gen-0000/candidate.safetensors
SMALL9=$D/loop-small/gen-0009/candidate.safetensors   # the best SMALL network of the net5 run
EVAL='mcts:sims=200,batch=1,cpuct=1.0,noise=0,temp=0,race=0'
stop=0; trap 'stop=130' INT; trap 'stop=143' TERM
typeset -i ok=0 skip=0
t0=$SECONDS

say() { print -u2 "net5-large: $*  (elapsed $(( SECONDS - t0 )) s)"; }
state() { print "pid=$$ start=$start step=$1" > $statusf.tmp && mv $statusf.tmp $statusf; }
start=$(date '+%Y-%m-%dT%H:%M:%S')

# Energy measurement (scripts/energy/README.md). A failing marker or sampler never stops the training.
export ENERGY_NAME=net5-large MARK_SOFT=1
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

nogo() { say "NO-GO: $*"; exit 1; }
[[ -s $SMALL9 && -s $TEACHER/samples-s501-00000.safetensors ]] || nogo "teacher data or $SMALL9 missing"

# 0 baseline: 10 idle minutes before the first step, once per run. Leave the machine alone meanwhile.
if [[ ! -e $D/.baseline-large-done ]]; then
  state baseline; say "baseline: 10 idle minutes; leave the machine alone"
  mark start baseline
  sleep 600 & bl=$!; wait $bl
  mark end baseline
  (( stop )) && { kill $bl 2>/dev/null; exit $stop; }
  touch $D/.baseline-large-done
fi

# 1 Gen0 LARGE (resumable from its newest checkpoint). 60,000 steps of 256 = 16.2 passes over the 949,818 training
# samples, the pass count of the 128,000-step LARGE of capacity diagnostics 2 (D2/e3, 2.02 M samples); lr 3e-4 as there.
run gen0-large $WT/scripts/gen0.sh run $ROOT/$TEACHER $ROOT/$D/gen0-large --planes classic1 --rows 5 --preset large \
  --trainer gpu --steps 60000 --batch 256 --lr 3e-4 --seed 1 --checkpoint-steps 200

# 2 E1: score >= 0.6 against ab:6400 (pairs files make it resumable; the summary is printed again on a rerun)
run e1-large scripts/mctsmatch.sh --out $D/eval/e1-large --net $GEN0 --subject $EVAL --vs ab:1600 --vs ab:6400 --vs ab:25600 \
  --pairs 200 --seed 3001 --threads $T --rows 5 > $D/e1-large.txt
e1=$(perl -ne 'print $1 if /vs ab:6400: .*score ([0-9.]+)/' $D/e1-large.txt)
[[ -n $e1 ]] || nogo "e1-large.txt has no line for ab:6400"
perl -e 'exit !($ARGV[0] >= 0.6)' $e1 || nogo "E1: Gen0 LARGE scored $e1 against ab:6400 (gate: at least 0.6)"

# 3 loop, one generation per call, with the stop rule; lr 1e-4 as loop run 4 for LARGE (3e-4 made LARGE worse)
decision() { perl -ne 'print $1 if /"decision":"(\w+)"/' $D/loop-large/gen-$(printf %04d $1)/decision.json 2>/dev/null; }
for g in {1..10}; do
  run loop-large-$g scripts/loop.sh run $D/loop-large --init $GEN0 --rows 5 --race 0 --s1 0 --ladder-spec "$R5" \
    --generations $g --games 2500 --sims 200 --lr 1e-4 --lr-final 1e-5 --reuse 20 --window 1500000 \
    --gate-pairs 200 --promote-z 1.28 --baseline-rung 25600 --baseline-pairs 100 --teacher $TEACHER \
    --trainer gpu --evaluator gpu --threads $T --ladder 1600,6400,25600 --strength-pairs 100
  if (( g >= 6 )) && [[ $(decision $g) == rejected && $(decision $(( g - 1 ))) == rejected ]]; then
    say "loop: generations $(( g - 1 )) and $g rejected; stop rule reached"; break
  fi
done

# 4 E2: the best loop network against Gen0, 400 pairs
best=0
for f in $D/loop-large/gen-*/decision.json; do
  [[ $(perl -ne 'print $1 if /"decision":"(\w+)"/' $f) == promoted ]] && best=${${f:h:t}#gen-}
done
BEST=$D/loop-large/gen-$best/candidate.safetensors
if [[ $best == 0 || $best == 0000 ]]; then
  say "E2: no generation was promoted; the Gen0 network stays the result"
  BEST=$GEN0
else
  run e2-large scripts/mctsmatch.sh --out $D/eval/e2-large --net $BEST --subject $EVAL --vs "$EVAL,net=$GEN0" \
    --pairs 400 --seed 3002 --threads $T --rows 5 > $D/e2-large.txt
  say "E2 ($BEST against Gen0 LARGE): $(cat $D/e2-large.txt)"
fi

# 5 H2H: the best LARGE against the best SMALL (loop-small gen-0009), 400 pairs, 200 simulations
run h2h-large scripts/mctsmatch.sh --out $D/eval/h2h-large --net $BEST --subject $EVAL --vs "$EVAL,net=$SMALL9" \
  --pairs 400 --seed 3003 --threads $T --rows 5 > $D/h2h-large.txt
say "H2H ($BEST against SMALL gen-0009): $(cat $D/h2h-large.txt)"

# 6 E3: the best LARGE against the ab ladder, as E3 of the SMALL run (same seed, rungs and pairs)
run e3-large scripts/mctsmatch.sh --out $D/eval/e3-large --net $BEST --subject $EVAL --vs ab:1600 --vs ab:6400 --vs ab:25600 \
  --vs ab:102400 --pairs 200 --seed 3001 --threads $T --rows 5 > $D/e3-large.txt
say "E3 ($BEST): $(cat $D/e3-large.txt)"

say "done: $ok ok, $skip skip, 0 error; nothing more to do"
python3 scripts/energy/report.py || say "WARNING: the energy report failed (data/energy/net5-large.csv is intact; call report.py again)"
python3 scripts/energy/sampler.py --stop
rm -f $statusf
