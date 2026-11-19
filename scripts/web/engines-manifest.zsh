#!/bin/zsh
# Writes web/public/engines/engines.json: every shipped engine file with raw bytes, gzip bytes and SHA-256,
# grouped by engine id, plus the git version of the build. Reads web/public/engines/{cpp,java,net}/ (net-wasm: the three flavours of the network backend, js and wasm each).
# Exit 0 = written, non-zero = no engine file found at all or an engine with missing files. A missing engine is a warning: the UI then shows it as not shipped.
set -eu
root=${0:A:h:h:h}
dir=$root/web/public/engines
version=$(git -C $root describe --always --dirty 2>/dev/null || print unknown)

DIR=$dir VERSION=$version perl -MJSON::PP -MDigest::SHA=sha256_hex -MIO::Compress::Gzip=gzip,\$GzipError -e '
  my $dir = $ENV{DIR};
  # file name -> engine id. The TeaVM targets are renamed engine-java.* in teavm-web/build.gradle.kts.
  my %engine_of = (
    "cpp/engine-cpp.js"                  => "cpp-wasm",
    "cpp/engine-cpp.wasm"                => "cpp-wasm",
    "java/engine-java.wasm"              => "java-wasmgc",
    "java/engine-java.wasm-runtime.js"   => "java-wasmgc",
    "java/engine-java.js"                => "java-js",
    "net/engine-net-nn-scalar.js"        => "net-wasm",
    "net/engine-net-nn-scalar.wasm"      => "net-wasm",
    "net/engine-net-nn-simd.js"          => "net-wasm",
    "net/engine-net-nn-simd.wasm"        => "net-wasm",
    "net/engine-net-nn-relaxed.js"       => "net-wasm",
    "net/engine-net-nn-relaxed.wasm"     => "net-wasm",
  );
  my (%engines, $found);
  for my $rel (sort keys %engine_of) {
    my $path = "$dir/$rel";
    next unless -f $path;
    open my $h, "<:raw", $path or die "$path: $!"; local $/; my $raw = <$h>; close $h;
    die "$path is empty\n" unless length $raw;
    my $gz; gzip(\$raw => \$gz, -Level => 9) or die "gzip $path: $GzipError\n";
    my $e = $engines{ $engine_of{$rel} } //= { files => [], binaryBytes => 0, binaryGzipBytes => 0 };
    push @{ $e->{files} }, { path => "engines/$rel", bytes => length $raw, gzipBytes => length $gz, sha256 => sha256_hex($raw) };
    $e->{binaryBytes} += length $raw; $e->{binaryGzipBytes} += length $gz; $found++;
  }
  die "web:engines: no engine file under $dir (run wasm:build and teavm:build first)\n" unless $found;
  for my $id (qw(cpp-wasm java-wasmgc java-js net-wasm)) {
    print STDERR "web:engines: warning: no files for $id\n" unless $engines{$id};
  }
  # java-wasmgc needs both of its files; a half-built engine is worse than none.
  for my $id (keys %engines) {
    my $n = @{ $engines{$id}{files} };
    my $want = $id eq "cpp-wasm" || $id eq "java-wasmgc" ? 2 : $id eq "net-wasm" ? 6 : 1;
    die "web:engines: $id has $n file(s), expected $want\n" if $n != $want;
  }
  my $json = JSON::PP->new->canonical->pretty->encode({ v => 1, version => $ENV{VERSION}, engines => \%engines });
  my $out = "$dir/engines.json"; my $tmp = "$out.tmp.$$";
  open my $o, ">", $tmp or die "$tmp: $!"; print $o $json; close $o or die "close: $!";
  rename $tmp, $out or die "rename: $!";
  print STDERR "web:engines: wrote $out (" . scalar(keys %engines) . " engines, $found files)\n";
'
