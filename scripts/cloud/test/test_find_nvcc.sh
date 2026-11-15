#!/usr/bin/env bash
# bootstrap.sh find_nvcc: the nvcc of the newest toolkit directory; none gives nothing.
set -u
# shellcheck disable=SC1091
. "$(dirname "$0")/lib.sh"
T=$(mktemp -d "${TMPDIR:-/tmp}/cloudtest.XXXXXX"); trap 'rm -rf "$T"' EXIT
# shellcheck disable=SC1091
. "$SRC_CLOUD/bootstrap.sh"
fake() { mkdir -p "$T/$1/$2/bin"; printf '#!/bin/sh\n' > "$T/$1/$2/bin/nvcc"; chmod +x "$T/$1/$2/bin/nvcc"; }

mkdir -p "$T/empty"
assert_eq "$(find_nvcc "$T/empty")" "" "no toolkit: empty"
fake a cuda-12.4
assert_eq "$(find_nvcc "$T/a")" "$T/a/cuda-12.4/bin/nvcc" "one toolkit"
fake a cuda-13.0
assert_eq "$(find_nvcc "$T/a")" "$T/a/cuda-13.0/bin/nvcc" "cuda-12.4 and cuda-13.0: the newest"
fake a cuda-9.2
fake a cuda-12.10
assert_eq "$(find_nvcc "$T/a")" "$T/a/cuda-13.0/bin/nvcc" "9.2 and 12.10 are older than 13.0 (numeric, not text, order)"
fake b cuda-12.9; fake b cuda-12.10
assert_eq "$(find_nvcc "$T/b")" "$T/b/cuda-12.10/bin/nvcc" "12.10 is newer than 12.9"
fake c cuda
assert_eq "$(find_nvcc "$T/c")" "$T/c/cuda/bin/nvcc" "only the cuda link: that one"
mkdir -p "$T/d/cuda-13.0/bin"   # a directory without an executable nvcc
assert_eq "$(find_nvcc "$T/d")" "" "a toolkit directory without nvcc: empty"
finish_test
