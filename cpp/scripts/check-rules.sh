#!/bin/zsh
# usage: check-rules.sh [--root DIR]     check the memory-safety rules that no compiler flag enforces; exit 1 on a finding
#        check-rules.sh --self-test      plant one violation per rule in a temporary tree and prove that each one is found
#
# Rules (docs/cpp-comparison-plan.md, "Memory-safety rules"), comments and string literals are ignored:
#   R1  new / delete / malloc / calloc / realloc / free outside src/gpu/
#   R2  memcpy, and #pragma clang unsafe_buffer_usage, outside the files of the unsafe-zone list
#   R3  -ffast-math (or -Ofast) in any build file or script
#   R4  -ffp-contract=off missing from CMakePresets.json or build-cuda.sh
#   R5  -ffp-contract=fast on a line that does not name simd_net
#   R6  <print>, <format>, <mdspan>, <generator> in the files that the Linux build (gcc 12) compiles
# Every rule prints how many files it scanned, so "no findings" can be told from "saw nothing".
# Unsafe-zone files, as built by WP0 (the spike showed that std::copy_n plus std::bit_cast and CC_SHA256_Update need no zone;
# std::memcpy and argv indexing do): args.hpp, simd_net.cpp, metal_net.mm, kernels.metal, cuda_net.cu, kernels.cuh.
setopt err_exit pipe_fail no_unset

here=${0:A:h}

