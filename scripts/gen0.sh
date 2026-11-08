#!/bin/zsh
# usage: scripts/gen0.sh run DATA RUN [options] | run --status RUN | strength RUN [options] | proxies RUN --data DIR [options] | monitor-report RUN
# (see selfplay/src/main/java/sternhalma/selfplay/Gen0.java)
# Builds the selfplay module and runs the generation-0 training tool in the repository root. DATA is a directory of
# teacher shards from 'scripts/selfplay.sh run --teacher-depth D'. A second 'run' on the same RUN resumes; one after
# success does nothing and exits 0; 75 means it runs already; 130/143 mean aborted by SIGINT/SIGTERM.
# Run it inside the devenv shell: devenv shell -- scripts/gen0.sh run data/teacher data/gen0 --steps 4000
set -eu
root=${0:A:h:h}
cd "$root"
./gradlew --no-build-cache -q :selfplay:classes
cp="$root/engine/build/classes/java/main:$root/tablebase/build/classes/java/main:$root/search/build/classes/java/main"
cp="$cp:$root/nn/build/classes/java/main:$root/selfplay/build/classes/java/main"
exec java -Xmx4g -cp "$cp" sternhalma.selfplay.Gen0 "$@"
