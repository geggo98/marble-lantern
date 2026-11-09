#!/bin/zsh
# usage: scripts/mctsmatch.sh --out DIR --subject mcts:sims=100,... [--net FILE] --vs ab:100 [--vs raw] [--pairs 100] [--seed 3001] ...
# usage: scripts/mctsmatch.sh bench --net FILE --subject mcts:... [--threads 1,8] [--seconds 20]
# (see selfplay/src/main/java/sternhalma/selfplay/MctsMatch.java and MctsBench.java)
# Builds the selfplay module and plays paired games of a network player (raw policy or MCTS) against ladder rungs in the
# repository root. Finished pairs are kept in DIR/pairs-*.jsonl, so a second run resumes; 75 means it runs already;
# 130/143 mean aborted by SIGINT/SIGTERM. Run it inside the devenv shell.
set -eu
root=${0:A:h:h}
cd "$root"
./gradlew --no-build-cache -q :selfplay:classes
cp="$root/engine/build/classes/java/main:$root/tablebase/build/classes/java/main:$root/search/build/classes/java/main"
cp="$cp:$root/nn/build/classes/java/main:$root/selfplay/build/classes/java/main"
main=sternhalma.selfplay.MctsMatch
if [[ ${1:-} == bench ]]; then
  main=sternhalma.selfplay.MctsBench
  shift
fi
exec java -Xmx3g --add-modules jdk.incubator.vector -cp "$cp" $main "$@"
