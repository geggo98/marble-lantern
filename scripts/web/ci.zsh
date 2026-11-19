#!/bin/zsh
# Everything the Pages job and the pull-request web job need, in order (plan sections 4 and 9.4).
# Run through `devenv tasks run web:ci`. Nothing is published here; the workflow uploads web/dist afterwards.
#
# Steps, in this order; the first failure stops the run, the summary names the step:
#   1  tables      pack rows2.bin into web/public/tables (needs the real table, not an LFS pointer)
#   2  java-tests  Gradle: :engine:test :tablebase:test, :search:test (BrowserSpecTest only), :teavm-web:test
#   3  wasm-build  C++ engine as WebAssembly
#   4  wasm-test   node tests of the C++ engine
#   5  teavm-build Java engine with TeaVM
#   5b teavm-net-build net-MCTS facade of the net engines with TeaVM (web/public/engines/net/engine-net.*)
#   6  engines     web/public/engines/engines.json
#   7  web-install npm ci
#   8  web-test    typecheck and Vitest
#   9  web-build   vite build and the bundle checks (index.html base path, no 'connect/' in the static bundle)
#  10  bench-smoke benchmark with 6 plies and 2,000 nodes: fingerprints must match
#  11  e2e         Playwright: Chromium and Firefox are gates, WebKit is informative
#
# usage: ci.zsh [--from STEP] [--status]
#   --from STEP   start at this step (name from the list), after fixing a failure; the steps before it are skipped
#   --status      say whether a run is active (pid and elapsed time) and exit
# env:   BASE_PATH  base path of the site. If unset and REPO_NAME is set, it becomes /$REPO_NAME/ (the project path of
#                   GitHub Pages); if both are unset, scripts/web/build.zsh uses /marble-lantern/. Note: the e2e step
#                   (scripts/web/e2e.zsh) sets its own BASE_PATH=/marble-lantern/, so REPO_NAME does not reach it.
# Exit: 0 = all steps passed, 1 = a step failed, 2 = wrong call, 75 = a run is already active, 130/143 = interrupted.
#
# Rerunning is safe: every step script is idempotent (it skips work whose output verifies). A lock on
# $root/.devenv/state/web-ci.lock (held through an open file, so the kernel frees it on any death, also kill -9)
# keeps two runs from working in one checkout. The status file is for display only.
set -u
setopt pipefail

root=${0:A:h:h:h}
cd $root

typeset -a steps
steps=(tables java-tests wasm-build wasm-test teavm-build teavm-net-build engines web-install web-test web-build bench-smoke e2e)

from=""
case "${1:-}" in
  "") ;;
  --status) mode=status ;;
  --from) from=${2:-}; [[ $from == (${(j:|:)steps}) ]] || { print -u2 "usage: ci.zsh [--from STEP] [--status]; steps: ${steps}"; exit 2 } ;;
  *) print -u2 "usage: ci.zsh [--from STEP] [--status]"; exit 2 ;;
esac

state=$root/.devenv/state
mkdir -p $state
lockf=$state/web-ci.lock
statusf=$state/web-ci.status

# Lock through perl: flock(1) does not exist on macOS. The lock descriptor survives the exec ($^F), so the lock lives as long as this script.
if [[ -z ${WEB_CI_LOCKED:-} ]]; then
  export WEB_CI_LOCKED=1
  exec perl -MFcntl=:flock -e '
    $^F = 10; my ($lock, $status, @cmd) = @ARGV;
    open my $lk, ">>", $lock or die "lock: $!";
    if (!flock $lk, LOCK_EX | LOCK_NB) {
      open my $s, "<", $status; my $st = <$s> // "unknown start"; chomp $st;
      my ($pid) = $st =~ /pid=(\d+)/; my $et = $pid ? `ps -o etime= -p $pid 2>/dev/null` : ""; $et =~ s/^\s+|\s+$//g;
      print STDERR "web:ci: a run is already active: $st, running for ", ($et || "? (process gone)"), "\n"; exit 75;
    }
    exec @cmd or die "exec: $!";' -- $lockf $statusf $0:A "$@"
fi
# From here on this process holds the lock.
if [[ ${mode:-} == status ]]; then print "web:ci: not running"; exit 0; fi
print "pid=$$ start=$(date '+%Y-%m-%dT%H:%M:%S') cwd=$root" > $statusf

if [[ -z ${BASE_PATH:-} && -n ${REPO_NAME:-} ]]; then export BASE_PATH=/$REPO_NAME/; fi
print -u2 "web:ci: BASE_PATH=${BASE_PATH:-(default of build.zsh)}"

typeset -A cmd
cmd=(
  tables       "scripts/web/tables.zsh"
  java-tests   "./gradlew --no-build-cache :engine:test :tablebase:test :search:test --tests sternhalma.search.BrowserSpecTest :teavm-web:test"
  wasm-build   "scripts/web/build-wasm.zsh"
  wasm-test    "scripts/web/test-wasm.zsh"
  teavm-build  "scripts/web/build-teavm.zsh"
  teavm-net-build "scripts/web/build-teavm-net.zsh"
  engines      "env ENGINES_STRICT=1 scripts/web/engines-manifest.zsh"
  web-install  "scripts/web/install.zsh"
  web-test     "scripts/web/test.zsh"
  web-build    "scripts/web/build.zsh"
  bench-smoke  "scripts/web/bench.zsh --plies 6 --nodes 2000 --runs 1"
  e2e          "scripts/web/e2e.zsh"
)

stop=0
trap 'stop=130' INT
trap 'stop=143' TERM

typeset -i ok=0 skipped=0 failed=0 n=0 total=$#steps
t0=$SECONDS
failed_step=""
[[ -n $from ]] && skipping=1 || skipping=0
for s in $steps; do
  n+=1
  if (( skipping )); then
    [[ $s == $from ]] && skipping=0 || { skipped+=1; print -u2 "web:ci: [$n/$total] $s SKIP (before --from $from)"; continue }
  fi
  (( stop )) && break
  print -u2 "web:ci: [$n/$total] $s: ${cmd[$s]}  (elapsed $(( SECONDS - t0 )) s)"
  t1=$SECONDS
  # the command text is fixed above, so word splitting of the string is intended
  ${=cmd[$s]}
  rc=$?
  if (( stop )); then break; fi
  if (( rc == 0 )); then
    ok+=1; print -u2 "web:ci: [$n/$total] $s ok in $(( SECONDS - t1 )) s"
  else
    failed+=1; failed_step=$s
    print -u2 "web:ci: [$n/$total] $s FAILED with exit $rc after $(( SECONDS - t1 )) s"
    break
  fi
done

left=$(( total - ok - skipped - failed ))
print -u2 "web:ci: $ok ok, $skipped skip, $failed failed, $left not run, $(( SECONDS - t0 )) s${failed_step:+; failed step: $failed_step (rerun with --from $failed_step)}"
(( stop )) && exit $stop
(( failed )) && exit 1
exit 0
