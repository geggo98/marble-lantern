#!/bin/zsh
# Typecheck and unit tests of web/ (tsc --noEmit, then Vitest). Exit 0 = both passed.
set -eu
root=${0:A:h:h:h}
cd $root/web
if [[ ! -d node_modules/.bin ]]; then
  print -u2 "web:test: web/node_modules is missing; run 'devenv tasks run web:install' first"
  exit 1
fi
$root/scripts/web/export-board.zsh --check
npm run --silent typecheck
# The Hilla app (app/) type-checks web/src with its own tsconfig, which has fewer type declarations (for example no WebGPU globals):
# a name that web/tsconfig knows but app/tsconfig does not broke the Java job of CI once (GPUBufferUsage, 2026-11-19).
npx tsc -p $root/app/tsconfig.json --noEmit
npm run --silent test
