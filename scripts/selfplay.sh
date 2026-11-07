#!/bin/zsh
# usage: scripts/selfplay.sh run|report|strong-check ...   (see selfplay/src/main/java/sternhalma/selfplay/SelfPlay.java)
# Builds the selfplay module and runs the self-play data tool in the repository root.
# Run it inside the devenv shell: devenv shell -- scripts/selfplay.sh run --out data/sp --spec ab:nodes=1000 --games 10 --seed 1006
set -eu
root=${0:A:h:h}
cd "$root"
./gradlew --no-build-cache -q :selfplay:classes
cp="$root/engine/build/classes/java/main:$root/tablebase/build/classes/java/main:$root/search/build/classes/java/main"
cp="$cp:$root/nn/build/classes/java/main:$root/selfplay/build/classes/java/main"
exec java -Xmx4g -cp "$cp" sternhalma.selfplay.SelfPlay "$@"
