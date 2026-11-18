#!/bin/zsh
# Installs the npm dependencies of web/ from the lock file. Scripts of packages do not run (--ignore-scripts).
# Exit 0 = installed or already up to date, 1 = npm failed.
set -eu
root=${0:A:h:h:h}
cd $root/web
if [[ -d node_modules/.bin && -f node_modules/.package-lock.json && node_modules/.package-lock.json -nt package-lock.json ]]; then
  print -u2 "web:install: node_modules is newer than package-lock.json, nothing to do (SKIP)"
  exit 0
fi
npm ci --ignore-scripts --no-audit --no-fund
print -u2 "web:install: installed $(ls node_modules | wc -l | tr -d ' ') top-level entries from package-lock.json"
