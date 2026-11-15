#!/bin/zsh
# bench-mac.sh: the classical measurement session on the Mac (docs/cpp-comparison-plan.md, WP7 and "Classical measurement").
#
# usage: bench-mac.sh [--rounds N] [--out FILE] [--max-load L] [--keep-logs] [--no-gate] [--games N] [--specs best|r2|best,r2]
#
# --no-gate: test the script itself on a busy machine. The hygiene refusals are skipped and the result file starts with the line
# "INVALID SESSION"; such a file is never a measurement and is not committed.
#
# One session: hygiene checks, then N rounds (default 3) in which the Java Bench, the release-hardened and the release search_bench each run
# once per spec (BEST_SPEC and R2) as a self-check run (spec against itself, --games 0), processes alternating J, C-hardened, C-release.
# The nodes per CPU-second figure of a program is side A of its own self-check run. Every run must pass its checks (ratio 0.98 to 1.02,
# identical trees, RESULT line, no JIT trend, golden check line for C++); the first runs of round 1 are the gate of the session. A failed
# check stops the session with exit 4 and writes no result file. Then the start-up row (one-position runs, J and C interleaved) is timed.
# The result file is cpp/results/mac-classical-<date>.txt.
# --games 2 (WP6) adds the two full games to every run (64 positions + 2 games, as Bench); the result file then has the games row (nodes per
# CPU-second and CPU ms per move over the games alone, and the check that Java and C++ played the games with equal node totals and move
# counts). Without --games the games rows are written as "not measured". --specs best restricts a session to BEST_SPEC (R2 has no race play).
# The start-up row is only measured in a session without games.
#
# Inputs: cpp/build/release-hardened/search_bench and cpp/build/release/search_bench (build both first), cpp/golden/ (Java golden
# files), cpp/build/java-search.cmd (the Java command line that the printRunCommand task wrote; its class path is the only absolute path
# and is never copied into the result file).
# Exit codes: 0 result written, 1 error, 3 machine not quiet (load or foreign processes; nothing measured), 4 a check failed (session
# invalid, repeat it), 75 a session is already running, 130 and 143 aborted.
emulate -L zsh
setopt pipe_fail no_unset
zmodload zsh/datetime

# One session at a time: an flock that the kernel releases when this process dies (no stale state).
if [[ -z ${BENCH_MAC_LOCKED:-} ]]; then
  export BENCH_MAC_LOCKED=1
  exec perl -MFcntl=:flock,F_SETFD -e '
    open(my $l, ">>", shift) or die "lock: $!";
    flock($l, LOCK_EX | LOCK_NB) or do { print STDERR "bench-mac.sh: a session is already running\n"; exit 75 };
    fcntl($l, F_SETFD, 0);   # keep the descriptor across exec
    exec @ARGV or die "exec: $!";' "${TMPDIR:-/tmp}/sternhalma-bench-mac.lock" $0 "$@"
fi

SELF=${0:A}
CPP=${SELF:h:h}
REPO=${CPP:h}
rounds=3
out=
# Load limit (1-minute average, before and after a session and in every search_bench run): 4.0, raised from the 2.0 of the plan on 2026-11-15
# for the idle base load of this development Mac (WindowServer, SoundSource, iTerm), measured at 3.2 to 3.7 with no measurement running
# (see cpp/README.md). It is valid only if no other compute-heavy process runs (the pgrep gate below); the validity of a session still
# rests on the self-ratio, the interleaving, the thread CPU time and the trend test.
max_load=4.0
keep=0
gate=1
games=0
specs_arg=best,r2
while (( $# )); do
  case $1 in
    --rounds) rounds=$2; shift 2 ;;
    --out) out=$2; shift 2 ;;
    --max-load) max_load=$2; shift 2 ;;
    --games) games=$2; shift 2 ;;
    --specs) specs_arg=$2; shift 2 ;;
    --keep-logs) keep=1; shift ;;
    --no-gate) gate=0; shift ;;
    *) print -u2 "usage: bench-mac.sh [--rounds N] [--out FILE] [--max-load L] [--keep-logs] [--no-gate] [--games N] [--specs best|r2|best,r2]"; exit 1 ;;
  esac
