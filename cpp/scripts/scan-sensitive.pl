#!/usr/bin/env perl
# usage: scan-sensitive.pl <file>...        scan files for sensitive data (formats, not words); exit 1 on a hit
#        scan-sensitive.pl --self-test      prove that every pattern finds its planted example; exit 1 otherwise
#
# Patterns (see docs/cpp-comparison-plan.md, "Disclosure and sensitive data"):
#   12-digit numbers (account ids), AWS resource ids (ami-, i-, sg-, sir-, vol- followed by hex), IPv4 addresses,
#   ip- and ec2- host names, compute.internal and amazonaws.com host names, e-mail addresses, absolute home paths,
#   the values of AWS_PROFILE, USER and the local host name from the environment.
# For every file the script prints how many lines it read, so an empty result can be told apart from a scan that saw nothing.
# Exit codes: 0 clean, 1 hit (or failed self-test), 2 usage or unreadable file.
use strict;
use warnings;
use utf8;

# Examples are assembled from fragments, so that this file does not match its own patterns.
my @planted = (
    ['account id',         'account ' . '123456' . '789012' . ' end'],
    ['AMI id',             'image ' . 'ami' . '-0' . '123456789abcdef0'],
    ['instance id',        'instance ' . 'i' . '-0' . '123456789abcdef0'],
    ['security group id',  'group ' . 'sg' . '-0' . '123456789abcdef0'],
    ['spot request id',    'request ' . 'sir' . '-' . 'abcdef12'],
    ['volume id',          'volume ' . 'vol' . '-0' . '123456789abcdef0'],
    ['IPv4 address',       'addr ' . '203.0' . '.113.7' . ' end'],
    ['ip- host name',      'host ' . 'ip' . '-10-0-1-5' . ' end'],
    ['ec2- host name',     'host ' . 'ec2' . '-3-4-5-6' . '.eu-central-1'],
    ['internal host name', 'host ' . 'node1.eu-central-1.' . 'compute' . '.internal'],
    ['amazonaws host',     'url ' . 'https://s3.' . 'amazon' . 'aws.com/bucket'],
    ['e-mail address',     'mail ' . 'someone' . '@' . 'example.org'],
    ['absolute home path (macOS)', 'path ' . '/Us' . 'ers/someone/project'],
    ['absolute home path (Linux)', 'path ' . '/ho' . 'me/someone/project'],
);

# Each entry: name, regex.
my @patterns = (
    ['account id',         qr/(?<![\d.\-])\d{12}(?![\d.])/],
    ['AMI id',             qr/\bami-[0-9a-f]{8,17}\b/],
    ['instance id',        qr/\bi-[0-9a-f]{8,17}\b/],
    ['security group id',  qr/\bsg-[0-9a-f]{8,17}\b/],
    ['spot request id',    qr/\bsir-[0-9a-z]{8}\b/],
    ['volume id',          qr/\bvol-[0-9a-f]{8,17}\b/],
    ['IPv4 address',       qr/(?<![\d.])(?:(?:25[0-5]|2[0-4]\d|1?\d?\d)\.){3}(?:25[0-5]|2[0-4]\d|1?\d?\d)(?![\d.]*\d)/],
    ['ip- host name',      qr/\bip-\d{1,3}-\d{1,3}-\d{1,3}-\d{1,3}\b/],
    ['ec2- host name',     qr/\bec2-\d{1,3}-\d{1,3}-\d{1,3}-\d{1,3}\b/],
    ['internal host name', qr/[\w.\-]+\.compute\.internal\b/],
    ['amazonaws host',     qr/[\w.\-]+\.amazonaws\.com\b/],
    ['e-mail address',     qr/[\w.+\-]+@[A-Za-z0-9\-]+(?:\.[A-Za-z0-9\-]+)+/],
    ['absolute home path (macOS)', qr{/[U]sers/[^/\s]+}],
    ['absolute home path (Linux)', qr{/[h]ome/[^/\s]+}],
);

# Values from the environment: AWS_PROFILE, USER and the host name. Values shorter than 3 characters are ignored.
sub env_patterns {
    my @out;
    my %vals = (
        AWS_PROFILE => $ENV{AWS_PROFILE},
        USER        => $ENV{USER},
        hostname    => do { my $h = `hostname 2>/dev/null`; defined $h ? $h : '' },
    );
    for my $k (sort keys %vals) {
        my $v = $vals{$k} // '';
        $v =~ s/\s+\z//;
        next if length($v) < 3;
        push @out, ["value of $k", qr/(?<![\w])\Q$v\E(?![\w])/];
    }
    return @out;
}

sub scan_line {
    my ($line, $pats) = @_;
    my @hits;
    for my $p (@$pats) {
        push @hits, $p->[0] if $line =~ $p->[1];
    }
    return @hits;
}

if (@ARGV == 1 and $ARGV[0] eq '--self-test') {
    my $failed = 0;
    for my $pl (@planted) {
        my ($name, $text) = @$pl;
        my @hits = scan_line($text, \@patterns);
        if (grep { $_ eq $name } @hits) {
            print "self-test ok:   $name\n";
        } else {
            print "self-test FAIL: $name (planted line not found)\n";
            $failed++;
        }
    }
    # Environment values: plant a fake value for the duration of the test.
    {
        local $ENV{AWS_PROFILE} = 'planted' . '-profile-name';
        local $ENV{USER} = 'planted' . 'user';
        my @env = env_patterns();
        for my $name ('value of AWS_PROFILE', 'value of USER') {
            my $text = $name eq 'value of USER' ? 'owner plantedu' . 'ser here' : 'profile planted' . '-profile-name here';
            my @hits = scan_line($text, \@env);
            if (grep { $_ eq $name } @hits) { print "self-test ok:   $name\n" }
            else { print "self-test FAIL: $name (planted line not found)\n"; $failed++ }
        }
    }
    # Negative controls: ordinary content must stay clean (a scanner that flags everything is no scanner either).
    for my $clean ('nodes 4852147404152522458 checksum', 'version 1.2.3 of the tool', 'int x = a[3] + 42;') {
        my @hits = scan_line($clean, \@patterns);
        if (@hits) { print "self-test FAIL: clean line flagged as @hits: $clean\n"; $failed++ }
        else { print "self-test ok:   clean line stays clean\n" }
    }
    if ($failed) { print "self-test: $failed failure(s)\n"; exit 1 }
    print "self-test: all patterns found their planted example\n";
    exit 0;
}

die "usage: scan-sensitive.pl <file>... | --self-test\n" unless @ARGV;

my @all = (@patterns, env_patterns());
my ($hits, $unreadable, $total_lines) = (0, 0, 0);
for my $file (@ARGV) {
    next if -d $file;
    my $fh;
    unless (open $fh, '<:raw', $file) {
        print STDERR "scan-sensitive: $file: $!\n";
        $unreadable++;
        next;
    }
    my $n = 0;
    while (my $line = <$fh>) {
        $n++;
        $line =~ s/\r?\n\z//;
        for my $name (scan_line($line, \@all)) {
            my $shown = length($line) > 120 ? substr($line, 0, 120) . '...' : $line;
            print "HIT  $file:$n: $name  [$shown]\n";
            $hits++;
        }
    }
    close $fh;
    $total_lines += $n;
    print "read $n lines: $file\n";
}
print "scan-sensitive: " . scalar(@ARGV) . " file(s), $total_lines line(s) read, $hits hit(s)\n";
exit 2 if $unreadable;
exit($hits ? 1 : 0);
