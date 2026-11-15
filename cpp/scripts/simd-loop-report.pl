#!/usr/bin/perl
# usage: simd-loop-report.pl [--check] FILE.s...
#
# Reads g++ assembler (-S) of src/nn/simd_net.cpp and reports the innermost loop around every group of 8 vfmadd231ps (the 4 cells x 2
# tiles accumulators of the convolution): size in instructions, fused multiply-adds, broadcasts, conditional jumps, jumps into a block
# that calls the libstdc++ assertion (-D_GLIBCXX_ASSERTIONS: bounds checks), and operands on the stack (spills; for a vector register
# a store-forwarding risk). With --check the exit code is 1 if a file has no such loop, a loop has a jump to an assertion block, a
# stack operand, or other than 8 fused multiply-adds; the scan prints how many loops and lines it saw ("no findings" is not "saw nothing").
# Example of a loop line:  .L4:  vbroadcastss (%rax), %zmm5 ... vfmadd231ps %zmm15, %zmm5, %zmm13 ... jne .L4
use strict; use warnings;
my $check = @ARGV && $ARGV[0] eq "--check" ? shift @ARGV : 0;
my $bad = 0;
for my $f (@ARGV) {
  open my $h, "<", $f or die "$f: $!"; my @l = <$h>; chomp @l;
  my %lab; for my $i (0 .. $#l) { $lab{$1} = $i if $l[$i] =~ /^(\.L\d+):/ }
  my %assert;   # labels whose block (12 lines) calls the assertion failure or the out-of-range throw
  for my $k (keys %lab) { for my $j ($lab{$k} .. $lab{$k} + 12) { last if $j > $#l; if ($l[$j] =~ /glibcxx_assert_fail|__throw_out_of_range/) { $assert{$k} = 1; last } } }
  my ($loops, $seen) = (0, -1);
  for my $i (0 .. $#l) {
    next unless $l[$i] =~ /vfmadd231ps\s+%([yz])mm/; my $w = $1;
    my $n = grep { $_ <= $#l && $l[$_] =~ /vfmadd231ps\s+%${w}mm/ } ($i .. $i + 40); next unless $n >= 8;
    next if $i > 0 && grep { $l[$_] =~ /vfmadd231ps\s+%${w}mm/ } (($i > 12 ? $i - 12 : 0) .. $i - 1);   # only the first of a group
    my ($best, $end);   # the closest label before the group that a later jump (after the group) goes back to
    for my $k (sort { $lab{$b} <=> $lab{$a} } grep { $lab{$_} < $i } keys %lab) {
      my $e; for my $j (reverse($i .. $#l)) { if ($l[$j] =~ /^\s+j\w+\s+\Q$k\E$/) { $e = $j; last } }
      next unless defined $e && $e - $lab{$k} < 400 && $e > $i; ($best, $end) = ($k, $e); last;
    }
    next unless $best; next if $lab{$best} <= $seen; $seen = $end; $loops++;
    my ($tot, $fma, $jcc, $chk, $stack, $vstack, $bc) = (0) x 7;
    for my $j ($lab{$best} .. $end) {
      next unless $l[$j] =~ /^\t[a-z]/; $tot++;
      $fma++ if $l[$j] =~ /vfmadd/; $bc++ if $l[$j] =~ /vbroadcastss/;
      if ($l[$j] =~ /^\t(j\w+)\s+(\.L\d+)/) { $jcc++ if $1 ne "jmp"; $chk++ if $assert{$2} }
      if ($l[$j] =~ /\((%rsp|%rbp)\)/) { $stack++; $vstack++ if $l[$j] =~ /[xyz]mm/ }
    }
    printf "%s: loop %s (lines %d-%d, %smm): %d instructions, %d fma, %d broadcasts, %d conditional jumps, %d jumps to an assertion block, %d stack operands (%d with a vector register)\n",
      $f, $best, $lab{$best} + 1, $end + 1, $w, $tot, $fma, $bc, $jcc, $chk, $stack, $vstack;
    $bad++ if $chk || $stack || $fma != 8;
  }
  printf "%s: %d lines read, %d convolution loops found\n", $f, scalar @l, $loops;
  $bad++ unless $loops;
}
exit($check && $bad ? 1 : 0);
