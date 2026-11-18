#!/bin/zsh
# Same as app-run.zsh, with the TornadoVM JVM flags, so that the network player ("net") computes on the device.
#
# usage: app-run-tornado.zsh [--no-build] [spring-boot arguments ...]
# environment: as app-run.zsh; APP_WEIGHTS is needed for "net"; TORNADOVM_HOME is set by the devenv shell.
# The flags come from $TORNADOVM_HOME/tornado-argfile.template, as nn-tornado/build.gradle.kts derives them: each
# "--flag value" line becomes two arguments, ${TORNADOVM_HOME} is expanded, and "." leaves the module path, because
# the application runs on the class path. If TornadoVM does not start, use app-run.zsh: "net" then uses the CPU
# path (jdk.incubator.vector).
set -eu
setopt pipefail

root=${0:A:h:h:h}
template=${TORNADOVM_HOME:-}/tornado-argfile.template
if [[ -z ${TORNADOVM_HOME:-} || ! -f $template ]]; then
  print -u2 "app-run-tornado: no TornadoVM SDK (TORNADOVM_HOME='${TORNADOVM_HOME:-}'); run 'devenv tasks run setup:tornado' inside 'devenv shell'"
  exit 1
fi

# one argument per line: flag, then value
flags=("${(@f)$(perl -ne '
  BEGIN { $h = $ENV{TORNADOVM_HOME} }
  s/^\s+|\s+$//g; next if $_ eq "" or /^#/;
  s/\$\{TORNADOVM_HOME\}/$h/g;
  if (/^(?<flag>\S+)\s+(?<value>.+)$/) {
    my ($f, $v) = ($+{flag}, $+{value});
    $v = join ":", grep { $_ ne "." } split /:/, $v if $f eq "--module-path";
    print "$f\n$v\n";
  } else { print "$_\n" }
' "$template")}")

export APP_JAVA_OPTS="${(j: :)flags} --sun-misc-unsafe-memory-access=allow -Dtornado.recover.bailout=False -Dsternhalma.app.backend=tornado ${APP_JAVA_OPTS:-}"
exec "${0:A:h}/app-run.zsh" "$@"
