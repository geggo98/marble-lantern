#!/bin/zsh
# CI helper: puts a Maven Central mirror in FRONT of Maven Central for Gradle, but only if the mirror is healthy.
#
# Why: Maven Central is run by a non-profit and should be spared; shared CI runners also get HTTP 429 from it.
# Why a probe: Gradle falls through to the next repository ONLY on HTTP 404. On 429/5xx of the first repository
# the build fails and Central is never asked. So a sick mirror in front would break the build. We therefore probe first
# and install nothing when the mirror is not healthy; the build then resolves from Maven Central (with the retries
# from gradle.properties).
# Why the checksum in the probe: the probe fetches small artifacts and compares their sha256 with
# gradle/verification-metadata.xml. A mirror that answers 200 with other bytes counts as unhealthy.
# Integrity of the real build does not depend on this probe: dependency verification stays strict.
#
# Usage: scripts/ci/select-gradle-repos.zsh [--mirror URL] [--gradle-home DIR]
#   --mirror      default $CI_MAVEN_MIRROR or the Google Cloud Storage mirror of Maven Central (US)
#   --gradle-home default ${GRADLE_USER_HOME:-$HOME/.gradle}; the init script goes to <home>/init.d/
# Exit: 0 = done (mirror installed or deliberately not), 1 = broken setup (e.g. no checksum in the metadata), 2 = usage.
set -eu

root=${0:A:h:h:h}
mirror=${CI_MAVEN_MIRROR:-https://maven-central.storage-download.googleapis.com/maven2}
home=${GRADLE_USER_HOME:-$HOME/.gradle}
while (( $# )); do
  case $1 in
    --mirror) mirror=${2:?--mirror needs a URL}; shift 2 ;;
    --gradle-home) home=${2:?--gradle-home needs a directory}; shift 2 ;;
    *) print -u2 "usage: $0 [--mirror URL] [--gradle-home DIR]"; exit 2 ;;
  esac
done
mirror=${mirror%/}
target=$home/init.d/ci-mirror.init.gradle
metadata=$root/gradle/verification-metadata.xml

# A leftover from an earlier run must not survive an unhealthy probe.
rm -f "$target"

# Small artifacts that exist on Maven Central; path relative to the repository root. The sha256 comes from the
# verification metadata, so there is one source of truth.
probes=(
  org/junit/junit-bom/5.10.2/junit-bom-5.10.2.pom
  org/jetbrains/kotlin/kotlin-stdlib/2.4.10/kotlin-stdlib-2.4.10.module
)

sha256() { perl -MDigest::SHA=sha256_hex -0777 -ne 'print sha256_hex($_)' "$1"; }

# One request, one retry after 2 s, 10 s limit each. Sets $why on failure. Returns 0 only for 200 + matching checksum.
probe_one() {
  local rel=$1 want=$2 tmp attempt code got
  tmp=$(mktemp)
  for attempt in 1 2; do
    code=$(curl -sS --max-time 10 -o "$tmp" -w '%{http_code}' "$mirror/$rel" 2>/dev/null) || code=000
    if [[ $code == 200 ]]; then
      got=$(sha256 "$tmp")
      if [[ $got == $want ]]; then rm -f "$tmp"; return 0; fi
      why="$rel: checksum $got, expected $want"
    else
      why="$rel: HTTP $code"
    fi
    (( attempt == 1 )) && sleep 2
  done
  rm -f "$tmp"
  return 1
}

healthy=1; why=
for rel in $probes; do
  name=${rel:t}
  # matches:  <artifact name="junit-bom-5.10.2.pom">  <sha256 value="169dd9..." origin="..."/>
  want=$(NAME=$name perl -0777 -ne 'print $+{h} if m{<artifact \s+ name="\Q$ENV{NAME}\E" \s*> \s* <sha256 \s+ value="(?<h>[0-9a-f]{64})"}x' "$metadata")
  [[ -n $want ]] || { print -u2 "select-gradle-repos: no sha256 for $name in $metadata"; exit 1; }
  probe_one $rel $want || { healthy=0; break; }
done

if (( healthy )); then
  mkdir -p "${target:h}"
  MIRROR=$mirror perl -pe 's{\@MIRROR_URL\@}{$ENV{MIRROR}}g' "${0:A:h}/gradle-mirror.init.gradle" > "$target.tmp.$$"
  mv "$target.tmp.$$" "$target"
  print "::notice title=Gradle mirror::mirror healthy, resolving from $mirror first, Maven Central as fallback"
  print "select-gradle-repos: installed $target"
else
  print "::warning title=Gradle mirror::mirror unhealthy ($why), resolving from Maven Central"
  print "select-gradle-repos: nothing installed"
fi
