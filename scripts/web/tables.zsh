#!/bin/zsh
# Packs rows2.bin into web/public/tables/ (rows2.bin.gz and rows2.manifest.json) with scripts/offline/pack-tables.py.
# SKIP when the packed files already verify against tablebase/tables/rows2.bin.
# Exit 0 = packed or already correct, 1 = failure (also: rows2.bin is an LFS pointer, not the table).
set -eu
root=${0:A:h:h:h}
src=$root/tablebase/tables/rows2.bin
out=$root/web/public/tables
pack=$root/scripts/offline/pack-tables.py

if [[ ! -f $src ]]; then print -u2 "web:tables: $src is missing"; exit 1; fi
if [[ $(head -c 24 $src) == "version https://git-lfs" ]]; then
  print -u2 "web:tables: $src is a Git LFS pointer; run 'git lfs pull --include=tablebase/tables/rows2.bin' first"
  exit 1
fi
if $pack --verify $src --out $out >/dev/null 2>&1; then
  print -u2 "web:tables: 1 skip (packed files in $out verify), 0 ok, 0 error, nothing to do"
  exit 0
fi
mkdir -p $out
rm -f $out/*.tmp.*(N)                      # CLEANUP: leftovers of an interrupted run
$pack $src --out $out
$pack --verify $src --out $out
print -u2 "web:tables: 1 ok, 0 skip, 0 error, nothing to do"
