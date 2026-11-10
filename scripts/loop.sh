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
# Run it inside the devenv shell, detached for long runs (the harness kills background jobs after about 10 minutes):
#   devenv shell -- scripts/loop.sh run data/loop1 --init data/gen0run3/gen-0000/candidate.safetensors --generations 3
set -eu
root=${0:A:h:h}
cd "$root"
./gradlew --no-build-cache -q :selfplay:classes
cp="$root/engine/build/classes/java/main:$root/tablebase/build/classes/java/main:$root/search/build/classes/java/main"
cp="$cp:$root/nn/build/classes/java/main:$root/selfplay/build/classes/java/main"
# the Vector API module makes the MCTS forward pass about 15 times faster
exec java -Xmx6g --add-modules jdk.incubator.vector -cp "$cp" sternhalma.selfplay.Loop "$@"
