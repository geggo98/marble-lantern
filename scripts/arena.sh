#!/bin/zsh
# usage: scripts/arena.sh --a <spec> --b <spec> [--pairs N] [--seed S] [--opening-plies K] [--threads T]
# Builds the search module and runs the paired-game arena (see search/src/main/java/sternhalma/search/Arena.java).
# Run it inside the devenv shell: devenv shell -- scripts/arena.sh --a ab:nodes=20000 --b ab:nodes=5000
set -eu
root=${0:A:h:h}
cd "$root"
./gradlew --no-build-cache -q :search:classes
exec java -cp "$root/engine/build/classes/java/main:$root/search/build/classes/java/main" sternhalma.search.Arena "$@"
