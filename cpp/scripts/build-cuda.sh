#!/bin/zsh
# Builds the CUDA backend and its tests on the instance with explicit command lines (no CMake): g++-12 for every .cpp,
# nvcc -ccbin g++-12 for cuda_net.cu. Stub created by WP0; WP12 writes the real script.
# Flags that the rules of docs/cpp-comparison-plan.md fix: -ffp-contract=off everywhere, no fast-math.
CXXFLAGS=(-std=c++23 -O2 -ffp-contract=off -D_GLIBCXX_ASSERTIONS -Wall -Wextra -Werror)
print -u2 "build-cuda.sh: not implemented yet (WP12)"
exit 64
