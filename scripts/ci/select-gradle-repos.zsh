#!/bin/zsh
# CI helper: puts a Maven Central mirror in FRONT of Maven Central for Gradle, but only if the mirror is healthy.
#
# Why: Maven Central is run by a non-profit and should be spared; shared CI runners also get HTTP 429 from it.
# Why a probe: Gradle falls through to the next repository ONLY on HTTP 404. On 429/5xx of the first repository
# the build fails and Central is never asked. So a sick mirror in front would break the build. We therefore probe first
# and install nothing when the mirror is not healthy; the build then resolves from Maven Central (with the retries
# from gradle.properties). A mirror that fails after a healthy probe is handled by run-with-mirror-fallback.zsh.
# Why the checksum in the probe: the probe fetches two small artifacts and compares their sha256 with
# gradle/verification-metadata.xml. A mirror that answers 200 with other bytes counts as unhealthy.
# Which artifacts: chosen from the metadata itself (first .pom or .module of kotlin-stdlib and of junit-bom, else the
# first entries in file order), so a version bump cannot leave the probe pointing at a file that is gone.
# Integrity of the real build does not depend on this probe: dependency verification stays strict.
#
# Usage: scripts/ci/select-gradle-repos.zsh [--mirror URL] [--gradle-home DIR] [--allow-insecure-http]
#   --mirror      default $CI_MAVEN_MIRROR or the Google Cloud Storage mirror of Maven Central (US); must be https://
#   --gradle-home default ${GRADLE_USER_HOME:-$HOME/.gradle}; the init script goes to <home>/init.d/.
#                 Without --gradle-home and without GRADLE_USER_HOME the script writes into ~/.gradle and therefore
#                 runs only when GITHUB_ACTIONS=true, so it cannot change a developer machine by accident.
#   --allow-insecure-http   test harness only: accept an http:// mirror (a local fault-injection server)
# Exit: 0 = done (mirror installed or deliberately not), 1 = broken setup (e.g. no checksum in the metadata),
#       2 = usage (including a refused default home and an invalid mirror URL).
set -eu

for tool in curl perl; do
  command -v $tool >/dev/null || { print -u2 "select-gradle-repos: $tool not found"; exit 1; }
done

root=${0:A:h:h:h}
mirror=${CI_MAVEN_MIRROR:-https://maven-central.storage-download.googleapis.com/maven2}
home=${GRADLE_USER_HOME:-}
insecure=false
while (( $# )); do
  case $1 in
    --mirror) mirror=${2:?--mirror needs a URL}; shift 2 ;;
    --gradle-home) home=${2:?--gradle-home needs a directory}; shift 2 ;;
    --allow-insecure-http) insecure=true; shift ;;
    *) print -u2 "usage: $0 [--mirror URL] [--gradle-home DIR] [--allow-insecure-http]"; exit 2 ;;
  esac
done

if [[ -z $home ]]; then
  if [[ ${GITHUB_ACTIONS:-} != true ]]; then
    print -u2 "select-gradle-repos: refusing to write into the default ~/.gradle outside GitHub Actions."
    print -u2 "  Pass --gradle-home DIR (or set GRADLE_USER_HOME) to install somewhere you choose."
    exit 2
  fi
  home=$HOME/.gradle
fi

mirror=${mirror%/}
# The URL is spliced into a Groovy string literal: allow no quote, backslash, dollar sign or space.
if [[ $insecure == true ]]; then url_re='^https?://[A-Za-z0-9._~:/-]+$'; else url_re='^https://[A-Za-z0-9._~:/-]+$'; fi
if ! [[ $mirror =~ $url_re ]]; then
  print -u2 "select-gradle-repos: invalid mirror URL (must match $url_re): $mirror"
  exit 2
fi

target=$home/init.d/ci-mirror.init.gradle
metadata=$root/gradle/verification-metadata.xml

