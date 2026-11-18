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
npm run --silent test
