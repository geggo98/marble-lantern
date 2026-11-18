#!/bin/zsh
# Calibration of the skill estimate (see calibrate-skill.mts for what it does). Run it through devenv: devenv shell -- scripts/web/calibrate-skill.zsh ...
#
# usage: calibrate-skill.zsh --work DIR [--rungs a,b,c] [--games N] [--seed S] [--opening N]
#        calibrate-skill.zsh --aggregate --work DIR --out src/skill/calibration.json   (paths are relative to web/)
# Needs `devenv tasks run web:engines web:tables` first (web/public/engines, web/public/tables).
#
# Resumable (finished games stay in DIR), abortable (SIGINT/SIGTERM: the running game is dropped), progress with ETA on stderr.
# The lock is held by an open file descriptor (the kernel frees it on any death); it is per set of rungs, so that runs with
# different --rungs can work in parallel in one DIR (one process per CPU core is the intended use).
# Exit: 0 = done, 1 = error, 2 = wrong call, 75 = the same rungs run already, 130/143 = stopped.
set -eu
setopt pipefail
root=${0:A:h:h:h}
cd $root/web
[[ -d node_modules ]] || { print -u2 "calibrate-skill: web/node_modules is missing; run 'devenv tasks run web:install' first"; exit 1; }

work="" rungs=all aggregate=0
args=("$@")
for (( i = 1; i <= $#args; i++ )); do
  case $args[i] in
    --work) work=$args[i+1] ;;
    --rungs) rungs=$args[i+1] ;;
    --aggregate) aggregate=1 ;;
  esac
done
[[ -n $work ]] || { print -u2 "usage: calibrate-skill.zsh --work DIR ... (see the header of this file)"; exit 2; }
mkdir -p $work

run=(node --import ./bench/register.mjs ../scripts/web/calibrate-skill.mts "$@")
if (( aggregate )); then
  exec $run
fi
# flock(1) does not exist on macOS: perl takes the lock and keeps the descriptor open across exec ($^F), then starts node.
lock=$work/.lock-${rungs//,/_}
exec perl -MFcntl=:flock -e '
  $^F = 1000; my $lock = shift;
  open(my $fh, ">>", $lock) or die "lock: $!";
  flock($fh, LOCK_EX | LOCK_NB) or do { print STDERR "calibrate-skill: these rungs already run (lock $lock)\n"; exit 75 };
  exec @ARGV or die "exec: $!";' $lock $run
