#!/bin/zsh
# Node benchmark of the browser engines (plan section 8): plays the benchmark game on every engine in
# web/public/engines/engines.json and compares the fingerprint (move checksum, total nodes) with web/bench/expected.json.
# Run through `devenv tasks run web:bench --input args="<options>"` (or BENCH_ARGS=...), or call this script directly. Options go to web/bench/run-node.ts:
#   --plies N --nodes N --runs N --engines a,b --public DIR --expected FILE --json FILE --jvm
# Defaults: 40 plies, 20000 nodes, 5 runs (the full run, about 30 seconds).
#
# Exit: 0 = every fingerprint is ok, 1 = mismatch, unchecked fingerprint or error, 2 = wrong call, 130/143 = stopped.
# Runs take seconds to a minute, so there is no lock, no resume and no status file: a run is cheap to repeat, and an
# interrupted run (SIGINT/SIGTERM) stops between games and discards its partial result. Progress goes to stderr, line by line.
set -eu
setopt pipefail

root=${0:A:h:h:h}
cd $root/web
if [[ ! -d node_modules ]]; then
  print -u2 "web:bench: web/node_modules is missing; run 'devenv tasks run web:install' first"
  exit 1
fi
# devenv 2.4 has no "--" passthrough: `devenv tasks run web:bench -- --runs 1` looks for a task called "--runs". Options come in as
# a task input (`--input args="--plies 6 --nodes 2000 --runs 1"`, delivered as JSON in $DEVENV_TASK_INPUT) or as $BENCH_ARGS.
# Direct calls (scripts/web/bench.zsh --runs 1) pass the arguments on as they are.
if (( $# == 0 )); then
  extra=${BENCH_ARGS:-}
  if [[ -n ${DEVENV_TASK_INPUT:-} ]]; then
    extra=$(print -r -- $DEVENV_TASK_INPUT | perl -MJSON::PP -0777 -ne 'my $a = decode_json($_)->{args}; print $a // ""')
  fi
  set -- ${=extra}
fi
# devenv tasks swallow a task's stdout as task output, so the table goes to stderr, where the task log and CI show it.
exec node --import ./bench/register.mjs bench/run-node.ts "$@" 1>&2
