#!/bin/zsh
# CI wrapper for every command that runs Gradle: if the build fails because the Gradle mirror failed in the middle of
# the build, run the command ONE more time against Maven Central.
#
# Why: select-gradle-repos.zsh probes the mirror before the build, but a mirror can still fail later (HTTP 429 or 5xx,
# a timeout, a pom without its jar). Gradle falls through to the next repository only on HTTP 404, so such a failure
# fails the build although Maven Central would have answered.
#
# What it does: runs the command with its output copied to a log (exit code preserved). It reruns the command, without
# the mirror init script, only if ALL of these hold:
#   - the command failed,
#   - the mirror init script is installed,
#   - the log names the mirror (its host or "ciMirror") AND shows a transport or lookup failure
#     ("Received status code 4xx/5xx", "Could not find", "Could not GET", "Could not HEAD", "timed out"),
#   - the log does NOT contain "Dependency verification failed". A checksum mismatch is a security finding, never a
#     reason to switch repositories. A genuine test or compile failure also does not match and is not rerun.
# The rerun deletes the init script first, prints a ::warning, and its exit code is the result. The retries from
# gradle.properties stay in force for the rerun.
#
# Usage: scripts/ci/run-with-mirror-fallback.zsh [--gradle-home DIR] -- <command> [args...]
#   --gradle-home default ${GRADLE_USER_HOME:-$HOME/.gradle}; the init script is <home>/init.d/ci-mirror.init.gradle
# Exit: the exit code of the (last) run of the command; 2 = usage.
set -u

home=${GRADLE_USER_HOME:-$HOME/.gradle}
while (( $# )); do
  case $1 in
    --gradle-home) home=${2:?--gradle-home needs a directory}; shift 2 ;;
    --) shift; break ;;
    *) print -u2 "usage: $0 [--gradle-home DIR] -- <command> [args...]"; exit 2 ;;
  esac
done
(( $# )) || { print -u2 "usage: $0 [--gradle-home DIR] -- <command> [args...]"; exit 2; }

init=$home/init.d/ci-mirror.init.gradle
log=$(mktemp)
trap 'rm -f "$log"' EXIT

set -o pipefail
"$@" 2>&1 | tee "$log"
rc=${pipestatus[1]}
(( rc == 0 )) && exit 0
[[ -f $init ]] || exit $rc

# matches:  def mirrorUrl = 'https://maven-central.storage-download.googleapis.com/maven2'   ->  host
host=$(perl -ne 'print $+{h} if m{^ def \s+ mirrorUrl \s* = \s* \x27 https?:// (?<h>[^/:\x27]+)}x' "$init")

# matches:  Could not GET 'https://<mirror host>/maven2/...'. Received status code 429 from server: Too Many Requests
mirror_failed=$(HOST=$host perl -0777 -ne '
  print "no" and exit if /Dependency \s verification \s failed/x;
  my $names_mirror = /ciMirror/ || (length $ENV{HOST} && /\Q$ENV{HOST}\E/);
  my $failure = /Received \s status \s code \s [45]\d\d | Could \s not \s find | Could \s not \s (?:GET|HEAD) | timed \s out/x;
  print(($names_mirror && $failure) ? "yes" : "no");
' "$log")

if [[ $mirror_failed != yes ]]; then
  exit $rc
fi

print "::warning title=Gradle mirror::the build failed with an error from the mirror ($host), running it once more against Maven Central"
rm -f "$init"
"$@"
exit $?
