#!/bin/zsh
# usage: scripts/threeseatmatch.sh --out DIR (--candidate SPEC | --control ab3|random) [--net FILE] --vs ab3:nodes=5000 [--vs random] [--jump adjacent|long] [--openings 100] [--seed 4001]
# usage: scripts/threeseatmatch.sh paired A/openings-X.jsonl B/openings-Y.jsonl [--json OUT.json]
# (see selfplay/src/main/java/sternhalma/selfplay/ThreeSeatMatch.java and PairedReport.java, docs/training.md "Evaluating variants")
# Builds the selfplay module and plays three-player games of a candidate against two identical opponents, the candidate
# rotated through seats 0, 1, 2 on every opening. Finished openings are kept in DIR/openings-*.jsonl, so a second run
# resumes; 75 means it runs already; 130/143 mean aborted by SIGINT/SIGTERM. 'paired' compares two such files (or two
# MctsMatch pairs files) with a paired z-score. Run it inside the devenv shell.
set -eu
root=${0:A:h:h}
cd "$root"
./gradlew --no-build-cache -q :selfplay:classes
cp="$root/engine/build/classes/java/main:$root/tablebase/build/classes/java/main:$root/search/build/classes/java/main"
cp="$cp:$root/nn/build/classes/java/main:$root/selfplay/build/classes/java/main"
main=sternhalma.selfplay.ThreeSeatMatch
if [[ ${1:-} == paired ]]; then
  main=sternhalma.selfplay.PairedReport
  shift
fi
exec java -Xmx3g --add-modules jdk.incubator.vector -cp "$cp" $main "$@"
