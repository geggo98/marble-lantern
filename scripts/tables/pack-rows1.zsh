#!/bin/zsh
# Compress tablebase/tables/rows1.bin to tablebase/tables/rows1.bin.zst (maintainer task, about 3 minutes).
#
# usage: pack-rows1.zsh [--source FILE] [--dest FILE]
#   --source  the raw table (default: tablebase/tables/rows1.bin)
#   --dest    the archive      (default: <source>.zst)
#
# Command: zstd --ultra -22 --long=27 -T0 (21,216,784 bytes with zstd 1.5.7; the window of 128 MiB needs no
# special option in a reader). The archive is written under a temporary name, decompressed again, compared with
# ROWS1_SHA256 (the constant in RaceTable.java) and only then renamed.
#
# Per item (here one file): SUCCESS (packed), SKIP (a verified archive exists), ERROR, CLEANUP (stale temp files).
# Exit: 0 done or nothing to do, 1 error, 75 already running, 130/143 aborted by SIGINT/SIGTERM.
zmodload zsh/system
zmodload zsh/datetime
setopt no_unset pipe_fail

readonly ROWS1_SHA256=85850d6f47f526daf45ac5cd3a0dad8841244cb50d0d8d15a5b6a54f09d1bfd1
root=${0:A:h:h:h}
source=$root/tablebase/tables/rows1.bin
dest=
while (( $# )); do
  case $1 in
    --source) source=$2; shift 2 ;;
    --dest)   dest=$2;   shift 2 ;;
    *) print -u2 "usage: $0 [--source FILE] [--dest FILE]"; exit 2 ;;
  esac
done
[[ -n $dest ]] || dest=$source.zst
command -v zstd >/dev/null || { print -u2 "zstd not found on PATH (run inside devenv shell)"; exit 1; }

sha_of() { perl -MDigest::SHA -e 'my $d = Digest::SHA->new(256); $d->addfile(\*STDIN); print $d->hexdigest' }

# lock: the kernel releases it when this shell dies, also on kill -9
lockdir=$root/tablebase/build
mkdir -p $lockdir
exec {lockfd}>>$lockdir/.pack-rows1.lock
if ! zsystem flock -t 0 -f $lockfd $lockdir/.pack-rows1.lock; then
  print -u2 "already running: $(<$lockdir/.pack-rows1.status 2>/dev/null)"
  exit 75
fi
print "pid=$$ start=$(date '+%Y-%m-%dT%H:%M:%S')" >$lockdir/.pack-rows1.status

tmp=$dest.tmp.$$
cleanup=0
for stale in $dest.tmp.*(N); do rm -f -- $stale; (( cleanup++ )); done

child=0
stop=0
on_signal() { stop=$1; (( child )) && kill $child 2>/dev/null; }
trap 'on_signal 130' INT
trap 'on_signal 143' TERM
finish() {  # $1 = exit code
  rm -f -- $tmp
  print -u2 "${ok:-0} ok, ${skip:-0} skip, ${err:-0} error, ${cleanup} cleanup$2"
  exit $1
}
ok=0 skip=0 err=0

# SKIP: a verified archive already exists
if [[ -s $dest ]]; then
  got=$(zstd -d -c -- $dest | sha_of)
  if [[ $got == $ROWS1_SHA256 ]]; then
    skip=1
    finish 0 " - nothing more to do ($dest verified, $(wc -c <$dest | tr -d ' ') bytes)"
  fi
  print -u2 "existing $dest does not verify (sha256 $got); packing again"
fi

[[ -f $source ]] || { err=1; finish 1 " - source $source missing"; }
actual=$(sha_of <$source)
if [[ $actual != $ROWS1_SHA256 ]]; then
  err=1; finish 1 " - $source has sha256 $actual, expected $ROWS1_SHA256"
fi

t0=$EPOCHSECONDS
print -u2 "packing $source ($(wc -c <$source | tr -d ' ') bytes) -> $dest"
zstd --ultra -22 --long=27 -T0 -q -f --no-progress -o $tmp -- $source &
child=$!
while kill -0 $child 2>/dev/null; do
  sleep 10 & wait $!
  (( stop )) && break
  print -u2 "compressing: $(( EPOCHSECONDS - t0 )) s elapsed, $(wc -c <$tmp 2>/dev/null | tr -d ' ') bytes written"
done
wait $child; rc=$?
child=0
(( stop )) && finish $stop " - aborted, nothing published"
(( rc == 0 )) || { err=1; finish 1 " - zstd exited with $rc"; }

print -u2 "verifying $tmp"
got=$(zstd -d -c -- $tmp | sha_of)
if [[ $got != $ROWS1_SHA256 ]]; then
  err=1; finish 1 " - the archive decompresses to sha256 $got, expected $ROWS1_SHA256"
fi
mv -f -- $tmp $dest
ok=1
finish 0 " - wrote $dest ($(wc -c <$dest | tr -d ' ') bytes) in $(( EPOCHSECONDS - t0 )) s"