check() {   # $1 = root directory of the cpp tree
  perl -CSDA -Mutf8 -e '
use strict; use warnings;
my $root = shift @ARGV;
my %zone = map { $_ => 1 } qw(args.hpp simd_net.cpp metal_net.mm kernels.metal cuda_net.cu kernels.cuh);
# The one file that may replace the global operator new and delete: the counting allocator of nn_test (WP10).
my %allocator = map { $_ => 1 } qw(alloc_counter.hpp);
my $findings = 0;

sub slurp { my ($f) = @_; open my $h, "<", $f or die "$f: $!"; local $/; my $t = <$h>; return $t }
sub files { my ($dir, $re) = @_; my @out; return @out unless -d "$root/$dir";
  open my $p, "-|", "find", "$root/$dir", "-type", "f" or die; while (<$p>) { chomp; push @out, $_ if m{$re} } close $p; return sort @out }
sub rel { my ($f) = @_; $f =~ s{^\Q$root\E/}{}; return $f }
# Removes comments and the contents of string and character literals, keeps line structure.
sub strip { my ($t) = @_;
  $t =~ s{ (R"\((?:.*?)\)") | ("(?:\\.|[^"\\\n])*") | (\x27(?:\\.|[^\x27\\\n])*\x27) | (//[^\n]*) | (/\*.*?\*/) }
         { defined $1 || defined $2 ? "\"\"" : defined $3 ? "\x27\x27" : defined $5 ? join("", $5 =~ m/\n/g) : "" }gsxe;
  return $t }
sub report { my ($rule, $file, $n, $msg) = @_; printf "FINDING %s %s:%d: %s\n", $rule, rel($file), $n, $msg; $findings++ }

my @code = (files("src", qr/\.(cpp|hpp|cc|h|mm|cu|cuh|metal)$/), files("apps", qr/\.(cpp|hpp|mm)$/), files("tests", qr/\.(cpp|hpp|mm)$/));
my @build = ((grep { -f } map { "$root/$_" } qw(CMakeLists.txt CMakePresets.json)), files("cmake", qr/\.cmake$/), files("scripts", qr/\.(sh|pl|py)$/));
@build = grep { !m{/check-rules\.sh$} } @build;

# R1 and R2
my ($r1, $r1lines, $r2, $r2lines) = (0, 0, 0, 0);
for my $f (@code) {
  my ($base) = $f =~ m{([^/]+)$};
  my $inGpu = rel($f) =~ m{^src/gpu/} || $allocator{$base};
  my @lines = split /\n/, strip(slurp($f)), -1;
  $r1++ unless $inGpu; $r2++ unless $zone{$base};
  for my $i (0 .. $#lines) {
    my $l = $lines[$i]; my $n = $i + 1;
    next if $l =~ /^\s*#\s*include/;
    unless ($inGpu) {
      $r1lines++;
      report("R1", $f, $n, "new outside src/gpu/") if $l =~ /(?<!operator)\bnew\b/;
      report("R1", $f, $n, "delete outside src/gpu/") if $l =~ /\bdelete\b/ and $l !~ /=\s*delete\b/;
      report("R1", $f, $n, "$1 outside src/gpu/") if $l =~ /\b(malloc|calloc|realloc|free)\s*\(/;
    }
    unless ($zone{$base}) {
      $r2lines++;
      report("R2", $f, $n, "memcpy outside the unsafe-zone files") if $l =~ /\bmemcpy\b/;
      report("R2", $f, $n, "unsafe_buffer_usage pragma outside the unsafe-zone files") if $l =~ /unsafe_buffer_usage/;
    }
  }
}
printf "R1 new/delete/malloc/free: %d files, %d lines scanned\n", $r1, $r1lines;
printf "R2 memcpy and unsafe_buffer_usage pragmas: %d files, %d lines scanned\n", $r2, $r2lines;

# R3 and R5 over the build files
my ($r3lines, $r5lines) = (0, 0);
for my $f (@build) {
  my @lines = split /\n/, slurp($f), -1;
  for my $i (0 .. $#lines) {
    my $l = $lines[$i]; my $n = $i + 1;
    $r3lines++;
    report("R3", $f, $n, "fast-math flag") if $l =~ /-ffast-math|-Ofast/;
    if ($l =~ /-ffp-contract=fast/) { $r5lines++; report("R5", $f, $n, "-ffp-contract=fast on a line that does not name simd_net") unless $l =~ /simd_net/ }
  }
}
printf "R3 fast-math: %d files, %d lines scanned\n", scalar(@build), $r3lines;
printf "R5 fp-contract=fast only for simd_net: %d lines with the flag\n", $r5lines;

# R4: the flag must be in the real values, not in a comment or in a printed text.
#   CMakePresets.json: CMAKE_CXX_FLAGS and CMAKE_OBJCXX_FLAGS;
#   scripts/build-cuda.sh: the cxxflags array and, after "-Xcompiler", the nvcc flags (shell comments and print/echo lines are dropped first).
my $r4 = 0;
{
  my $f = "$root/CMakePresets.json"; $r4++;
  if (!-f $f) { report("R4", $f, 0, "file missing") }
  else {
    my $t = slurp($f);
    for my $var (qw(CMAKE_CXX_FLAGS CMAKE_OBJCXX_FLAGS)) {
      report("R4", $f, 0, "-ffp-contract=off missing from $var") unless $t =~ /"$var"\s*:\s*"[^"]*-ffp-contract=off/;
    }
  }
  $f = "$root/scripts/build-cuda.sh"; $r4++;
  if (!-f $f) { report("R4", $f, 0, "file missing") }
  else {
    my $code = join "\n", grep { !/^\s*#/ && !/^\s*(?:print|echo|printf)\b/ } split /\n/, slurp($f);
    report("R4", $f, 0, "-ffp-contract=off missing from the cxxflags array") unless $code =~ /^\s*cxxflags=\([^\n]*-ffp-contract=off/m;
    report("R4", $f, 0, "-Xcompiler -ffp-contract=off missing from the nvcc flags") unless $code =~ /-Xcompiler\s+-ffp-contract=off/;
  }
}
printf "R4 fp-contract=off present: %d files checked\n", $r4;

# R6
my @linux = grep { my $r = rel($_); $r =~ m{^src/(core|nn|gpu/cuda)/} or $r =~ m{^apps/nn_} or $r =~ m{^tests/(cuda_test|cuda_adapter_test|nn_test|java_compat_test)\.} } @code;
my $r6lines = 0;
for my $f (@linux) {
  my @lines = split /\n/, slurp($f), -1;
  for my $i (0 .. $#lines) { $r6lines++;
    report("R6", $f, $i + 1, "header that libstdc++ 12 lacks: $1") if $lines[$i] =~ /^\s*#\s*include\s*<(print|format|mdspan|generator)>/ }
}
printf "R6 headers of the Linux build: %d files, %d lines scanned\n", scalar(@linux), $r6lines;

print "check-rules: ", ($findings ? "$findings finding(s)" : "clean"), "\n";
exit($findings ? 1 : 0);
' "$1"
}

if [[ ${1:-} == --self-test ]]; then
  tmp=$(mktemp -d)
  trap 'rm -rf $tmp' EXIT
  clean() {   # $1 = dir
    mkdir -p $1/src/core $1/src/gpu $1/src/nn $1/apps $1/tests $1/scripts $1/cmake
    print -r -- 'int main() { return 0; }  // new delete malloc( memcpy in a comment, "new" in a string' > $1/src/core/ok.cpp
    print -r -- '{ "CMAKE_CXX_FLAGS": "-ffp-contract=off", "CMAKE_OBJCXX_FLAGS": "-ffp-contract=off" }' > $1/CMakePresets.json
    print -r -- 'cxxflags=(-std=c++23 -ffp-contract=off)\nnvccflags=(-O3 -Xcompiler -ffp-contract=off)' > $1/scripts/build-cuda.sh
    print -r -- 'set_source_files_properties(src/nn/simd_net.cpp PROPERTIES COMPILE_OPTIONS "-ffp-contract=fast")' > $1/CMakeLists.txt
  }
  clean $tmp/clean
  if check $tmp/clean > $tmp/clean.out; then print "self-test ok:   clean tree is clean"; else print "self-test FAIL: clean tree flagged"; cat $tmp/clean.out; exit 1; fi
  failed=0
  plant() {   # $1 = rule, $2 = relative file, $3 = content
    local d=$tmp/$1; clean $d
    mkdir -p ${d}/${2:h}; print -r -- $3 > $d/$2
    if check $d > $d.out 2>&1; then print "self-test FAIL: $1 not found"; failed=1
    elif grep -q "FINDING $1 " $d.out; then print "self-test ok:   $1 found"
    else print "self-test FAIL: $1 exited 1 for another reason"; cat $d.out; failed=1; fi
  }
  plant R1 src/core/bad.cpp 'int* p = new int(3);'
  plant R1 src/nn/bad2.cpp 'void f(void* p) { free(p); }'
  plant R2 src/core/bad3.cpp 'void f(char* a, char* b) { memcpy(a, b, 1); }'
  plant R2 src/core/bad4.hpp '#pragma clang unsafe_buffer_usage begin'
  plant R3 CMakeLists.txt 'add_compile_options(-ffast-math)'
  plant R5 scripts/other.sh 'g++ -ffp-contract=fast x.cpp'
  plant R6 src/core/bad5.cpp '#include <print>'
  d=$tmp/R4; clean $d; print 'nothing' > $d/CMakePresets.json
  if check $d > $d.out 2>&1; then print "self-test FAIL: R4 not found"; failed=1; elif grep -q "FINDING R4 " $d.out; then print "self-test ok:   R4 found"; else print "self-test FAIL: R4"; failed=1; fi
  d=$tmp/R4b; clean $d   # the flag is only in a comment and in a printed text of build-cuda.sh
  print -r -- '# -ffp-contract=off everywhere\ncxxflags=(-std=c++23)\nnvccflags=(-O3)\nprint "fma: -ffp-contract=off"' > $d/scripts/build-cuda.sh
  if check $d > $d.out 2>&1; then print "self-test FAIL: R4 not found for build-cuda.sh with the flag only in comments"; failed=1; elif grep -q "FINDING R4 " $d.out; then print "self-test ok:   R4 found (flag only in a comment and a print)"; else print "self-test FAIL: R4b"; failed=1; fi
  (( failed )) && { print "self-test: failed"; exit 1; }
  print "self-test: every rule found its planted violation"
  exit 0
fi

root=${here:h}
if [[ ${1:-} == --root ]]; then root=${2:?--root needs a directory}; fi
check $root
