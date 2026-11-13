#!/bin/zsh
# usage: scripts/loop.sh run RUN --init NETWORK [--generations 3] [--games 600] [--sims 100] [options]
#        scripts/loop.sh run --status RUN | strength RUN [--generation N] | monitor-report RUN
# (see selfplay/src/main/java/sternhalma/selfplay/Loop.java for every option and docs/training.md, "As implemented (block 2h)")
# Builds the selfplay module and runs the training loop in the repository root: per generation self-play with the best
# network (PUCT search, race play and S1 adjudication), training of a candidate (fresh AdamW optimiser, replay window),
# a gate (candidate against the best network, and against a fixed classical rung) and a strength record (Elo against
# ladder rungs). RUN is a directory (for example data/loop1); generation 0 is the network of --init, copied once.
# A second 'run' on the same RUN resumes (the state comes from the files); one after success does nothing and exits
# 0; 75 means it runs already; 130/143 mean aborted by SIGINT/SIGTERM. Every option that changes a result must be
# repeated on a resume (run.json is compared); cadences, threads, --generations and the strength options may change.
# --promote-z Z (default 1.28) promotes a candidate only if score - Z * se > 0.5 (one-sided; 0 is the old rule score > 0.5); it
# is part of run.json: repeat it on a resume (a run from before the option resumes with --promote-z 0).
# --evaluator gpu plays the self-play games with the network on the GPU (--games-in-flight N games at the same time in --eval-cohorts K
# groups, --threads compute threads); it needs the TornadoVM SDK like --trainer gpu, falls back to the CPU with a message, and is not
# part of run.json: a run may be resumed with the other evaluator (docs/training.md, "As implemented (block G1)").
# --trainer gpu trains the candidates on the GPU (TornadoVM, Metal): it needs the TornadoVM SDK (TORNADOVM_HOME, set by the
# devenv shell) and gives different networks than the CPU trainer; it falls back to the CPU, with a message, where no device
# is usable. The trainer is part of run.json: repeat it on a resume.
# Run it inside the devenv shell, detached for long runs (the harness kills background jobs after about 10 minutes):
#   devenv shell -- scripts/loop.sh run data/loop1 --init data/gen0run3/gen-0000/candidate.safetensors --generations 3
set -eu
root=${0:A:h:h}
cd "$root"
./gradlew --no-build-cache -q :selfplay:classes
cp="$root/engine/build/classes/java/main:$root/tablebase/build/classes/java/main:$root/search/build/classes/java/main"
cp="$cp:$root/nn/build/classes/java/main:$root/nn-tornado/build/classes/java/main:$root/selfplay/build/classes/java/main"
# the TornadoVM runtime flags of the SDK, only for --trainer gpu or --evaluator gpu (they put the TornadoVM modules on the module path)
tornado=()
if [[ ( " $* " == *" --trainer gpu "* || " $* " == *" --evaluator gpu "* ) && -n ${TORNADOVM_HOME:-} && -f $TORNADOVM_HOME/tornado-argfile ]]; then
  tornado=(@"$TORNADOVM_HOME/tornado-argfile" --sun-misc-unsafe-memory-access=allow)
fi
# the Vector API module makes the MCTS forward pass about 15 times faster
exec java -Xmx6g "${tornado[@]}" --add-modules jdk.incubator.vector -cp "$cp" sternhalma.selfplay.Loop "$@"