done

R2='ab:eval=linear-r1,pvs=1,killers=1,history=1,lmr=2,asp=1,ttrep=1,mate=1,partial=1,nodes=20000'
BEST='ab:eval=racelin1-r3,pvs=1,killers=1,history=1,lmr=2,asp=1,ttrep=1,mate=1,partial=1,fut=1,futk=20,fut2=1,race=1,racerows=1,nodes=20000'
typeset -A SPEC=(best $BEST r2 $R2)
SPECS=(${(s:,:)specs_arg})
WARM=()
(( games )) && WARM=(--warm-games)   # the untimed warm-up also plays the games (JIT trend rule of the plan: one more warm-up pass)
HARD=$CPP/build/release-hardened/search_bench
REL=$CPP/build/release/search_bench
JCMD_FILE=$CPP/build/java-search.cmd
[[ -z $out ]] && out=$CPP/results/mac-classical-$(strftime %Y-%m-%d $EPOCHSECONDS).txt

for f in $HARD $REL $JCMD_FILE $CPP/golden/MANIFEST.tsv; do
  [[ -r $f ]] || { print -u2 "bench-mac.sh: missing $f (build the presets release and release-hardened, run the golden exporter and printRunCommand)"; exit 1 }
done
JAVA=(${(z)$(<$JCMD_FILE)})   # java flags, class path and main class; spec and options are appended
(( gate )) && LOADARG=(--max-load $max_load) || LOADARG=(--ignore-load)

WORK=$(mktemp -d "${TMPDIR:-/tmp}/bench-mac.XXXXXX") || exit 1
CHILD=
abort() { [[ -n $CHILD ]] && kill $CHILD 2>/dev/null; rm -f $out.tmp.$$; print -u2 "bench-mac.sh: aborted (logs in $WORK)"; exit $1 }
trap 'abort 130' INT
trap 'abort 143' TERM

