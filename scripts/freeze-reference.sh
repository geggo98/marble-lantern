#!/bin/zsh
# usage: scripts/freeze-reference.sh [--force] [<dir>]
#
# Freezes the cost reference of round 2 (docs/search-and-evaluation.md, "Round 2 yardstick"): builds
# engine.jar and search.jar from the current checkout and copies them into <dir> (default: .reference/
# in the repository root, which git ignores), together with REFERENCE.txt (commit id, build date, JDK,
# SHA-256 of both jars). Bench loads the jars from -Dsternhalma.reference.dir (default: that directory)
# in a separate class loader to measure nps_old.
#
# Run it inside the devenv shell, on a clean checkout of the commit to freeze:
#   devenv shell -- scripts/freeze-reference.sh
# A frozen reference is never overwritten silently: an existing <dir>/REFERENCE.txt needs --force.
# The commit id comes from `git rev-parse HEAD` and the tree must be clean (`git status --porcelain`,
# tracked files only); set REFERENCE_COMMIT=<id> to skip both git calls and record that id instead.
set -eu
root=${0:A:h:h}
force=0
if [[ ${1:-} == --force ]]; then
  force=1
  shift
fi
dir=${1:-$root/.reference}
cd "$root"

if [[ -n ${REFERENCE_COMMIT:-} ]]; then
  commit=$REFERENCE_COMMIT
  tree="not checked (REFERENCE_COMMIT given)"
else
  commit=$(git rev-parse HEAD)
  if [[ -n $(git status --porcelain --untracked-files=no) ]]; then
    print -u2 "freeze-reference: the working tree has changes; commit or stash them first"
    exit 1
  fi
  tree=clean
fi

if [[ -e $dir/REFERENCE.txt && $force == 0 ]]; then
  print -u2 "freeze-reference: $dir already holds a frozen reference ($dir/REFERENCE.txt); use --force to replace it"
  exit 1
fi

./gradlew --no-build-cache -q :engine:jar :search:jar
mkdir -p "$dir"
for jar in engine/build/libs/engine.jar search/build/libs/search.jar; do
  cp "$jar" "$dir/${jar:t}.tmp.$$"
  mv "$dir/${jar:t}.tmp.$$" "$dir/${jar:t}"
done
{
  print "commit=$commit"
  print "tree=$tree"
  print "built=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  print "jdk=$(java -version 2>&1 | head -n 1)"
  (cd "$dir" && shasum -a 256 engine.jar search.jar) | perl -lane 'print "sha256 $F[1]=$F[0]"'
} > "$dir/REFERENCE.txt.tmp.$$"
mv "$dir/REFERENCE.txt.tmp.$$" "$dir/REFERENCE.txt"
cat "$dir/REFERENCE.txt"
