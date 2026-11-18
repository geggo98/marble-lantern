#!/bin/zsh
# Starts the online mode (Hilla app, server engines) on port 8080 with the table directory of this repository.
#
# usage: app-run.zsh [--no-build] [spring-boot arguments ...]
# environment:
#   APP_PORT        port, default 8080
#   APP_ADDRESS     bind address, default 127.0.0.1 (the endpoint is anonymous: set 0.0.0.0 only on purpose)
#   APP_WEIGHTS     safetensors file; with it the server also offers the network player ("net")
#   APP_JAVA_OPTS   extra JVM options
# The first run builds the boot jar with the production frontend (Gradle, a few seconds to two minutes). The frontend
# build downloads node into ~/.vaadin once (plugin option not found, see docs/web-ui.md); this script never needs it
# at run time. Ctrl-C stops the server (exit 130); exit code otherwise as java.
set -eu
setopt pipefail

root=${0:A:h:h:h}
cd "$root"

build=1
if [[ ${1:-} == --no-build ]]; then build=0; shift; fi

if (( build )); then
  print -u2 "app-run: building app/build/libs/app.jar"
  ./gradlew --no-build-cache -Pvaadin.productionMode=true :app:bootJar >&2 || { print -u2 "app-run: the build failed"; exit 1; }
fi
jar=$root/app/build/libs/app.jar
[[ -f $jar ]] || { print -u2 "app-run: $jar does not exist (run without --no-build)"; exit 1; }

opts=(--add-modules jdk.incubator.vector
      "-Dsternhalma.tablebase.dir=$root/tablebase/tables")
[[ -n ${APP_WEIGHTS:-} ]] && opts+=("-Dsternhalma.app.weights=$APP_WEIGHTS")
[[ -n ${APP_JAVA_OPTS:-} ]] && opts+=(${=APP_JAVA_OPTS})

print -u2 "app-run: http://localhost:${APP_PORT:-8080}/  (Ctrl-C stops)"
exec java $opts -jar "$jar" --server.port=${APP_PORT:-8080} --server.address=${APP_ADDRESS:-127.0.0.1} "$@"
