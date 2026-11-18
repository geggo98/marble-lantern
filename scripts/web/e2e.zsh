#!/bin/zsh
# Browser tests of the offline site: Playwright against `vite preview` of web/dist (base path /marble-lantern/).
# Chromium and Firefox are gates: a failure there makes the script exit 1. WebKit is informative: it runs apart, its
# result is printed, and it never changes the exit code. (Playwright's WebKit is not Safari.)
# usage: e2e.zsh [playwright arguments, e.g. a test file or --grep ...]
# Needs `devenv tasks run web:build` first (the task web:e2e does that) and PLAYWRIGHT_BROWSERS_PATH from devenv.
# Exit 0 = both gate projects passed; 1 = a gate project failed; 2 = missing prerequisite.
set -u
root=${0:A:h:h:h}
cd $root/web
if [[ ! -d node_modules/.bin ]]; then
  print -u2 "web:e2e: web/node_modules is missing; run 'devenv tasks run web:install' first"; exit 2
fi
if [[ ! -f dist/index.html ]]; then
  print -u2 "web:e2e: web/dist is missing; run 'devenv tasks run web:build' first"; exit 2
fi
if [[ -z ${PLAYWRIGHT_BROWSERS_PATH:-} || ! -d $PLAYWRIGHT_BROWSERS_PATH ]]; then
  print -u2 "web:e2e: PLAYWRIGHT_BROWSERS_PATH is unset or not a directory; run inside 'devenv shell'"; exit 2
fi
export BASE_PATH=/marble-lantern/
mkdir -p test-results/screens

typeset -i gate=0 info=0
print -u2 "web:e2e: gate projects chromium, firefox"
npm exec --no -- playwright test --project=chromium --project=firefox "$@"
gate=$?

print -u2 "web:e2e: informative project webkit (does not change the exit code)"
npm exec --no -- playwright test --project=webkit "$@"
info=$?

print -u2 "web:e2e: gate exit $gate, webkit (informative) exit $info"
(( gate == 0 )) && exit 0
exit 1
