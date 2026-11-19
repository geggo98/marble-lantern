#!/bin/zsh
# usage: scripts/s2-report.zsh   -- specialist (data/shared/s2) against the shared net (data/shared/eval), paired per variant and rung
# PairedReport A B reports mean(A - B): A = specialist, so a positive mean and z > 1.96 means the specialist is better.
# 2p-long: the pairs files of MctsMatch carry no opponent name, so the shared files are told apart by the opponent's mean
# nodes per move (the 6400-node rung searches more nodes than the 1600-node rung); the same rule is applied to the specialist
# files, and the counts are printed so a wrong assignment shows.
set -u
root=${0:A:h:h}
sh=${S2_SHARED:-/Users/stefan/tmp/stern-halma/data/shared}
data=${S2_DATA:-$sh/s2}
shared=$sh/eval          # shared-net results of the earlier evaluation
# rung_files DIR -> "low-rung-file high-rung-file" of a MctsMatch directory
rung_files() {
  perl -MJSON::PP -e '
    my %m; for my $f (@ARGV) { open my $h, "<", $f or die; my ($n, $mv, $c) = (0, 0, 0);
      while (<$h>) { my $j = eval { decode_json($_) } or next; $n += $j->{o_nodes}; $mv += $j->{o_moves}; $c++ }
      $m{$f} = [$c, $mv ? $n / $mv : 0] }
    my @s = sort { $m{$a}[1] <=> $m{$b}[1] } keys %m; print join(" ", @s), "\n";
    printf STDERR "   %s: %d pairs, %.0f nodes/move\n", $_, @{$m{$_}} for @s' $1/pairs-*.jsonl
}
# opening_file DIR OPPONENT -> the openings file of a ThreeSeatMatch directory for that opponent
opening_file() {
  local r
  for r in $1/result-*.json; do
    if perl -MJSON::PP -e 'local $/; open my $h, "<", $ARGV[0]; exit(decode_json(<$h>)->{opponent} eq $ARGV[1] ? 0 : 1)' $r $2; then
      print ${r:h}/openings-${${${r:t}#result-}%.json}.jsonl; return 0
    fi
  done
  return 1
}
print "== 2p-long (jump long), mcts:sims=200, seed 3002, specialist - shared"
if [[ -f $data/2p-long/DONE ]]; then
  print "shared files:"; sf=(${=$(rung_files $shared/long-shared-mcts-sims-200)})
  print "specialist files:"; pf=(${=$(rung_files $data/2p-long)})
  print -- "-- rung ab:1600"; $root/scripts/threeseatmatch.sh paired $pf[1] $sf[1]
  print -- "-- rung ab:6400"; $root/scripts/threeseatmatch.sh paired $pf[2] $sf[2]
else print "(not finished)"; fi
for j in adjacent long; do
  print; print "== 3p-$j, mcts:sims=200,race=0, seed 4001, specialist - shared"
  if [[ -f $data/3p-$j/DONE ]]; then
    for opp in ab3:nodes=5000 ab3:nodes=20000; do
      print -- "-- $opp"
      $root/scripts/threeseatmatch.sh paired $(opening_file $data/3p-$j $opp) $(opening_file $shared/3p-$j $opp)
    done
  else print "(not finished)"; fi
done
