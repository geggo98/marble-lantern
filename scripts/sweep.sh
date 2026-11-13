#!/bin/zsh
# usage: scripts/sweep.sh --loop data/loop2 --out data/sweep1 --variants BASE,A,B,C [--generation 3] [--pairs 200] [--gate-generation G]
#                         [--strength-rungs 25600,102400 --strength-pairs 100] [--trainer gpu|cpu] [--threads 8]
# (see selfplay/src/main/java/sternhalma/selfplay/Sweep.java and docs/training.md, "Loop run 2 and hyperparameter sweep 1")
# Offline hyperparameter sweep on the data of a finished loop run: every variant trains a candidate from the loop's generation 0 network
# on the replay window of generations 1 to --generation (learning-rate schedule, steps and value coefficient are the variant's),
# prints the held-out proxies, plays the loop's gate against that network and optionally the strength record. Resumable: run again
# after an abort. Needs the devenv shell for --trainer gpu (TORNADOVM_HOME), like scripts/loop.sh. Run detached for long sweeps.
set -eu
root=${0:A:h:h}
cd "$root"
./gradlew --no-build-cache --max-workers=3 -q :selfplay:classes
cp="$root/engine/build/classes/java/main:$root/tablebase/build/classes/java/main:$root/search/build/classes/java/main"
cp="$cp:$root/nn/build/classes/java/main:$root/nn-tornado/build/classes/java/main:$root/selfplay/build/classes/java/main"
tornado=()
if [[ " $* " != *" --trainer cpu "* && -n ${TORNADOVM_HOME:-} && -f $TORNADOVM_HOME/tornado-argfile ]]; then
  tornado=(@"$TORNADOVM_HOME/tornado-argfile" --sun-misc-unsafe-memory-access=allow)
fi
exec java -Xmx6g "${tornado[@]}" --add-modules jdk.incubator.vector -cp "$cp" sternhalma.selfplay.Sweep "$@"
