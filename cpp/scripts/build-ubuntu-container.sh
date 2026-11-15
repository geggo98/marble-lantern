#!/bin/zsh
# usage: build-ubuntu-container.sh [--platform linux/arm64|linux/amd64] [--golden DIR] [--data DIR] [--log FILE]
#
# Builds and tests the Linux part of the C++ code in an Ubuntu 22.04 container with g++-12 (12.3, the compiler of the AWS
# instance), on OrbStack or Docker, for free: the gap that the gcc-13 build on the Mac leaves ("does it build with 12.3?").
# This does not replace the AWS run; it has no CUDA device and no nvcc (the CUDA host layer is built against the stub backend).
#
# The worktree is mounted read-only (/src), the golden files read-only (/golden), --data DIR (default STERNHALMA_DATA_DIR, the data
# directory with the real weights, local only) read-only as /data; the build is in the container (/work, /build).
# The container script is container-inner.sh. The default platform is the native one (arm64 on an Apple Silicon Mac); with
# --platform linux/amd64 (Rosetta) the x86-64 build runs, and the AVX-512 objects (SH_SIMD_LANES=16) are compiled with -mavx512f
# (compile only: they cannot run there).
# Exit codes: 0 everything built and every test passed; 1 a build or test failed (see the log); 2 usage; 3 no docker.
set -u
setopt pipefail

platform=; golden=; log=; data=
while (( $# )); do
  case $1 in
    --platform) platform=${2:?--platform needs a value}; shift 2 ;;
    --golden) golden=${2:?--golden needs a directory}; shift 2 ;;
    --data) data=${2:?--data needs a directory}; shift 2 ;;
    --log) log=${2:?--log needs a file}; shift 2 ;;
    *) print -u2 "usage: build-ubuntu-container.sh [--platform linux/arm64|linux/amd64] [--golden DIR] [--data DIR] [--log FILE]"; exit 2 ;;
  esac
done
command -v docker >/dev/null || { print -u2 "build-ubuntu-container.sh: no docker (OrbStack or Docker Desktop)"; exit 3 }

repo=${0:A:h:h:h}                      # the worktree: cpp/scripts/ -> cpp -> repository root
[[ -n $golden ]] || golden=${STERNHALMA_GOLDEN_DIR:-$repo/cpp/golden}
[[ -f $golden/MANIFEST.tsv ]] || { print -u2 "build-ubuntu-container.sh: no golden files in $golden (cpp/golden of a worktree where the Java exporters ran)"; exit 2 }
[[ -n $data ]] || data=${STERNHALMA_DATA_DIR:-}
[[ -n $log ]] || log=${TMPDIR:-/tmp}/ubuntu-container-build.log

args=(--rm -v $repo:/src:ro -v ${golden:A}:/golden:ro)
[[ -z $data ]] || args+=(-v ${data:A}:/data:ro -e STERNHALMA_DATA_DIR=/data)
[[ -z $platform ]] || args+=(--platform $platform)
print -u2 "container build: image ubuntu:22.04${platform:+, platform $platform}; log $log"
docker run $args ubuntu:22.04 bash /src/cpp/scripts/container-inner.sh 2>&1 | tee $log
rc=${pipestatus[1]}
print -u2 "container build: exit $rc (log $log)"
exit $rc