tmp=
trap 'rm -f "$tmp" "${target}.tmp.$$"' EXIT

# A leftover from an earlier run must not survive an unhealthy probe.
rm -f "$target"

# Two probe artifacts as lines "<path relative to the repository root><TAB><sha256>", derived from the metadata.
# matches:  <component group="org.junit" name="junit-bom" version="5.10.2">
#             <artifact name="junit-bom-5.10.2.pom">  <sha256 value="169dd9..." origin="..."/>
probe_lines=$(perl -0777 -ne '
  my (@pref, @all);
  while (m{<component \s+ group="(?<g>[^"]+)" \s+ name="(?<n>[^"]+)" \s+ version="(?<v>[^"]+)" \s* > (?<body>.*?) </component>}gsx) {
    my ($g, $n, $v, $body) = ($+{g}, $+{n}, $+{v}, $+{body});
    while ($body =~ m{<artifact \s+ name="(?<a>[^"]+\.(?:pom|module))" \s*> \s* <sha256 \s+ value="(?<h>[0-9a-f]{64})"}gx) {
      my ($art, $hash) = ($+{a}, $+{h});   # before the s/// below, which resets %+
      (my $path = $g) =~ s{\.}{/}g;
      my $line = "$path/$n/$v/$art\t$hash";
      push @all, $line;
      push @pref, [$n, $line] if $n eq "kotlin-stdlib" || $n eq "junit-bom";
    }
  }
  my @out; my %seen;
  for my $name (qw(kotlin-stdlib junit-bom)) {                 # first pom/module entry of each preferred component
    my ($hit) = grep { $_->[0] eq $name } @pref;
    push @out, $hit->[1] if $hit;
  }
  if (@out < 2) {                                              # fill up with the first entries in file order
    %seen = map { $_ => 1 } @out;
    for my $l (@all) { last if @out >= 2; push @out, $l unless $seen{$l}++ }
  }
  print "$_\n" for @out;
' "$metadata")
if (( ${#${(f)probe_lines}} < 2 )); then
  print -u2 "select-gradle-repos: fewer than two .pom/.module checksums found in $metadata"
  exit 1
fi

sha256() { perl -MDigest::SHA=sha256_hex -0777 -ne 'print sha256_hex($_)' "$1"; }

# One request, one retry after 2 s, 10 s limit each. Sets $why on failure. Returns 0 only for 200 + matching checksum.
probe_one() {
  local rel=$1 want=$2 attempt code got
  tmp=$(mktemp)
  for attempt in 1 2; do
    code=$(curl -sS --max-time 10 -o "$tmp" -w '%{http_code}' "$mirror/$rel" 2>/dev/null) || code=000
    if [[ $code == 200 ]]; then
      got=$(sha256 "$tmp")
      if [[ $got == $want ]]; then return 0; fi
      why="$rel: checksum $got, expected $want"
    else
      why="$rel: HTTP $code"
    fi
    (( attempt == 1 )) && sleep 2
  done
  return 1
}

healthy=1; why=
for line in ${(f)probe_lines}; do
  probe_one ${line%%$'\t'*} ${line#*$'\t'} || { healthy=0; break; }
done

if (( healthy )); then
  mkdir -p "${target:h}"
  MIRROR=$mirror INSECURE=$insecure perl -pe 's{\@MIRROR_URL\@}{$ENV{MIRROR}}g; s{\@ALLOW_INSECURE\@}{$ENV{INSECURE}}g' \
    "${0:A:h}/gradle-mirror.init.gradle" > "$target.tmp.$$"
  mv "$target.tmp.$$" "$target"
  print "::notice title=Gradle mirror::mirror healthy, resolving from $mirror first, Maven Central as fallback"
  print "select-gradle-repos: installed $target"
else
  print "::warning title=Gradle mirror::mirror unhealthy ($why), resolving from Maven Central"
  print "select-gradle-repos: nothing installed"
fi
