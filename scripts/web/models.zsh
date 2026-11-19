#!/bin/zsh
# Checks the packed net models in web/models (manifest, sizes, sha256 of the packed and the inflated file) with
# scripts/models/pack-model.py verify, then copies them to web/public/models/ (git-ignored, like web/public/tables).
# Exit 0 = verified and copied (or already in place), 1 = a check failed.
set -eu
root=${0:A:h:h:h}
src=$root/web/models
out=$root/web/public/models
$root/scripts/models/pack-model.py verify --out $src
mkdir -p $out
rm -f $out/*.tmp.*(N)                       # CLEANUP: leftovers of an interrupted run
typeset -i ok=0 skip=0
for f in $src/models.json $src/*.safetensors.gz; do
  if [[ -f $out/${f:t} ]] && cmp -s $f $out/${f:t}; then skip+=1; continue; fi
  cp $f $out/${f:t}.tmp.$$ && mv $out/${f:t}.tmp.$$ $out/${f:t}
  ok+=1
done
for f in $out/*.safetensors.gz(N); do        # a copy of a model that was removed from web/models
  [[ -f $src/${f:t} ]] || { rm -f $f; print -u2 "web:models: removed stale ${f:t}"; }
done
$root/scripts/models/pack-model.py verify --out $out   # the copy is what vite serves: check it again
print -u2 "web:models: $ok ok, $skip skip, 0 error, nothing to do"
