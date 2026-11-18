#!/bin/zsh
# Unpack tablebase/tables/rows1.bin.zst into the cache tablebase/build/tables/rows1.bin (about 0.3 s plus the check).
#
# usage: unpack-rows1.zsh [--archive FILE] [--dest FILE]
#
# SUCCESS (unpacked), SKIP (the destination exists with the right SHA-256), ERROR, CLEANUP (stale temp files).
# The result is written under a temporary name, checked against ROWS1_SHA256 (the constant in RaceTable.java) and
# then renamed, so only a verified file carries the final name.
# The lock file ${dest}.lock is the one Rows1Archive.unpack takes, so Java and this script never delete each
# other's live temporary file.
# Exit: 0 done or nothing to do, 1 error, 75 already running, 130/143 aborted by SIGINT/SIGTERM.
zmodload zsh/system
setopt no_unset pipe_fail

readonly ROWS1_SHA256=85850d6f47f526daf45ac5cd3a0dad8841244cb50d0d8d15a5b6a54f09d1bfd1
root=${0:A:h:h:h}
archive=$root/tablebase/tables/rows1.bin.zst
dest=$root/tablebase/build/tables/rows1.bin
while (( $# )); do
  case $1 in
    --archive) archive=$2; shift 2 ;;
    --dest)    dest=$2;    shift 2 ;;
    *) print -u2 "usage: $0 [--archive FILE] [--dest FILE]"; exit 2 ;;
  esac
done

sha_of() { perl -MDigest::SHA -e 'my $d = Digest::SHA->new(256); $d->addfile(\*STDIN); print $d->hexdigest' }

mkdir -p -- ${dest:h}
exec {lockfd}>>${dest}.lock
if ! zsystem flock -t 0 -f $lockfd ${dest}.lock; then
  print -u2 "already running: $(<${dest:h}/.unpack-rows1.status 2>/dev/null)"
  exit 75
fi
print "pid=$$" >${dest:h}/.unpack-rows1.status

tmp=$dest.tmp.$$
ok=0 skip=0 err=0 cleanup=0
for stale in $dest.tmp.*(N); do rm -f -- $stale; (( cleanup++ )); done
stop=0
trap 'stop=130' INT
trap 'stop=143' TERM
finish() {
  rm -f -- $tmp
  print -u2 "$ok ok, $skip skip, $err error, $cleanup cleanup$2"
  exit $1
}

if [[ -f $dest ]] && [[ $(sha_of <$dest) == $ROWS1_SHA256 ]]; then
  skip=1; finish 0 " - nothing more to do ($dest verified)"
fi
[[ -f $archive ]] || { err=1; finish 1 " - $archive missing (git lfs pull --include=tablebase/tables/rows1.bin.zst)"; }
if [[ $(wc -c <$archive) -lt 1024 ]] && head -c 40 $archive | grep -q 'git-lfs'; then
  err=1; finish 1 " - $archive is a Git LFS pointer, not the archive (git lfs pull --include=tablebase/tables/rows1.bin.zst)"
fi
command -v zstd >/dev/null || { err=1; finish 1 " - zstd not found on PATH (run inside devenv shell)"; }

t0=$SECONDS
zstd -d -q -f -o $tmp -- $archive || { err=1; finish 1 " - zstd failed on $archive"; }
(( stop )) && finish $stop " - aborted, nothing published"
got=$(sha_of <$tmp)
[[ $got == $ROWS1_SHA256 ]] || { err=1; finish 1 " - $archive unpacks to sha256 $got, expected $ROWS1_SHA256"; }
mv -f -- $tmp $dest
ok=1
finish 0 " - wrote $dest ($(wc -c <$dest | tr -d ' ') bytes) in $(( SECONDS - t0 )) s"
