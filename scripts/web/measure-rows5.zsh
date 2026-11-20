#!/bin/zsh
# Engine measurements on the 5-row board (see measure-rows5.mjs for what it measures and its options).
# Run it through devenv: devenv shell -- scripts/web/measure-rows5.zsh ...
#
# usage: measure-rows5.zsh --work DIR [--tag T] [options of measure-rows5.mjs]   play the games (resumable)
#        measure-rows5.zsh --status --work DIR [--tag T] [same options]          is this shard running, and since when?
#        measure-rows5.zsh --aggregate --work DIR [--tag T]                      print the summary tables
# Needs web/public/engines (scripts/web/build-wasm.zsh, build-teavm.zsh) and web/public/tables (devenv tasks run web:tables).
#
# A shard is one set of options. Its lock is held by an open file descriptor (the kernel frees it on any death), so shards with
# different options can run in parallel in one DIR, one process per CPU core. The status file next to the lock (pid and start time)
# is for display only; resuming reads the finished game files.
# Exit: 0 = done, 1 = error, 2 = wrong call, 75 = this shard runs already, 130/143 = stopped.
set -eu
setopt pipefail
root=${0:A:h:h:h}
cd $root/web

work="" tag=main aggregate=0 status_only=0
args=()
for a in "$@"; do
  case $a in
    --status) status_only=1 ;;
    --aggregate) aggregate=1; args+=($a) ;;
    *) args+=($a) ;;
  esac
done
for (( i = 1; i <= $#args; i++ )); do
  case $args[i] in
    --work) work=$args[i+1] ;;
    --tag) tag=$args[i+1] ;;
  esac
done
[[ -n $work ]] || { print -u2 "usage: measure-rows5.zsh --work DIR ... (see the header of this file)"; exit 2; }
work=${work:A}
mkdir -p $work/$tag
run=(node ../scripts/web/measure-rows5.mjs "${args[@]}")
(( aggregate )) && exec $run

# the shard key: the options without --work, as a short hash
key=$(print -r -- "${args[@]}" | perl -pe 's/--work \S+//' | shasum | cut -c1-12)
lock=$work/$tag/.lock-$key
statusf=$work/$tag/.status-$key
# flock(1) does not exist on macOS: perl takes the lock and keeps the descriptor open across exec ($^F), then starts node.
exec perl -MFcntl=:flock -e '
  $^F = 1000; my ($lock, $statusf, $only) = splice(@ARGV, 0, 3);
  open(my $fh, ">>", $lock) or die "lock: $!";
  if (!flock($fh, LOCK_EX | LOCK_NB)) {
    my $st = "pid=? start=?\n";
    if (open(my $s, "<", $statusf)) { $st = <$s> // $st }
    my ($pid) = $st =~ /pid=(\d+)/;
    my $et = $pid ? `ps -o etime= -p $pid` : ""; $et =~ s/\s+//g;
    print STDERR "measure-rows5: runs already: ", $st =~ s/\n//r, " running for ", ($et || "? (process gone)"), "\n";
    exit 75;
  }
  if ($only) { print "measure-rows5: not running\n"; exit 0 }
  open(my $s, ">", $statusf) or die "status: $!"; print $s "pid=$$ start=" . localtime() . " args=@ARGV[2..$#ARGV]\n"; close $s;
  exec @ARGV or die "exec: $!";' $lock $statusf $status_only $run
