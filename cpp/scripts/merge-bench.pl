#!/usr/bin/env perl
# usage: merge-bench.pl <work-dir>      (the directory that bench-nn-mac.py writes: steps.tsv and one <step>.tsv per run)
# Merges the raw outputs of the Java CrossBench and the C++ nn_bench (shared TSV schema of README, "The Java benchmark and its
# schedule") into the summary of the session: medians over the pairs, cross-language ratios with the verdict of the decision rule
# of the plan ("Network on the Mac": more than 5 %, and outside the round range of both sides), batch-1 latency, phase split, first
# call, kernel table, cost of the checks, cold start, anchors. Lines "CHECK FAILED ..." make the session invalid (the anchors of
# the end against the start within 2 %, no CPU case with a thread CPU time below 0.8 of the wall time).
use strict;
use warnings;
use utf8;
use List::Util qw(min max sum);

my $dir = shift or die "usage: merge-bench.pl <work-dir>\n";
sub slurp { open my $f, "<:encoding(UTF-8)", $_[0] or die "$_[0]: $!"; local $/; scalar <$f> }
sub median { my @s = sort { $a <=> $b } @_; return undef unless @s; my $n = @s; $n % 2 ? $s[($n - 1) / 2] : ($s[$n / 2 - 1] + $s[$n / 2]) / 2 }
sub fmt { my ($v, $d) = @_; defined $v ? sprintf("%.${d}f", $v) : "NA" }
sub num { defined $_[0] && $_[0] ne "NA" ? $_[0] + 0 : undef }

