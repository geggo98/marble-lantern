#!/bin/zsh
# data/net5-cpupower/experiment.zsh [--status]: power and step time of the Gen0 trainer on CPU and GPU, SMALL and LARGE,
# on the 5-row teacher data, for the energy estimate of a CPU-only training. The training outputs are thrown away.
# Phases (energy run name net5-cpu-power): baseline (10 idle min), cpu-small, cpu-large (11 min each), gpu-small,
# gpu-large (5.5 min each), baseline-after (10 idle min); 60 idle seconds between phases keep the phase edges off load changes.
# Resumable: a phase with a .done file is skipped; an interrupted phase starts again from a clean run directory.
# Exit: 0 all phases done, 1 error, 75 already running, 130/143 stopped.
set -u
setopt pipefail
cd <repository> || exit 1
X=data/net5-cpupower
lockf=$X/.exp.lock
statusf=$X/.exp.status
if [[ -z ${EXP_LOCKED:-} ]]; then
  export EXP_LOCKED=1
  exec perl -MFcntl=:flock -e '
    $^F = 10; my ($lock, $status, $mode, @cmd) = @ARGV;
    open my $lk, ">>", $lock or die "lock: $!";
    if (!flock $lk, LOCK_EX | LOCK_NB) {
      open my $s, "<", $status; my $st = <$s> // "?"; chomp $st;
      my ($pid) = $st =~ /pid=(\d+)/; my $et = $pid ? `ps -o etime= -p $pid 2>/dev/null` : ""; $et =~ s/^\s+|\s+$//g;
      print STDERR "net5-cpu-power: running: $st, for ", ($et || "? (process gone)"), "\n"; exit 75;
    }
    if ($mode eq "--status") { print "net5-cpu-power: not running\n"; exit 0 }
    exec @cmd or die "exec: $!";' -- $lockf $statusf "${1:-run}" zsh $0:A
fi

WT=<repository>/.worktrees/gen0-large      # Gen0 with --preset large (uncommitted change, see gen0-large.patch)
TEACHER=<repository>/data/net5/teacher/r5-2p-adjacent
ABS=<repository>/$X
export ENERGY_NAME=net5-cpu-power MARK_SOFT=1
mark() { python3 scripts/energy/mark.py "$@" }
stop=0; child=0
trap 'stop=130; (( child )) && kill -TERM $child 2>/dev/null' INT
trap 'stop=143; (( child )) && kill -TERM $child 2>/dev/null' TERM
start=$(date '+%Y-%m-%dT%H:%M:%S'); t0=$SECONDS
typeset -i ok=0 skip=0
say() { print -u2 "exp: $*  (elapsed $(( SECONDS - t0 )) s)"; }
state() { print "pid=$$ start=$start phase=$1" > $statusf.tmp && mv $statusf.tmp $statusf; }

# phase name, seconds, preset, trainer, extra Gen0 options (the real configurations: SMALL = net5 gen0, LARGE = driver-large gen0)
phases=(
  "baseline 600 - - -"
  "cpu-small 660 small cpu --steps 40000 --lr 1e-3 --threads 14"
  "cpu-large 660 large cpu --steps 60000 --lr 3e-4 --threads 14"
  "gpu-small 330 small gpu --steps 40000 --lr 1e-3"
  "gpu-large 330 large gpu --steps 60000 --lr 3e-4"
  "baseline-after 600 - - -"
)
total=0; for p in $phases; do total=$(( total + ${${=p}[2]} + 60 )); done

python3 scripts/energy/sampler.py --detach >/dev/null 2>&1
python3 scripts/energy/sampler.py --status >/dev/null 2>&1; (( $? == 75 )) || { say "the energy sampler is not running"; exit 1; }
# build the classes once, so that the gradle check inside a phase is short
(cd $WT && ./gradlew --no-build-cache -q :selfplay:classes) || { say "build failed"; exit 1; }
say "display: $(pmset -g assertions | perl -ne 'print "PreventUserIdleDisplaySleep=$1" if /^\s*PreventUserIdleDisplaySleep\s+(\d)/') (1 = the display stays on)"

left=$total
for p in $phases; do
  w=(${=p}); name=$w[1]; secs=$w[2]; preset=$w[3]; trainer=$w[4]; extra=(${w[5,-1]})
  if [[ -e $X/$name.done ]]; then skip+=1; left=$(( left - secs - 60 )); say "$name SKIP (done)"; continue; fi
  say "$name: $secs s; about $(( left / 60 )) min left in the experiment"
  state $name
  mark start $name
  if [[ $preset == - ]]; then
    sleep $secs & child=$!; wait $child; child=0
  else
    rm -rf $X/$name; mkdir -p $X/$name
    $WT/scripts/gen0.sh run $TEACHER $ABS/$name/run --planes classic1 --rows 5 --preset $preset --trainer $trainer \
      --batch 256 --seed 1 --checkpoint-steps 200 $extra[@] 2> $X/$name/gen0.log > $X/$name/gen0.out & child=$!
    sleep $secs & sl=$!; wait $sl
    kill -TERM $child 2>/dev/null; wait $child; rc=$?; child=0
    say "$name: Gen0 exit $rc (143 = stopped by this script, as planned): $(perl -ne 'print if /steps\/s/' $X/$name/gen0.log | tail -1)"
  fi
  mark end $name
  (( stop )) && { say "stopped in $name"; exit $stop; }
  touch $X/$name.done; ok+=1; left=$(( left - secs - 60 ))
  sleep 60 & child=$!; wait $child; child=0
  (( stop )) && exit $stop
done
say "done: $ok ok, $skip skip, 0 error; nothing more to do"
python3 scripts/energy/report.py || say "WARNING: report.py failed"
python3 scripts/energy/sampler.py --stop
rm -f $statusf
