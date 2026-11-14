#!/usr/bin/env bash
# Runs every test_*.sh of this directory; needs no AWS account and no network (external commands are stubs).
# usage: scripts/cloud/test/run-all.sh      exit 0 if all pass
set -u
here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
failed=()
for t in "$here"/test_*.sh; do
  echo "== $(basename "$t")"
  bash "$t" || failed+=("$(basename "$t")")
done
if [ ${#failed[@]} -eq 0 ]; then echo "ALL PASSED"; else echo "FAILED: ${failed[*]}" >&2; exit 1; fi
