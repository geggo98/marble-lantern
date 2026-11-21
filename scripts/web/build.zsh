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
    # A hit inside a string literal that holds a space is prose (the Info topic "architecture" documents the server path and this very check);
    # a Hilla client call is a bare path without a space. Only the latter counts.
    my $n = 0;
    while ($s =~ m{connect/}g) {
      my $at = pos($s) - 8;
      my $from = rindex($s, q{"}, $at); my $to = index($s, q{"}, $at + 8);
      my $lit = ($from >= 0 && $to > $from) ? substr($s, $from, $to - $from) : "";
      $n++ unless $lit =~ /\s/;
    }
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
# Check 4b: the texts of the Info topics are not in the entry chunk (plan section 1.4). For every topic catalog, in both languages, the
# longest plain run (letters, digits, space, comma, full stop, hyphen; at least 20 characters) of its "lead" is the marker.
# The control: every marker must occur in some other chunk, else the scan reads the wrong files or the marker does not survive minification.
perl -MJSON::PP -e '
  open my $h, "<", "dist/index.html" or die "dist/index.html: $!"; local $/; my $html = <$h>; close $h;
  my ($entry) = $html =~ m{<script[^>]*\ssrc="[^"]*?(assets/[^"/]+\.js)"} or die "web:build: no entry script in dist/index.html\n";
  my %js;
  for my $f (glob "dist/assets/*.js") { open my $c, "<:raw", $f or die "$f: $!"; $js{$f} = <$c>; close $c; }
  die "web:build: info-text scan saw no file\n" unless %js;
  my ($markers, $leaks) = (0, 0);
  for my $lang (qw(en de)) {
    for my $f (sort glob "src/about/i18n/$lang/*.json") {
      next if $f =~ m{/(?:glossary|chrome|kit)\.json$};
      open my $j, "<:raw", $f or die "$f: $!"; my $raw = <$j>; close $j;
      my $lead = decode_json($raw)->{lead} // die "web:build: $f has no lead\n";
      utf8::encode($lead);
      my ($marker) = sort { length($b) <=> length($a) } ($lead =~ /([A-Za-z0-9 ,.-]{20,})/g);
      die "web:build: $f: the lead has no plain run of 20 characters for the scan\n" unless defined $marker;
      $markers++;
      my ($inside, $elsewhere) = (0, 0);
      for my $file (keys %js) { my $has = index($js{$file}, $marker) >= 0; if ($file eq "dist/$entry") { $inside += $has } else { $elsewhere += $has } }
      die "web:build: control failed: the marker of $f does not occur in any lazy chunk (\"$marker\")\n" unless $elsewhere;
      if ($inside) { print STDERR "web:build: the entry chunk contains the text of $f (\"$marker\")\n"; $leaks++ }
    }
  }
  die "web:build: $leaks topic catalog(s) leaked into the entry chunk\n" if $leaks;
  # every topic of ABOUT_TOPICS (but the glossary) has a catalog with a marker in both languages, so that a new topic cannot slip past the scan
  open my $r, "<", "src/about/routes.ts" or die "src/about/routes.ts: $!"; my $routes = do { local $/; <$r> }; close $r;
  my ($list) = $routes =~ /ABOUT_TOPICS = \[([^\]]*)\]/ or die "web:build: no ABOUT_TOPICS in routes.ts\n";
  my @topics = grep { $_ ne "glossary" } $list =~ /\x27([a-z]+)\x27/g;
  die "web:build: ", scalar(@topics), " topics but $markers markers (expected two per topic)\n" unless $markers == 2 * @topics;
  print STDERR "web:build: info texts stay in the lazy chunks ($markers markers, none in dist/$entry)\n";
'
# Check 5: the installable app. index.html links the manifest and the apple-touch-icon under the base path, and dist/sw.js
# (written by web/tooling/pwa-plugin.ts) names index.html, the manifest, the default engine and the table in its precache.
perl -e '
  my $base = shift;
  open my $h, "<", "dist/index.html" or die "dist/index.html: $!"; local $/; my $html = <$h>; close $h;
  $html =~ m{<link rel="manifest" href="\Q${base}\Emanifest\.webmanifest"} or die "web:build: index.html does not link ${base}manifest.webmanifest\n";
  $html =~ m{<link rel="apple-touch-icon" href="\Q${base}\Eicons/apple-touch-icon\.png"} or die "web:build: index.html has no apple-touch-icon under $base\n";
  open my $s, "<", "dist/sw.js" or die "web:build: dist/sw.js is missing\n"; my $sw = <$s>; close $s;
  for my $want ("index.html", "manifest.webmanifest", "engines/java/engine-java.wasm", "tables/rows2.bin.gz") {
    die "web:build: dist/sw.js does not name $want\n" unless $sw =~ m{["\x60\x27]\Q$want\E["\x60\x27]}; # the minifier may quote with backticks
  }
  -f "dist/$_" or die "web:build: dist/$_ is missing\n" for qw(manifest.webmanifest icons/icon-192.png icons/icon-512.png icons/icon-maskable-512.png icons/apple-touch-icon.png);
  print STDERR "web:build: installable app ok (manifest linked under $base, sw.js precaches shell, engine and table)\n";
' $BASE_PATH
print -u2 "web:build: dist/ is ready (base $BASE_PATH)"