progress_total=$(( rounds * 3 * ${#SPECS} + (games == 0 ? rounds * 2 : 0) ))
progress_n=0
t_start=$EPOCHREALTIME
progress() {   # one line per step on stderr: n/N, elapsed
  (( progress_n++ ))
  printf '%d/%d  elapsed %ds  %s\n' $progress_n $progress_total $(( EPOCHREALTIME - t_start )) "$1" >&2
}

load1() { sysctl -n vm.loadavg | perl -ne 'print $1 if /\{\s*([\d.]+)/' }
# Foreign processes: by process name (pgrep -l), not by command line (-f would also match any program that has "java" in an argument).
procs() { pgrep -l 'gradle|java|clang|ninja|cmake' | perl -ne 'next if /^\s*(\d+)\s/ && ($1 == $ENV{SELF_PID} || $1 == $ENV{SELF_PPID}); s/^\s*\d+\s+//; print' | sort | uniq -c | perl -pe 's/^\s+//' }
export SELF_PID=$$ SELF_PPID=$PPID
therm() { pmset -g therm | perl -ne 'print "    $1\n" if /(CPU_Speed_Limit\s*=\s*\d+|No (?:thermal|performance) warning level has been recorded)/' }

# A command that runs in the background so that the trap can kill it; its output goes to $1.
run() {
  local log=$1; shift
  "$@" >$log 2>&1 &
  CHILD=$!
  wait $CHILD; local rc=$?
  CHILD=
  return $rc
}

# The checks of one log; prints the problems, exit 1 if there is one. The second argument is "java" or "cpp".
read -r -d '' CHECK_PL <<'EOF'
my ($file, $kind) = @ARGV;
open my $fh, "<", $file or die "$file: $!";
my @l = <$fh>; my @bad;
my ($ratio) = map { /\bratio=([\d.]+)/ ? $1 : () } grep { /^RESULT / } @l;
push @bad, "no RESULT line" unless defined $ratio;
push @bad, "RESULT under load" if grep { /^RESULT .*UNDER_LOAD/ } @l;
push @bad, "self-ratio $ratio outside 0.98 to 1.02" if defined $ratio && ($ratio < 0.98 || $ratio > 1.02);
push @bad, "trees not identical" unless grep { /^RESULT .*identical=true/ } @l;
if ($kind eq "cpp") {
  push @bad, "no golden check for side A and B" unless 2 == grep { /^golden: side [AB] checked against/ } @l;
}
my @a = map { /^rep \d+: A (\d+) nodes\/CPU-s/ ? $1 : () } @l;
push @bad, "JIT trend: rising repetitions @a" if @a >= 3 && $a[0] <= $a[1] && $a[1] <= $a[2] && $a[2] > 1.01 * $a[0];
print "$_\n" for @bad;
exit(@bad ? 1 : 0);
EOF

check() {   # check <log> <java|cpp>
  local problems
  problems=$(perl -e "$CHECK_PL" $1 $2) && return 0
  print -u2 "bench-mac.sh: check failed for ${1:t}: ${problems//$'\n'/; }"
  print -u2 "bench-mac.sh: session invalid, repeat it (logs in $WORK); nothing written"
  exit 4
}

# ---- hygiene before ----
load_before=$(load1)
procs_before=$(procs)
therm_before=$(therm)
if (( gate )) && perl -e 'exit($ARGV[0] > $ARGV[1] ? 0 : 1)' $load_before $max_load; then
  print -u2 "bench-mac.sh: load before is $load_before, above $max_load; not started"; exit 3
fi
if (( gate )) && [[ -n $procs_before ]]; then
  print -u2 "bench-mac.sh: foreign processes are running, not started:"; print -u2 $procs_before; exit 3
fi
(( gate )) && [[ $(pmset -g batt | head -1) != *"AC Power"* ]] && { print -u2 "bench-mac.sh: the power adapter is not connected; not started"; exit 3 }
print -u2 "load before $load_before, foreign processes: ${procs_before:-none}, $rounds rounds"

# ---- the rounds ----
for (( r = 1; r <= rounds; r++ )); do
  for s in $SPECS; do
    progress "round $r $s java"
    run $WORK/$s-java-$r.log $JAVA ${SPEC[$s]} ${SPEC[$s]} --games $games ${WARM[@]} || { print -u2 "java run failed: $WORK/$s-java-$r.log"; exit 1 }
    check $WORK/$s-java-$r.log java
    progress "round $r $s C++ release-hardened"
    run $WORK/$s-hard-$r.log $HARD ${SPEC[$s]} ${SPEC[$s]} --games $games ${WARM[@]} $LOADARG || { print -u2 "C++ run failed: $WORK/$s-hard-$r.log"; exit 1 }
    check $WORK/$s-hard-$r.log cpp
    progress "round $r $s C++ release"
    run $WORK/$s-rel-$r.log $REL ${SPEC[$s]} ${SPEC[$s]} --games $games ${WARM[@]} $LOADARG || { print -u2 "C++ run failed: $WORK/$s-rel-$r.log"; exit 1 }
    check $WORK/$s-rel-$r.log cpp
  done
done

# ---- start-up: process start to the end of a one-position run (warm-up search included), BEST_SPEC ----
(( games == 0 )) && for (( r = 1; r <= rounds; r++ )); do
  for p in java hard; do
    progress "start-up $r $p"
    if [[ $p == java ]]; then cmd=($JAVA ${SPEC[best]} --positions 1 --games 0 --reps 1); else cmd=($HARD ${SPEC[best]} --positions 1 --games 0 --reps 1 $LOADARG); fi
    t0=$EPOCHREALTIME
    run $WORK/startup-$p-$r.log $cmd || { print -u2 "start-up run failed: $WORK/startup-$p-$r.log"; exit 1 }
    printf '%.3f\n' $(( EPOCHREALTIME - t0 )) >$WORK/startup-$p-$r.sec
  done
done

# ---- hygiene after ----
load_after=$(load1)
procs_after=$(procs)
therm_after=$(therm)
if (( gate )) && perl -e 'exit($ARGV[0] > $ARGV[1] ? 0 : 1)' $load_after $max_load; then
  print -u2 "bench-mac.sh: load after is $load_after, above $max_load; session invalid, repeat it (logs in $WORK)"; exit 4
fi
if (( gate )) && [[ -n $procs_after ]]; then
  print -u2 "bench-mac.sh: foreign processes after the session, invalid (logs in $WORK):"; print -u2 $procs_after; exit 4
fi

# ---- the result file ----
mkdir -p ${out:h}
rm -f $out.tmp.*(N)   # leftovers of an aborted session
JVM_FLAGS=$(print -r -- "$JAVA" | perl -ne 'print join " ", grep { /^-X|^--add-modules$|^jdk\.incubator/ } split " "')
{
  (( gate )) || print "INVALID SESSION: --no-gate, hygiene not enforced; not a measurement"
  print "# Classical search measurement on the Mac (docs/cpp-comparison-plan.md, WP7)"
  print "date: $(strftime '%Y-%m-%d %H:%M' $EPOCHSECONDS) local time"
  print "machine: $(sysctl -n machdep.cpu.brand_string), $(sysctl -n hw.ncpu) cores, macOS $(sw_vers -productVersion) (build $(sw_vers -buildVersion))"
  print "git commit: $(git -C $REPO rev-parse --short HEAD)$([[ -n $(git -C $REPO status --porcelain --untracked-files=no) ]] && print ' (working tree has uncommitted changes)')"
  print "C++ compiler: $(/usr/bin/clang++ --version | head -1)"
  for p in release-hardened release; do
    print "C++ $p: $(grep -E '^(CMAKE_CXX_FLAGS|CMAKE_CXX_FLAGS_RELEASE|SH_HARDENING|SH_NATIVE):' $CPP/build/$p/CMakeCache.txt | perl -pe 's/:\w+=/=/; s/\n/ /') -std=c++23, no LTO, no PGO, -mcpu=native (SH_NATIVE), virtual Evaluator calls"
  done
  print "Java: $(java -version 2>&1 | head -1), JVM flags: $JVM_FLAGS (default GC G1), main class sternhalma.search.Bench"
  print "method: self-check runs (spec against itself, --games $games), 3 repetitions after one untimed warm-up pass (positions$( (( games )) && print -n " and games")), single thread, thread CPU time;"
  print "        processes alternate J, C-hardened, C-release in $rounds rounds; the figure of a program is side A of its own run; the cross-language"
  print "        ratio is the median of the adjacent pairs; a ratio outside 0.98 to 1.02 counts as real, otherwise it is not resolved"
  if (( games )); then
    print "items: the 64 reference positions (node-limited searches, nodes=20000) and $games full games from the full-game openings of ReferencePositions (race play in BEST_SPEC)"
  else
    print "positions: the 64 reference positions (node-limited searches, nodes=20000); games rows: not measured (run with --games 2)"
  fi
  print "spec BEST: $BEST"
  [[ $specs_arg == *r2* ]] && print "spec R2: $R2"
  print "equality: every C++ position search was compared with the golden fingerprint line (move, score, depth, nodes) of its spec in every run;"
  print "          both sides of every self-check run searched identical trees"
  print ""
  print "hygiene:"
  print "  load before: $load_before (1-minute average), after: $load_after; limit $max_load"
  print "  foreign processes (pgrep -l gradle|java|clang|ninja|cmake, by process name) before: ${procs_before:-none}; after: ${procs_after:-none}"
  print "  thermal before:"; print -r -- "$therm_before"; print "  thermal after:"; print -r -- "$therm_after"
  print "  power: $(pmset -g batt | head -1 | perl -pe 's/^Now drawing from //; s/\s*$//')"
  print ""
  perl -CSDA -Mutf8 - $WORK $rounds $games ${SPECS} <<'EOF'
use strict; use warnings; use List::Util qw(min max);
my ($dir, $rounds, $games, @specs) = @ARGV;
sub slurp { open my $f, "<", $_[0] or die "$_[0]: $!"; local $/; <$f> }
sub median { my @s = sort { $a <=> $b } @_; my $n = @s; $n % 2 ? $s[$n/2] : ($s[$n/2-1] + $s[$n/2]) / 2 }
my (%nps, %ms, %self);
for my $s (@specs) { for my $p (qw(java hard rel)) { for my $r (1 .. $rounds) {
  my $t = slurp("$dir/$s-$p-$r.log");
  $t =~ /^RESULT nps_a=(\d+) cpu_ms_a=([\d.]+) nps_b=\d+ cpu_ms_b=[\d.]+ ratio=([\d.]+)/m or die "no RESULT in $s-$p-$r";
  push @{$nps{$s}{$p}}, $1; push @{$ms{$s}{$p}}, $2; push @{$self{$s}{$p}}, $3;
} } }
sub cell { my @v = @_; sprintf "%.0f (%.0f to %.0f)", median(@v), min(@v), max(@v) }
sub verdict { my $q = shift; $q > 1.02 ? "C++ faster, real" : $q < 0.98 ? "C++ slower, real" : "not resolved" }
sub pair_ratio { my ($s, $p, $q) = @_; median(map { $nps{$s}{$p}[$_] / $nps{$s}{$q}[$_] } 0 .. $rounds - 1) }
my %name = (best => "BEST_SPEC (racelin1-r3), 64 positions", r2 => "R2 (linear-r1), 64 positions");
if ($games) { $name{$_} =~ s/, 64 positions/, 64 positions + $games games/ for keys %name }
print "Nodes per CPU-second (median of $rounds runs, range in parentheses):\n";
printf "  %-40s %-26s %-28s %-26s\n", "row", "Java", "C++ release-hardened (main)", "C++ release (no checks)";
for my $s (@specs) {
  printf "  %-40s %-26s %-28s %-26s\n", $name{$s}, cell(@{$nps{$s}{java}}), cell(@{$nps{$s}{hard}}), cell(@{$nps{$s}{rel}});
}
print "\nRatios (median of the adjacent pairs of the same round):\n";
for my $s (@specs) {
  my ($h, $r, $c) = (pair_ratio($s, "hard", "java"), pair_ratio($s, "rel", "java"), pair_ratio($s, "rel", "hard"));
  printf "  %-40s hardened/Java %.4f (%s); release/Java %.4f (%s); release/hardened %.4f (cost of the checks %.1f %%)\n",
    $name{$s}, $h, verdict($h), $r, verdict($r), $c, 100 * (1 - 1 / $c);
}
print $games ? "\nCPU ms per move (median, over the positions and the games of the run):\n" : "\nCPU ms per move (median, on the position searches):\n";
for my $s (@specs) {
  printf "  %-40s Java %.3f, hardened %.3f, release %.3f\n", $name{$s}, median(@{$ms{$s}{java}}), median(@{$ms{$s}{hard}}), median(@{$ms{$s}{rel}});
}
print "\nSelf-ratios of the self-check runs (all within 0.98 to 1.02, else the session was stopped):\n";
for my $s (@specs) { for my $p (qw(java hard rel)) { printf "  %-5s %-5s %s\n", $s, $p, join " ", @{$self{$s}{$p}} } }
print "\nPer-repetition nodes per CPU-second of side A (JIT trend check: no monotonic rise above 1 %):\n";
for my $s (@specs) { for my $p (qw(java hard rel)) { for my $r (1 .. $rounds) {
  my @a = slurp("$dir/$s-$p-$r.log") =~ /^rep \d+: A (\d+) nodes\/CPU-s/mg;
  printf "  %-5s %-5s run %d: %s\n", $s, $p, $r, join " ", @a;
} } }
if ($games == 0) {
  my (@j, @c);
  for my $r (1 .. $rounds) { push @j, slurp("$dir/startup-java-$r.sec") + 0; push @c, slurp("$dir/startup-hard-$r.sec") + 0 }
  print "\nStart-up (process start to the end of a one-position run with its warm-up search, BEST_SPEC, race table load included), seconds, median (range):\n";
  printf "  Java %.3f (%.3f to %.3f); C++ release-hardened %.3f (%.3f to %.3f); C++/Java %.3f\n", median(@j), min(@j), max(@j), median(@c), min(@c), max(@c), median(@c) / median(@j);
  print "\nGames rows (--games 2) and CPU ms per move over games: not measured in this session (see the games session file).\n";
} else {
  # the games alone: parsed from the "A games:" line of every run (totals over all timed repetitions)
  my %g;
  for my $s (@specs) { for my $p (qw(java hard rel)) { for my $r (1 .. $rounds) {
    slurp("$dir/$s-$p-$r.log") =~ /^A games:\s+(\d+) nodes, (\d+) moves, (\d+) CPU-ms, \d+ wall-ms: (\d+) nodes\/CPU-s, ([\d.]+) CPU-ms\/move/m or die "no games line in $s-$p-$r";
    push @{$g{$s}{$p}}, { nodes => $1, moves => $2, nps => $4, ms => $5 };
  } } }
  print "\nGames rows (the $games full games alone, totals of 3 timed repetitions per run; median of $rounds runs, range in parentheses):\n";
  printf "  %-40s %-26s %-28s %-26s\n", "row", "Java", "C++ release-hardened (main)", "C++ release (no checks)";
  for my $s (@specs) {
    printf "  %-40s %-26s %-28s %-26s\n", "$name{$s}: nodes per CPU-s, games", (map { cell(map { $_->{nps} } @{$g{$s}{$_}}) } qw(java hard rel));
    printf "  %-40s %-26s %-28s %-26s\n", "$name{$s}: CPU ms per move, games", (map { my @v = map { $_->{ms} } @{$g{$s}{$_}}; sprintf "%.3f (%.3f to %.3f)", median(@v), min(@v), max(@v) } qw(java hard rel));
  }
  print "\nGame ratios (median of the adjacent pairs, nodes per CPU-second over the games alone):\n";
  for my $s (@specs) {
    my @h = map { $g{$s}{hard}[$_]{nps} / $g{$s}{java}[$_]{nps} } 0 .. $rounds - 1;
    my @l = map { $g{$s}{rel}[$_]{nps} / $g{$s}{java}[$_]{nps} } 0 .. $rounds - 1;
    my @c = map { $g{$s}{rel}[$_]{nps} / $g{$s}{hard}[$_]{nps} } 0 .. $rounds - 1;
    printf "  %-40s hardened/Java %.4f (%s); release/Java %.4f (%s); release/hardened %.4f\n", $name{$s}, median(@h), verdict(median(@h)), median(@l), verdict(median(@l)), median(@c);
  }
  print "\nEquality of the games between the languages (nodes and moves summed over all repetitions of side A of every run; must be equal):\n";
  my $allequal = 1;
  for my $s (@specs) {
    my %seen;
    for my $p (qw(java hard rel)) { $seen{"$_->{nodes} nodes, $_->{moves} moves"}++ for @{$g{$s}{$p}} }
    my @kinds = sort keys %seen;
    $allequal = 0 if @kinds != 1;
    printf "  %-40s %s\n", $name{$s}, @kinds == 1 ? "all ${\ scalar(3 * $rounds)} runs: $kinds[0]" : "DIFFERENT: " . join(" | ", @kinds);
  }
  print $allequal ? "  equal\n" : "  NOT EQUAL\n";
  exit 5 unless $allequal;
}
EOF
} >$out.tmp.$$
# the block ends with the perl that parses the logs; its exit status (5 = the games of Java and C++ differ, or a die) decides
if (( $? )); then
  rm -f $out.tmp.$$
  print -u2 "bench-mac.sh: the result could not be written or the games of the languages differ: session invalid, repeat it (logs in $WORK); no result file"
  exit 4
fi
mv $out.tmp.$$ $out || { rm -f $out.tmp.$$; exit 1 }
print -u2 "result written: ${out#$REPO/}"
(( keep )) || rm -rf $WORK
exit 0
