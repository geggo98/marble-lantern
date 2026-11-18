#!/bin/zsh
# Puts the real tablebase/tables/rows2.bin into the working tree without spending Git LFS bandwidth on a cache hit.
# Origin: plan section 9.3. A checkout with `lfs: true` downloads every LFS object of the commit on every run; this
# script fetches the one object the CI jobs need (13,123,286 bytes) and keeps it in a cache directory that
# actions/cache restores under the key `lfs-rows2-<oid>`.
#
# usage: fetch-rows2.zsh [--oid | --github-output]
#   --oid   print the LFS object id (SHA-256) of rows2.bin from the pointer in HEAD and exit; the workflow uses it as cache key
#   --github-output  like --oid, and also append "oid=<id>" to the file named by GITHUB_OUTPUT (a step output of a workflow)
#   env LFS_ROWS2_CACHE   cache directory (default: ~/.cache/lfs-rows2)
# Exit: 0 = rows2.bin in the working tree matches the pointer (also when it already did), 1 = failure, 2 = wrong call.
#
# Steps: read the oid from the pointer in HEAD; use $cache/<oid> if it verifies; otherwise take the working-tree file if it
# verifies; otherwise `git lfs fetch` just this path (anonymous read, which works because the repository is public and
# the checkout runs with persist-credentials: false); then verify, publish into the cache, copy over the pointer, verify.
# Every file appears under a temporary name and is renamed, so a name that exists is complete.
set -eu
setopt pipefail

tbl=tablebase/tables/rows2.bin
want_oid_only=0
gh_output=0
case "${1:-}" in
  "") ;;
  --oid) want_oid_only=1 ;;
  --github-output) want_oid_only=1; gh_output=1 ;;
  *) print -u2 "usage: fetch-rows2.zsh [--oid | --github-output]"; exit 2 ;;
esac
root=${0:A:h:h:h}
cd $root

sha() { perl -MDigest::SHA -e 'open my $f, "<:raw", $ARGV[0] or exit 3; print Digest::SHA->new(256)->addfile($f)->hexdigest' -- $1 }
fail() { print -u2 "fetch-rows2: $*"; exit 1 }

# pointer in HEAD:  version https://git-lfs.github.com/spec/v1 / oid sha256:<64 hex> / size <n>
pointer=$(git show HEAD:$tbl | head -c 512)
oid=$(print -r -- $pointer | perl -ne 'print $+{oid} if m{^ oid \s+ sha256: (?<oid>[0-9a-f]{64}) \s* $}x')
size=$(print -r -- $pointer | perl -ne 'print $+{n} if m{^ size \s+ (?<n>[0-9]+) \s* $}x')
[[ -n $oid && -n $size ]] || fail "HEAD:$tbl is not a Git LFS pointer (no 'oid sha256:' and 'size' line); first line: ${pointer%%$'\n'*}"
if (( want_oid_only )); then
  if (( gh_output )); then
    [[ -n ${GITHUB_OUTPUT:-} ]] || fail "--github-output needs the variable GITHUB_OUTPUT"
    print -r -- "oid=$oid" >> $GITHUB_OUTPUT
  fi
  print -r -- $oid; exit 0
fi

cache=${LFS_ROWS2_CACHE:-$HOME/.cache/lfs-rows2}
mkdir -p $cache
rm -f $cache/*.tmp.*(N)                      # CLEANUP: leftovers of an interrupted run
ok_file() { [[ -f $1 ]] && [[ $(wc -c < $1 | tr -d ' ') == $size ]] && [[ $(sha $1) == $oid ]] }

source=""
if ok_file $cache/$oid; then
  print -u2 "fetch-rows2: cache hit $cache/$oid (0 bytes of LFS bandwidth)"
  source=$cache/$oid
elif ok_file $tbl; then
  print -u2 "fetch-rows2: $tbl is already the real table, seeding the cache"
  tmp=$cache/$oid.tmp.$$; cp $tbl $tmp; mv -f $tmp $cache/$oid
  source=$cache/$oid
else
  print -u2 "fetch-rows2: cache miss, git lfs fetch --include=$tbl ($size bytes)"
  git lfs fetch --include=$tbl >&2 || fail "git lfs fetch failed (no network, or the object is not on the LFS server)"
  obj=$(git rev-parse --path-format=absolute --git-path lfs/objects)/${oid[1,2]}/${oid[3,4]}/$oid
  ok_file $obj || fail "the fetched LFS object $obj is missing or does not match the pointer (size $size, sha256 $oid)"
  tmp=$cache/$oid.tmp.$$; cp $obj $tmp; mv -f $tmp $cache/$oid
  source=$cache/$oid
fi

if ! ok_file $tbl; then
  tmp=$tbl.tmp.$$
  cp $source $tmp
  ok_file $tmp || { rm -f $tmp; fail "the copy of $source does not verify"; }
  mv -f $tmp $tbl
fi
ok_file $tbl || fail "$tbl does not verify after the copy"
print -u2 "fetch-rows2: $tbl ok ($size bytes, sha256 $oid)"
