#!/bin/bash
# nn_bench --kernels must never end with exit 0 and no measured row: on a host with a Metal or CUDA device it prints
# "#kernels" / "#kernels-cuda" rows and exits 0, on a host with neither it exits 2 and says so.
# usage: check-nn-bench-kernels.sh <path to nn_bench>      exit 0 ok, 1 violated, 77 no golden data (ctest: skipped)
set -u
bench=${1:?usage: check-nn-bench-kernels.sh <nn_bench>}
out=$(mktemp "${TMPDIR:-/tmp}/nn-bench-kernels.XXXXXX") || exit 1
trap 'rm -f "$out" "$out.err"' EXIT
rc=0
"$bench" --kernels --presets small --batches 1 > "$out" 2> "$out.err" || rc=$?
rows=$(perl -ne '$n++ if /^#kernels(?:-cuda)?\t/; END { print $n // 0 }' "$out")
if grep -q 'golden' "$out.err" && [ "$rc" = 2 ] && [ "$rows" = 0 ] && ! grep -q -- '--kernels needs' "$out.err"; then
  echo "skipped: no golden data: $(head -1 "$out.err")" >&2; exit 77
fi
echo "exit $rc, $rows measured row(s)" >&2
if [ "$rc" = 0 ] && [ "$rows" -gt 0 ]; then echo "ok: device present, rows printed"; exit 0; fi
if [ "$rc" = 2 ] && [ "$rows" = 0 ] && grep -q -- '--kernels needs' "$out.err"; then echo "ok: no device, exit 2 with a message"; exit 0; fi
echo "FAIL: exit $rc with $rows rows" >&2; exit 1
