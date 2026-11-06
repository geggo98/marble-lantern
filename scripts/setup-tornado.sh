#!/bin/zsh
# usage: setup-tornado.sh [metal|opencl|ptx]   (default: $TORNADO_BACKEND, else metal on macOS / ptx on Linux)
# Downloads the pinned TornadoVM SDK into tornado-sdk/ and verifies its SHA-256.
# Safe to re-run: a verified, complete install is a SKIP (exit 0); a broken download never gets the final name.
set -eu
setopt pipefail

VERSION=7.3.1
JDK=jdk22plus          # archive family for JDK 22+ (we run JDK 25)
BASE="https://github.com/beehive-lab/TornadoVM/releases/download/v$VERSION"

# SHA-256 of the .tar.gz assets, from the GitHub release metadata of v7.3.1
typeset -A SHA
SHA[mac-aarch64-metal]=580208060f93270d20c7481a3ed58c88671d2cbc09a7e4e9f64b39e922924640
SHA[mac-aarch64-opencl]=d27cd9067a656aa5221a9d4153940bdc0623e0651837838cc5d000384cd0de75
SHA[linux-amd64-cuda]=2640f7fb6dc413e59cd493a4e0c9108e7b1d66252a2860b744eed091405a40da
SHA[linux-amd64-opencl]=ee9be026e535ea3563af02e87fec0989c0602fa7cd2489afead0ee7894a07b81

root=${DEVENV_ROOT:-$PWD}
sdk=$root/tornado-sdk

case "$(uname -s)-$(uname -m)" in
  Darwin-arm64)             plat=mac-aarch64;  default=metal ;;
  Linux-x86_64)             plat=linux-amd64;  default=ptx ;;
  *) print -u2 "unsupported platform: $(uname -s)-$(uname -m)"; exit 1 ;;
esac

backend=${1:-${TORNADO_BACKEND:-$default}}
case $backend in
  ptx) flavour=cuda ;;                       # the PTX backend ships in the "cuda" archive
  metal|opencl) flavour=$backend ;;
  *) print -u2 "unknown backend '$backend' (metal|opencl|ptx)"; exit 1 ;;
esac

key=$plat-$flavour
expected=${SHA[$key]:-}
[[ -n $expected ]] || { print -u2 "no pinned archive for $key"; exit 1 }

name=tornadovm-$VERSION-$JDK-$flavour-$plat
dest=$sdk/$name

# SKIP: marker is written only after a verified, complete extraction
if [[ -f $dest/.verified ]]; then
  print -u2 "skip: $name already installed ($dest)"
  exit 0
fi

mkdir -p "$sdk"
rm -rf "$sdk"/.tmp.*(N)                        # CLEANUP: leftovers of killed runs
work=$(mktemp -d "$sdk/.tmp.XXXXXX")           # same filesystem, so the final mv is atomic
trap 'rm -rf "$work"' EXIT

print -u2 "download: $BASE/$name.tar.gz"
curl -fL --retry 3 --retry-delay 2 -sS -o "$work/archive.tar.gz" "$BASE/$name.tar.gz"

actual=$(shasum -a 256 "$work/archive.tar.gz" | perl -ane 'print $F[0]')
if [[ $actual != $expected ]]; then
  print -u2 "SHA-256 mismatch for $name"
  print -u2 "  expected $expected"
  print -u2 "  actual   $actual"
  exit 1
fi

mkdir "$work/x"
tar -xzf "$work/archive.tar.gz" -C "$work/x"
top=("$work"/x/*(/N))
(( ${#top} == 1 )) || { print -u2 "unexpected archive layout: ${#top} top-level entries"; exit 1 }
[[ -x ${top[1]}/bin/tornado ]] || { print -u2 "no bin/tornado in ${top[1]:t}"; exit 1 }

: > "${top[1]}/.verified"
rm -rf "$dest"
mv "${top[1]}" "$dest"
ln -sfn "$name" "$sdk/current"
print -u2 "installed: $dest (sha256 ok)"
