#!/bin/zsh
# Writes web/public/engines/engines.json: every shipped engine file with raw bytes, gzip bytes and SHA-256,
# grouped by engine id, plus the git version of the build. Reads web/public/engines/{cpp,java,net}/ (net-wasm and net-webgpu: the same nine files, the three flavours of the CPU network backend, js and wasm each, plus the TeaVM facade engine-net.*; net-webgpu falls back to the CPU backend).
# Exit 0 = written, non-zero = no engine file found at all or a classical engine with missing files. A missing or incomplete engine is a warning for net-wasm and net-webgpu: the group is dropped and the UI shows the engine as not shipped.
# ENGINES_STRICT=1 (scripts/web/ci.zsh sets it, CI builds everything) makes a missing or incomplete net group an error.
# Each net group also carries downloadGzipBytes: what a typical load fetches (the Wasm GC facade and the best CPU flavour, gzip), for the engine sheet. binaryGzipBytes stays the sum of all files.
set -eu
root=${0:A:h:h:h}
dir=$root/web/public/engines
version=$(git -C $root describe --always --dirty 2>/dev/null || print unknown)

DIR=$dir VERSION=$version STRICT=${ENGINES_STRICT:-} perl -MJSON::PP -MDigest::SHA=sha256_hex -MIO::Compress::Gzip=gzip,\$GzipError -e '
  my $dir = $ENV{DIR};
  # file name -> engine id. The TeaVM targets are renamed engine-java.* in teavm-web/build.gradle.kts.
  # The net engines share all nine files: the TeaVM facade (engine-net.*: net-MCTS without the forward pass) and the C++ forward pass
  # (engine-net-nn-*, the CPU backend). net-webgpu needs the CPU backend too: it falls back to it when WebGPU is missing or lost.
  my @facade = ("net/engine-net.wasm", "net/engine-net.wasm-runtime.js", "net/engine-net.js");
  my @flavours = map { ("net/engine-net-nn-$_.js", "net/engine-net-nn-$_.wasm") } qw(scalar simd relaxed);
  my %engine_of = (
    "cpp/engine-cpp.js"                  => ["cpp-wasm"],
    "cpp/engine-cpp.wasm"                => ["cpp-wasm"],
    "java/engine-java.wasm"              => ["java-wasmgc"],
    "java/engine-java.wasm-runtime.js"   => ["java-wasmgc"],
    "java/engine-java.js"                => ["java-js"],
    (map { $_ => ["net-wasm", "net-webgpu"] } @facade),
    (map { $_ => ["net-wasm", "net-webgpu"] } @flavours),
  );
  my (%engines, $found);
  for my $rel (sort keys %engine_of) {
    my $path = "$dir/$rel";
    next unless -f $path;
    open my $h, "<:raw", $path or die "$path: $!"; local $/; my $raw = <$h>; close $h;
    die "$path is empty\n" unless length $raw;
    my $gz; gzip(\$raw => \$gz, -Level => 9) or die "gzip $path: $GzipError\n";
    for my $id (@{ $engine_of{$rel} }) {
      my $e = $engines{$id} //= { files => [], binaryBytes => 0, binaryGzipBytes => 0 };
      push @{ $e->{files} }, { path => "engines/$rel", bytes => length $raw, gzipBytes => length $gz, sha256 => sha256_hex($raw) };
      $e->{binaryBytes} += length $raw; $e->{binaryGzipBytes} += length $gz;
    }
    $found++;
  }
  die "web:engines: no engine file under $dir (run wasm:build and teavm:build first)\n" unless $found;
  my $strict = $ENV{STRICT};
  my %is_net = map { $_ => 1 } qw(net-wasm net-webgpu);
  for my $id (qw(cpp-wasm java-wasmgc java-js net-wasm net-webgpu)) {
    next if $engines{$id};
    die "web:engines: strict: no files for $id\n" if $strict && $is_net{$id};
    print STDERR "web:engines: warning: no files for $id\n";
  }
  # Each engine needs all of its files; a half-built engine is worse than none (java-js: its one file).
  for my $id (sort keys %engines) {
    my $n = @{ $engines{$id}{files} };
    my $want = $id eq "cpp-wasm" || $id eq "java-wasmgc" ? 2 : $is_net{$id} ? 9 : 1;
    next if $n == $want;
    if ($is_net{$id} && !$strict) {
      print STDERR "web:engines: warning: $id has $n file(s), expected $want: the engine is left out\n";
      delete $engines{$id};
      next;
    }
    die "web:engines: $id has $n file(s), expected $want\n";
  }
  # Typical load of a net engine: the Wasm GC facade (wasm + runtime) and the best CPU flavour (relaxed: glue + wasm).
  my @typical = ("engines/net/engine-net.wasm", "engines/net/engine-net.wasm-runtime.js", "engines/net/engine-net-nn-relaxed.js", "engines/net/engine-net-nn-relaxed.wasm");
  for my $id (grep { $engines{$_} } keys %is_net) {
    my %gz = map { $_->{path} => $_->{gzipBytes} } @{ $engines{$id}{files} };
    my $sum = 0; $sum += $gz{$_} // die "web:engines: $id lacks $_\n" for @typical;
    $engines{$id}{downloadGzipBytes} = $sum;
  }
  die "web:engines: no complete engine left\n" unless %engines;
  my $json = JSON::PP->new->canonical->pretty->encode({ v => 1, version => $ENV{VERSION}, engines => \%engines });
  my $out = "$dir/engines.json"; my $tmp = "$out.tmp.$$";
  open my $o, ">", $tmp or die "$tmp: $!"; print $o $json; close $o or die "close: $!";
  rename $tmp, $out or die "rename: $!";
  print STDERR "web:engines: wrote $out (" . scalar(keys %engines) . " engines, $found files)\n";
'