# ---- read the steps and their data ----
my @steps;
{
  open my $f, "<", "$dir/steps.tsv" or die "steps.tsv: $!";
  my $head = <$f>; chomp $head; my @h = split /\t/, $head;
  while (<$f>) { chomp; my %s; @s{@h} = split /\t/, $_, -1; push @steps, \%s }
}
# rows: $row{name}{key} = {eps, rmin, rmax, ms50, ms90, copy_in, kernel, copy_out, priors, first, load, bucket, lanes}
my (%row, %cpuwall, %gpu, @skipped, %profiled, %setup, %cold, %gc, %kern_java, %kern_cpp, %verified_bad);
for my $s (@steps) {
  my $name = $s->{name};
  my $text = slurp("$dir/$name.tsv");
  for my $line (split /\n/, $text) {
    next if $line eq "";
    if ($line =~ /^lang\t/ || $line =~ /^#.*columns/) { next }
    if ($line =~ /^#profiled\t(\w+)\t(\w+)\t(\d+)\t([\d.]+)\t(\S+)\t(\S+)\t(\S+)/) {
      $profiled{java}{"$1|$2|$3"}{$name} = [$4, num($5), num($6), num($7)]; next }
    if ($line =~ /^#instrumented\t(\w+)\t(\w+)\t(\d+)\t([\d.]+)\t(\S+)\t(\S+)\t(\S+)/) {
      $profiled{cpp}{"$1|$2|$3"}{$name} = [$4, num($5), num($6), num($7)]; next }
    if ($line =~ /^#kernels\tcpp\tmetal\t(\d+)\t(\d+)\t(\w+)\t([\d.]+)\t([\d.]+)\t([\d.]+)\t([\d.]+)/) {
      push @{ $kern_cpp{"$1|$2|$3"} }, [$4, $5, $6, $7]; next }
    if ($line =~ /^\|\s*(\d+)\s*\|\s*(\d+)\s*\|\s*(\w+)\s*\|\s*([\d.]+)\s*\|\s*([\d.]+)\s*\|\s*([\d.]+)\s*\|\s*([\d.]+)\s*\|$/) {
      push @{ $kern_java{"$1|$2|" . lc $3} }, [$4, $5, $6, $7]; next }
    if ($line =~ /^# cpu: (\S+) (\S+) (\S+) batch (\d+): .* ([\d.]+)$/) { $cpuwall{$name}{"$1|$2|$3|$4"} = $5; next }
    if ($line =~ /^# gpu: (\S+) (\S+) (\S+) batch (\d+): GPU time of the command buffer ([\d.]+) ms/) { $gpu{$name}{"$1|$2|$3|$4"} = $5; next }
    if ($line =~ /^# skipped: (.*)$/) { push @skipped, "$name: $1"; next }
    if ($line =~ /^# gc: (\S+) (\S+) (\S+) batch (\d+): (\d+) collections, (\d+) ms/) { $gc{"$1|$2|$3|$4"}{$name} = [$5, $6]; next }
    if ($line =~ /^# (\S+) (\S+) (\S+): metal (?:library|set-up).*$/ || $line =~ /^# (\S+) (\S+) (\S+): (cuda .*)$/) { $setup{$name}{$line} = 1; next }
    if ($line =~ /^# cold: construct ([\d.]+) ms, first call ([\d.]+) ms/) { $cold{$name} = [$1, $2]; next }
    next if $line =~ /^#/;
    my @c = split /\t/, $line, -1;
    next unless @c == 22;
    my ($lang, $impl, $dev, $preset, $weights, $batch, $bucket, $lanes, $eps, $ms50, $ms90, $rmin, $rmax, $cin, $h2d, $kern, $d2h, $cout, $pri, $first, $load, $ver) = @c;
    $verified_bad{$name}++ if $ver ne "ok";
    $row{$name}{"$impl|$preset|$weights|$batch"} = { lang => $lang, impl => $impl, eps => num($eps), rmin => num($rmin), rmax => num($rmax), ms50 => num($ms50), ms90 => num($ms90),
      copy_in => num($cin), h2d => num($h2d), kernel => num($kern), d2h => num($d2h), copy_out => num($cout), priors => num($pri), first => num($first), load => num($load), bucket => $bucket, lanes => $lanes, dev => $dev };
  }
}
my @fail;
push @fail, "CHECK FAILED: a case of $_ is not verified" for grep { $verified_bad{$_} } sort keys %verified_bad;

sub steps_of { my ($kind, $lang) = @_; grep { $_->{kind} eq $kind && (!defined $lang || $_->{lang} eq $lang) } @steps }
# values of one key over the steps of a kind and language (one per pair)
sub series { my ($kind, $lang, $key, $field) = @_; my @v; for my $s (steps_of($kind, $lang)) { my $r = $row{ $s->{name} }{$key}; push @v, $r->{$field} if $r && defined $r->{$field} } @v }
sub keys_of { my ($kind, $lang) = @_; my %k; for my $s (steps_of($kind, $lang)) { $k{$_}++ for keys %{ $row{ $s->{name} } } } sort keys %k }
sub eps_med { median(series(@_[0, 1, 2], "eps")) }

my $out = "";
sub say_ { $out .= join("", @_) . "\n" }

say_("# Summary of the network measurement (merge-bench.pl)");
say_("# Rates are evaluations per second, median over the pairs of the per-process median round; [min, max] is the smallest round_min and the largest round_max over the pairs.");
say_("# Verdict (decision rule of the plan, Network on the Mac): a ratio counts as real only if it is more than 5 % away from 1 AND the two round ranges do not overlap; otherwise 'not resolved'.");
say_("");

# ---- cross-language table ----
my @pairs_def = (["scalar", "reference", "scalar (Java) vs reference (C++)"], ["vector", "simd", "Vector API (Java) vs simd FMA (C++), main CPU row"], ["vector", "simd-nofma", "Vector API (Java) vs simd no-FMA (C++), extra row"],
  ["tornado", "metal-copy", "TornadoVM (Java) vs Metal copy (C++), main GPU row"], ["tornado", "metal-shared", "TornadoVM (Java) vs Metal shared (C++), extra row"]);
for my $kind (["main", "u5 weights (SMALL, LARGE)"], ["gen11", "real weights gen11 (LARGE)"]) {
  my ($k, $title) = @$kind;
  say_("## Throughput, $title");
  my %jk = map { $_ => 1 } keys_of($k, "java");
  my %ck = map { $_ => 1 } keys_of($k, "cpp");
  my %shapes; for my $key (keys %jk, keys %ck) { my ($i, $p, $w, $b) = split /\|/, $key; $shapes{"$p|$w|$b"} = 1 }
  my @order = sort { my @a = split /\|/, $a; my @b = split /\|/, $b; $a[0] cmp $b[0] or $a[1] cmp $b[1] or $a[2] <=> $b[2] } keys %shapes;
  for my $pair (@pairs_def) {
    my ($ji, $ci, $label) = @$pair;
    my $any = grep { $jk{"$ji|$_"} && $ck{"$ci|$_"} } @order;
    next unless $any;
    say_("### $label");
    say_(sprintf "%-6s %-5s %6s  %-34s %-34s %7s  %s", "preset", "wts", "batch", "Java $ji evals/s [min, max]", "C++ $ci evals/s [min, max]", "ratio", "verdict");
    for my $sh (@order) {
      my ($p, $w, $b) = split /\|/, $sh;
      my ($jkey, $ckey) = ("$ji|$sh", "$ci|$sh");
      next unless $jk{$jkey} || $ck{$ckey};
      my @je = series($k, "java", $jkey, "eps"); my @ce = series($k, "cpp", $ckey, "eps");
      my $jm = median(@je); my $cm = median(@ce);
      my @jr = (min(series($k, "java", $jkey, "rmin")), max(series($k, "java", $jkey, "rmax")));
      my @cr = (min(series($k, "cpp", $ckey, "rmin")), max(series($k, "cpp", $ckey, "rmax")));
      my $cell = sub { my ($m, @r) = @_; defined $m ? sprintf("%.1f [%.1f, %.1f]", $m, $r[0], $r[1]) : "not run (skipped)" };
      my ($ratio, $verdict) = (undef, "");
      if (defined $jm && defined $cm && @je == @ce) {
        my @rs = map { $ce[$_] / $je[$_] } 0 .. $#je;
        $ratio = median(@rs);
        my $disjoint = ($cm < $jr[0] || $cm > $jr[1]) && ($jm < $cr[0] || $jm > $cr[1]);
        $verdict = abs($ratio - 1) > 0.05 && $disjoint ? ($ratio > 1 ? "C++ faster, real" : "C++ slower, real") : "not resolved";
      }
      say_(sprintf "%-6s %-5s %6d  %-34s %-34s %7s  %s", $p, $w, $b, $cell->($jm, @jr), $cell->($cm, @cr), defined $ratio ? sprintf("%.3f", $ratio) : "NA", $verdict);
    }
    say_("");
  }
}

# ---- latency at batch 1 ----
say_("## Latency at batch 1, ms per call (p50 / p90, median over the pairs)");
say_(sprintf "%-6s %-5s  %-16s %-16s %-16s %-16s %-16s %-16s %-16s %-16s", "preset", "wts", "J scalar", "J vector", "J tornado", "C reference", "C simd", "C simd-nofma", "C metal-copy", "C metal-shared");
for my $pw (["small", "u5"], ["large", "u5"]) {
  my @cells;
  for my $ic (["java", "scalar"], ["java", "vector"], ["java", "tornado"], ["cpp", "reference"], ["cpp", "simd"], ["cpp", "simd-nofma"], ["cpp", "metal-copy"], ["cpp", "metal-shared"]) {
    my $key = "$ic->[1]|$pw->[0]|$pw->[1]|1";
    my $a = median(series("main", $ic->[0], $key, "ms50")); my $b = median(series("main", $ic->[0], $key, "ms90"));
    push @cells, defined $a ? sprintf("%.3f / %.3f", $a, $b) : "NA";
  }
  say_(sprintf "%-6s %-5s  " . ("%-16s " x 8), @$pw, @cells);
}
say_("");

# ---- where the GPU path overtakes one CPU thread ----
say_("## Batch from which the GPU path is faster than the best CPU path of the same language (u5 weights)");
for my $lang (["java", "tornado", ["scalar", "vector"]], ["cpp", "metal-copy", ["reference", "simd", "simd-nofma"]], ["cpp", "metal-shared", ["reference", "simd", "simd-nofma"]]) {
  my ($l, $gpuimpl, $cpuimpls) = @$lang;
  for my $preset (qw(small large)) {
    my $found = "never in 1..1024";
    for my $b (1, 16, 64, 256, 512, 1024) {
      my $g = eps_med("main", $l, "$gpuimpl|$preset|u5|$b", "eps");
      my @c = grep { defined } map { eps_med("main", $l, "$_|$preset|u5|$b", "eps") } @$cpuimpls;
      next unless defined $g && @c;
      if ($g > max(@c)) { $found = "batch $b"; last }
    }
    say_(sprintf "  %-5s %-13s %-6s %s", $l, $gpuimpl, $preset, $found);
  }
}
say_("");

# ---- phases of the GPU rows ----
say_("## Phases of a GPU call, ms per call (median over the pairs; NA = not measured separately)");
say_("# Java: kernel = TornadoVM execute (host to device, kernels, device to host in one). C++ Metal: kernel = commit-and-wait (blits, kernels, waiting in one command buffer); gpu = GPU time of that command buffer.");
say_(sprintf "%-13s %-6s %6s %9s %9s %9s %9s %9s %9s %10s", "impl", "preset", "batch", "copy_in", "execute", "copy_out", "priors", "gpu", "wall p50", "first call");
for my $ic (["java", "tornado"], ["cpp", "metal-copy"], ["cpp", "metal-shared"]) {
  my ($lang, $impl) = @$ic;
  for my $preset (qw(small large)) {
    for my $b (1, 16, 64, 256, 512, 1024) {
      my $key = "$impl|$preset|u5|$b";
      my @g;
      for my $s (steps_of("main", $lang)) { my $v = $gpu{ $s->{name} }{"$impl|$preset|u5|$b"}; push @g, $v if defined $v }
      my @e = series("main", $lang, $key, "eps"); next unless @e;
      say_(sprintf "%-13s %-6s %6d %9s %9s %9s %9s %9s %9s %10s", $impl, $preset, $b, fmt(median(series("main", $lang, $key, "copy_in")), 3), fmt(median(series("main", $lang, $key, "kernel")), 3),
        fmt(median(series("main", $lang, $key, "copy_out")), 3), fmt(median(series("main", $lang, $key, "priors")), 3), fmt(median(@g), 3), fmt(median(series("main", $lang, $key, "ms50")), 3),
        fmt(median(series("main", $lang, $key, "first")), 1));
    }
  }
}
say_("");

# ---- profiled / instrumented ----
say_("## Kernel, write and read time of a GPU call, ms (separate pass, the instrumentation slows the call; median over the pairs)");
say_("# Java: TornadoVM profiler (device kernel, write, read times; NA where the backend reports 0). C++: Metal instrumented mode (one command buffer per kernel and blit, GPU clock).");
say_(sprintf "%-5s %-6s %-5s %6s %10s %10s %10s %10s", "lang", "preset", "wts", "batch", "wall", "kernel", "dev write", "dev read");
for my $lang (qw(java cpp)) {
  for my $key (sort { my @a = split /\|/, $a; my @b = split /\|/, $b; $a[0] cmp $b[0] or $a[1] cmp $b[1] or $a[2] <=> $b[2] } keys %{ $profiled{$lang} || {} }) {
    my %by = %{ $profiled{$lang}{$key} };
    my @col = map { my $i = $_; median(grep { defined } map { $_->[$i] } values %by) } 0 .. 3;
    my ($p, $w, $b) = split /\|/, $key;
    say_(sprintf "%-5s %-6s %-5s %6d %10s %10s %10s %10s", $lang, $p, $w, $b, fmt($col[0], 3), fmt($col[1], 3), fmt($col[2], 3), fmt($col[3], 3));
  }
}
say_("");

# ---- set-up facts of the C++ Metal path ----
say_("## Set-up of the C++ Metal path (first pair, as printed by nn_bench)");
my ($first_cpp) = steps_of("main", "cpp");
if ($first_cpp) { say_("  $_") for sort keys %{ $setup{ $first_cpp->{name} } || {} } }
say_("");

# ---- kernel table ----
say_("## Convolution kernel table (kernel time = GPU time of one kernel; median over " . scalar(steps_of("kernels", "java")) . " rounds)");
say_("# Java: KernelsBenchmark (all arrays resident on the device, kernel time from the TornadoVM profiler, which has a floor of about 0.5 ms per task). C++: one command buffer with one kernel on shared buffers, GPU clock.");
say_(sprintf "%-3s %6s %-10s %12s %12s %8s   %12s %12s", "C", "batch", "variant", "Java ms", "C++ ms", "C++/Java", "Java GFLOP/s", "C++ GFLOP/s");
my %kk = map { $_ => 1 } (keys %kern_java, keys %kern_cpp);
for my $key (sort { my @a = split /\|/, $a; my @b = split /\|/, $b; $a[0] <=> $b[0] or $a[1] <=> $b[1] or $a[2] cmp $b[2] } keys %kk) {
  my ($c, $b, $v) = split /\|/, $key;
  my $jt = median(map { $_->[0] } @{ $kern_java{$key} || [] }); my $ct = median(map { $_->[0] } @{ $kern_cpp{$key} || [] });
  my $jg = median(map { $_->[1] } @{ $kern_java{$key} || [] }); my $cg = median(map { $_->[1] } @{ $kern_cpp{$key} || [] });
  say_(sprintf "%-3d %6d %-10s %12s %12s %8s   %12s %12s", $c, $b, $v, fmt($jt, 3), fmt($ct, 3), defined $jt && defined $ct && $jt > 0 ? sprintf("%.3f", $ct / $jt) : "NA", fmt($jg, 1), fmt($cg, 1));
}
say_("");

# ---- cost of the checks ----
say_("## Cost of the checks (batch 256): release-hardened against release, per pair, median of the ratio release/hardened");
say_(sprintf "%-13s %-6s %14s %14s %9s %s", "impl", "preset", "hardened", "release", "rel/hard", "cost of the checks");
my %ck;
for my $s (steps_of("checks")) { $ck{ $s->{pair} }{ $s->{lang} } = $row{ $s->{name} } }
for my $impl (qw(simd simd-nofma metal-copy metal-shared)) {
  for my $preset (qw(small large)) {
    my $key = "$impl|$preset|u5|256";
    my (@h, @r, @q);
    for my $p (sort keys %ck) {
      my $h = $ck{$p}{"cpp-hard"}{$key}; my $r = $ck{$p}{"cpp-rel"}{$key};
      next unless $h && $r; push @h, $h->{eps}; push @r, $r->{eps}; push @q, $r->{eps} / $h->{eps};
    }
    next unless @q;
    my $q = median(@q);
    say_(sprintf "%-13s %-6s %14.1f %14.1f %9.3f %s", $impl, $preset, median(@h), median(@r), $q, abs($q - 1) > 0.05 ? sprintf("%.1f %% (real)", 100 * (1 - 1 / $q)) : "not resolved (within 5 %)");
  }
}
say_("");

# ---- cold start ----
say_("## Cold start: process start to the end of the first forward pass (LARGE, u5), seconds of process wall time, median of 3 runs; construct and first call from the program");
say_(sprintf "%-8s %6s %12s %12s %12s", "program", "batch", "process s", "construct ms", "first ms");
my %coldrows;
for my $s (steps_of("cold")) {
  next unless $s->{name} =~ /^cold-r\d+-b(\d+)-(\S+)$/;
  my ($b, $prog) = ($1, $2);
  push @{ $coldrows{"$prog|$b"} }, [$s->{wall_s}, @{ $cold{ $s->{name} } || [undef, undef] }];
}
for my $key (sort keys %coldrows) {
  my ($prog, $b) = split /\|/, $key; my @r = @{ $coldrows{$key} };
  say_(sprintf "%-22s %6d %12s %12s %12s", $prog, $b, fmt(median(map { $_->[0] } @r), 3), fmt(median(grep { defined } map { $_->[1] } @r), 1), fmt(median(grep { defined } map { $_->[2] } @r), 1));
}
say_("");

# ---- anchors ----
say_("## Anchor rows (batch 256), evals/s per phase of the session; start against end must agree within 2 % for every program");
say_(sprintf "%-5s %-13s %-6s %10s %10s %10s %9s  %s", "lang", "impl", "preset", "start", "mid", "end", "end/start", "check");
for my $lang (qw(java cpp)) {
  my %a;
  for my $s (grep { $_->{kind} eq "anchors" && $_->{lang} eq $lang } @steps) {
    next unless $s->{name} =~ /^anchors-(\w+)-/;
    my $phase = $1;
    for my $key (keys %{ $row{ $s->{name} } }) { $a{$key}{$phase} = $row{ $s->{name} }{$key}{eps} }
  }
  for my $key (sort keys %a) {
    my ($impl, $preset) = split /\|/, $key;
    my ($st, $mi, $en) = @{ $a{$key} }{qw(start mid end)};
    my $q = defined $st && defined $en && $st > 0 ? $en / $st : undef;
    my $ok = defined $q && $q >= 0.98 && $q <= 1.02;
    say_(sprintf "%-5s %-13s %-6s %10s %10s %10s %9s  %s", $lang, $impl, $preset, fmt($st, 1), fmt($mi, 1), fmt($en, 1), defined $q ? sprintf("%.4f", $q) : "NA", $ok ? "ok" : "OUTSIDE 0.98 to 1.02");
    push @fail, sprintf("CHECK FAILED: anchor %s %s %s end/start = %s, outside 0.98 to 1.02", $lang, $impl, $preset, defined $q ? sprintf("%.4f", $q) : "NA") unless $ok;
  }
}
say_("");

# ---- validity of the CPU rows: thread CPU time against wall time ----
my (@cw, @low);
for my $s (@steps) { next unless $s->{lang} =~ /^cpp/ && $s->{kind} =~ /^(main|gen11|checks)$/;
  for my $k (keys %{ $cpuwall{ $s->{name} } || {} }) { next unless $k =~ /^(reference|simd|simd-nofma)\|/; push @cw, $cpuwall{ $s->{name} }{$k}; push @low, "$s->{name} $k" if $cpuwall{ $s->{name} }{$k} < 0.8 } }
say_("## Validity of the C++ CPU rows");
say_(sprintf "  thread CPU time / wall time of %d CPU cases: min %s, median %s, %d below 0.95, %d below 0.80", scalar(@cw), fmt(min(@cw), 3), fmt(median(@cw), 3), scalar(grep { $_ < 0.95 } @cw), scalar(@low));
push @fail, "CHECK FAILED: CPU case with thread CPU time below 0.8 of the wall time: $_" for @low;
my @loads = map { my $n = $_->{name}; map { $_->{load} } grep { defined $_->{load} } values %{ $row{$n} } } @steps;
say_(sprintf "  load average (1 min) at the start of the cases: min %s, median %s, max %s", fmt(min(@loads), 2), fmt(median(@loads), 2), fmt(max(@loads), 2));
my $gcn = 0; my $gcms = 0; for my $k (keys %gc) { for my $n (keys %{ $gc{$k} }) { $gcn += $gc{$k}{$n}[0]; $gcms += $gc{$k}{$n}[1] } }
say_("  Java: $gcn collections, $gcms ms of GC pause in the measured rounds of all runs");
say_("  skipped cases (one call above 3 s): " . scalar(@skipped) . " (" . join("; ", @skipped[0 .. min(5, $#skipped)]) . (@skipped > 6 ? "; ..." : "") . ")") if @skipped;
say_("");
say_("## Steps");
say_(sprintf "  %-30s %8s %6s %6s %8s  %s", "step", "wall s", "load<", "load>", "other %", "top 3 programs by CPU before the step");
say_(sprintf "  %-30s %8.1f %6.2f %6.2f %8.0f  %s", $_->{name}, $_->{wall_s}, $_->{load_before}, $_->{load_after}, $_->{foreign_cpu_max}, $_->{top3_before}) for @steps;
say_("");
say_($_) for @fail;
print $out;
exit 0;
