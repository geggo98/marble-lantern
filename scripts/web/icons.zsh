#!/bin/zsh
# Renders the icons of the installable app from web/public/logo.svg into web/public/icons/ (committed; rerun after a logo change).
# Needs rsvg-convert (librsvg, in devenv.nix). Run inside `devenv shell`.
#   icon-192.png, icon-512.png   transparent background, the whole logo (manifest purpose "any")
#   icon-maskable-512.png        dark page colour behind the logo at 80 % (purpose "maskable": the launcher crops to its own shape;
#                                the logo's content reaches about 35 % of its width from the centre, so at 80 % it stays inside
#                                the 40 % safe circle of the maskable-icon spec)
#   apple-touch-icon.png (180)   the same as the maskable icon: iOS fills transparent pixels with black and rounds the corners itself
# Exit 0 = all icons written and their sizes checked.
set -eu
root=${0:A:h:h:h}
src=$root/web/public/logo.svg
out=$root/web/public/icons
bg='#14110e' # --ml-color-bg of the dark theme, web/design/tokens.css
command -v rsvg-convert >/dev/null || { print -u2 "icons: rsvg-convert is missing; run inside 'devenv shell'"; exit 2 }
mkdir -p $out
tmp=$(mktemp -d "${TMPDIR:-/tmp}/ml-icons.XXXXXX")
trap 'rm -rf $tmp' EXIT

# wrap <size> <fraction of the size for the logo> <background or "none"> <output>
wrap() {
  local size=$1 frac=$2 fill=$3 dest=$4
  perl -CSDA -e '
    my ($src, $size, $frac, $fill) = @ARGV;
    open my $h, "<", $src or die "$src: $!"; local $/; my $svg = <$h>;
    $svg =~ s/<\?xml[^>]*\?>\s*//;
    my $w = $size * $frac; my $off = ($size - $w) / 2;
    # the root <svg> of the logo becomes a nested <svg> placed in the middle
    $svg =~ s/<svg\b([^>]*?)\swidth="[^"]*"\s+height="[^"]*"/<svg$1 x="$off" y="$off" width="$w" height="$w"/ or die "logo.svg: no width/height on the root element\n";
    my $rect = $fill eq "none" ? "" : qq{<rect width="$size" height="$size" fill="$fill"/>};
    print qq{<svg xmlns="http://www.w3.org/2000/svg" width="$size" height="$size" viewBox="0 0 $size $size">$rect$svg</svg>\n};
  ' $src $size $frac $fill > $tmp/icon.svg
  rsvg-convert --width $size --height $size --output $dest.tmp $tmp/icon.svg
  mv $dest.tmp $dest
}

wrap 192 1 none $out/icon-192.png
wrap 512 1 none $out/icon-512.png
wrap 512 0.8 $bg $out/icon-maskable-512.png
wrap 180 0.8 $bg $out/apple-touch-icon.png

# Check: every PNG has the size it claims (IHDR width and height, big-endian at bytes 16..23).
perl -e '
  my %want = ("icon-192.png" => 192, "icon-512.png" => 512, "icon-maskable-512.png" => 512, "apple-touch-icon.png" => 180);
  my $dir = shift; my $n = 0;
  for my $f (sort keys %want) {
    open my $h, "<:raw", "$dir/$f" or die "icons: $f: $!\n"; read $h, my $head, 24;
    die "icons: $f is not a PNG\n" unless substr($head, 0, 8) eq "\x89PNG\r\n\x1a\n";
    my ($w, $hgt) = unpack "NN", substr($head, 16, 8);
    die "icons: $f is ${w}x$hgt, expected $want{$f}\n" unless $w == $want{$f} && $hgt == $want{$f};
    print STDERR "icons: $f ${w}x$hgt ", -s "$dir/$f", " bytes\n"; $n++;
  }
  die "icons: checked no file\n" unless $n == 4;
' $out
