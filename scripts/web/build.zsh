#!/bin/zsh
# Builds the static (offline) site: vite build into web/dist, then checks the bundle.
# BASE_PATH defaults to /marble-lantern/ (the GitHub Pages project path). Exit 0 = built and all checks passed.
set -eu
root=${0:A:h:h:h}
cd $root/web
export BASE_PATH=${BASE_PATH:-/marble-lantern/}
if [[ ! -d node_modules/.bin ]]; then
  print -u2 "web:build: web/node_modules is missing; run 'devenv tasks run web:install' first"
  exit 1
fi
npm run --silent typecheck
$root/scripts/web/models.zsh
rm -rf dist
npm exec --no -- vite build

# Check 1: index.html refers to assets under the base path.
if ! perl -ne '$n++ if m{(?:src|href)="\Q'$BASE_PATH'\Eassets/}; END { exit($n ? 0 : 1) }' dist/index.html; then
  print -u2 "web:build: dist/index.html does not reference ${BASE_PATH}assets/"; exit 1
fi

# Check 2: the static build holds no Hilla client code. 'connect/' is the path of every Hilla endpoint call.
# The scan must be able to see something: it counts files and bytes, and looks for a string that must be there.
perl -e '
  my ($files, $bytes, $hits, $control) = (0, 0, 0, 0);
  for my $f (glob "dist/assets/*.js") {
    open my $h, "<:raw", $f or die "$f: $!"; local $/; my $s = <$h>;
    $files++; $bytes += length $s;
    my $n = () = $s =~ m{connect/}g;
    $hits += $n; $control++ if $s =~ /Marble Lantern/;
    print STDERR "  $f: connect/ = $n\n";
  }
  die "web:build: the bundle scan saw no JavaScript file\n" unless $files && $bytes;
  die "web:build: the bundle scan did not find the control string (it reads the wrong files?)\n" unless $control;
  die "web:build: $hits occurrence(s) of connect/ in the static bundle\n" if $hits;
  print STDERR "web:build: bundle scan ok ($files files, $bytes bytes, 0 x connect/)\n";
'
# Check 3: the model files are in dist and verify against their manifest (as in scripts/web/models.zsh).
if [[ ! -d dist/models ]]; then print -u2 "web:build: dist/models is missing"; exit 1; fi
../scripts/models/pack-model.py verify --out dist/models

# Check 4: the entry chunk (the one index.html loads) holds no WebGPU code: the probe loads lazily and the backend lives in the worker.
# The control: some other chunk must contain 'requestAdapter', else the scan reads the wrong files or the probe vanished.
perl -e '
  open my $h, "<", "dist/index.html" or die "dist/index.html: $!"; local $/; my $html = <$h>; close $h;
  my ($entry) = $html =~ m{<script[^>]*\ssrc="[^"]*?(assets/[^"/]+\.js)"} or die "web:build: no entry script in dist/index.html\n";
  my ($in_entry, $elsewhere, $seen) = (0, 0, 0);
  for my $f (glob "dist/assets/*.js") {
    open my $c, "<:raw", $f or die "$f: $!"; my $s = <$c>; close $c; $seen++;
    my $n = () = $s =~ /requestAdapter/g;
    if ($f eq "dist/$entry") { $in_entry += $n } else { $elsewhere += $n }
  }
  die "web:build: entry chunk scan saw no file\n" unless $seen;
  die "web:build: the entry chunk dist/$entry contains requestAdapter (WebGPU code in the main bundle)\n" if $in_entry;
  die "web:build: no chunk contains requestAdapter (the control of the WebGPU scan failed)\n" unless $elsewhere;
  print STDERR "web:build: entry chunk $entry has no WebGPU code ($elsewhere x requestAdapter in other chunks)\n";
'
print -u2 "web:build: dist/ is ready (base $BASE_PATH)"
