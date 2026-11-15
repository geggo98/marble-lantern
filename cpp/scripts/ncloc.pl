#!/usr/bin/env perl
# usage: ncloc.pl <file>...
# Counts NCLOC (non-comment, non-blank lines) per file and in total, for Java, C, C++, CUDA, Objective-C++ and MSL.
# A line counts if, after removing comments, something other than white space is left. String, character and text-block
# literals are respected, so "//" inside a string does not start a comment.
# Output: one "<ncloc>\t<lines read>\t<file>" line per file, then "<total>\t<lines read>\ttotal".
use strict;
use warnings;
use utf8;

die "usage: ncloc.pl <file>...\n" unless @ARGV;
my ($total, $total_lines) = (0, 0);
my $status = 0;

for my $file (@ARGV) {
    my $fh;
    unless (open $fh, '<:encoding(UTF-8)', $file) {
        warn "ncloc.pl: $file: $!\n";
        $status = 1;
        next;
    }
    my ($n, $lines) = (0, 0);
    my $in_block = 0;       # inside /* ... */
    my $in_text = 0;        # inside a Java text block """ ... """
    my $in_raw = 0;         # inside a C++ raw string R"(...)"
    while (my $line = <$fh>) {
        $lines++;
        my $code = '';      # the part of the line that is code
        my $i = 0;
        my $len = length $line;
        while ($i < $len) {
            my $c = substr $line, $i, 1;
            my $two = substr $line, $i, 2;
            if ($in_block) {
                if ($two eq '*/') { $in_block = 0; $i += 2 } else { $i++ }
                next;
            }
            if ($in_text) {
                $code .= $c;
                if ($c eq '\\') { $code .= substr $line, $i + 1, 1; $i += 2; next }
                if (substr($line, $i, 3) eq '"""') { $in_text = 0; $code .= '""'; $i += 3; next }
                $i++;
                next;
            }
            if ($in_raw) {
                $code .= $c;
                if ($two eq ')"') { $in_raw = 0; $code .= '"'; $i += 2; next }
                $i++;
                next;
            }
            if ($two eq '//') { last }
            if ($two eq '/*') { $in_block = 1; $i += 2; next }
            if (substr($line, $i, 3) eq '"""') { $in_text = 1; $code .= '"""'; $i += 3; next }
            if (substr($line, $i, 3) eq 'R"(') { $in_raw = 1; $code .= 'R"('; $i += 3; next }
            if ($c eq '"' or $c eq "'") {
                # plain string or character literal on one line
                $code .= $c;
                $i++;
                while ($i < $len) {
                    my $d = substr $line, $i, 1;
                    $code .= $d;
                    if ($d eq '\\') { $code .= substr $line, $i + 1, 1; $i += 2; next }
                    $i++;
                    last if $d eq $c;
                }
                next;
            }
            $code .= $c;
            $i++;
        }
        $n++ if $code =~ /\S/;
    }
    close $fh;
    printf "%d\t%d\t%s\n", $n, $lines, $file;
    $total += $n;
    $total_lines += $lines;
}
printf "%d\t%d\ttotal\n", $total, $total_lines;
exit $status;
