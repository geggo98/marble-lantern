# C++ port and comparison (`cpp/`)

Plan: `docs/cpp-comparison-plan.md`. This directory holds the C++23 port of the classical player and of the network forward
pass. The build skeleton (every library, app and test target, the presets and the rule scripts) comes from work package WP0;
later packages fill the files and add no target.

## Commands

Inside `devenv shell`, in `cpp/` (CMake, Ninja and clang-tidy come from `devenv.nix`; the compiler stays Apple clang):

```sh
cmake --preset debug-asan && cmake --build --preset debug-asan && ctest --preset debug-asan
cmake --preset release-hardened && cmake --build --preset release-hardened
cmake --preset tidy && cmake --build --preset tidy       # clang-tidy over every file (warnings, no gate)
scripts/check-rules.sh                                   # rules that no compiler flag enforces (--self-test proves it)
scripts/scan-sensitive.pl $(git diff --cached --name-only)   # before every commit (--self-test proves it)
scripts/ncloc.pl <files>                                 # NCLOC counter for Java and C++
scripts/build-gcc12-stub.sh                              # Linux-compatible part with GCC (see spike 2)
```

## Local data of a worktree

A worktree has no git-ignored files. Environment variables, no absolute path in the repository:

```sh
export STERNHALMA_DATA_DIR=<data directory of the main checkout>     # real LARGE weights for --real
export STERNHALMA_TABLE_DIR=<directory with rows1.bin and rows2.bin>  # default: <repo>/tablebase/tables/
export STERNHALMA_GOLDEN_DIR=<golden directory>                      # default: <repo>/cpp/golden
export STERNHALMA_GOLDEN_REQUIRED=1                                  # a missing golden file fails the test instead of skipping it
```

`git lfs pull` in each worktree, then check `shasum -a 256 tablebase/tables/rows1.bin tablebase/tables/rows2.bin` against the
constants of WP3 (`85850d6f...` and `d62edf38...`).

## Spike 1: `-Wunsafe-buffer-usage` (2026-11-15, Apple clang 21.0.0)

Flags: `-std=c++23 -Wall -Wextra -Wpedantic -Wshadow -Wconversion -Wno-sign-conversion -Wold-style-cast -Wunsafe-buffer-usage
-Wunsafe-buffer-usage-in-libc-call -Werror`, one scratch file per case.

| Case | Result |
|---|---|
| `std::copy_n` from a `std::span<const std::byte>` into a `std::array`, then `std::bit_cast` (u32, f64) | accepted |
| `std::copy_n` and `std::copy` between spans and sub-spans | accepted |
| `std::memcpy` from `span.data()` to an object or a span | **flagged** (`-Wunsafe-buffer-usage-in-libc-call`) |
| `std::fread` into `vector.data()` | **flagged** (same flag); `std::ifstream::read` is accepted |
| `p[1]` on a raw pointer, `span.data()[1]` | **flagged** |
| `argv[1][0]`, `argv[i]` with a variable index | **flagged** (`argv[0]` with a constant zero is accepted) |
| `std::span<char*>(argv, argc)`, `std::span<const int>(p, n)` | **flagged** (two-parameter construction) |
| `std::string_view(argv[0])` | accepted |
| `CC_SHA256_Update`, `CC_SHA256` with `span.data()` and `span.size()` | accepted |
| `std::vector::operator[]` | accepted |
| `#pragma clang unsafe_buffer_usage begin/end` around `memcpy` and around the argv conversion | accepted (the region is exempt) |

Consequences, applied in this package:

- `le_bytes.hpp` uses `std::copy_n` plus `std::bit_cast`; it needs no unsafe zone and `memcpy` is not allowed there.
- The SHA-256 file (now `src/core/sha256.cpp`, see deviations) needs **no** unsafe zone: CommonCrypto is called with `span.data()`
  and `span.size()` and passes. The "SHA-256" row of the plan's unsafe-zone table therefore drops out.
- `apps/args.hpp` is the one marked region for `argv` (as the plan expected).
- The unsafe-zone files known to `check-rules.sh` are: `args.hpp`, `simd_net.cpp`, `metal_net.mm`, `kernels.metal`,
  `cuda_net.cu`, `kernels.cuh`. `memcpy` and the pragma are rejected everywhere else.
- A planted `int* p; p[1]` in `src/core/json_min.cpp` failed the build with `error: unsafe buffer access
  [-Werror,-Wunsafe-buffer-usage]` and was removed again.

## Spike 2: GCC 12 on the Mac (2026-11-15)

- `nix shell github:NixOS/nixpkgs/nixos-25.05#gcc12` fetches GCC 12.4.0 from the binary cache, but **it does not run on this macOS**
  (Darwin 25.6): every start of `g++` prints `dyld: duplicate LC_RPATH '.../libSystem-B/lib'` and exits without output.
- Fallback of the plan: the newest working GCC close to 12. `gcc13` (13.5.0) from the current nixpkgs runs; the revision is pinned
  in `scripts/build-gcc12-stub.sh`. GCC 14 and 16 also run, but their libstdc++ is further from the 12.3 of the Ubuntu image.
- GCC 13 has `<format>` and the plan's `check-rules.sh` rule R6 therefore rejects `<print>`, `<format>`, `<mdspan>` and
  `<generator>` in the Linux file list (`src/core`, `src/nn`, `src/gpu/cuda`, `apps/nn_*`, the core and CUDA tests).
- `scripts/build-gcc12-stub.sh` compiles `src/core`, `src/nn`, the CUDA stub, the tests and the network apps with
  `g++ -std=c++23 -O2 -D_GLIBCXX_ASSERTIONS -ffp-contract=off` and the warning set of the plan, and runs the tests: all pass.
  `std::expected`, `std::span` and `std::bit_cast` work.
- What stays unproven until the AWS gate: that the code also compiles with 12.3 of Ubuntu. The gate on the instance costs about 0.02 USD.

## Deviations from the plan, made in WP0

- `sha256.{hpp,cpp}` live in `src/core/`, not in `src/tablebase/`: the golden reader in `src/core` needs SHA-256, and the core must not
  depend on the tablebase. Both the CommonCrypto path (macOS) and the portable path are always compiled and tested against each other.
- clang-tidy: the wrapper `clang-tidy` of nixpkgs `clang-tools` adds the Nix libc++ and fails on the Apple SDK (`mbstate_t` error);
  with `-isysroot` alone it still fails. The `tidy` preset therefore uses `clang-tidy-unwrapped` (same package) with
  `--extra-arg=-isysroot<sdk>`, which parses everything. Checks and exclusions are in `.clang-tidy`.
