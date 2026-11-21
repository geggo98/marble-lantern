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
export STERNHALMA_TABLE_DIR=<directory with rows1.bin and rows2.bin>  # default: <repo>/tablebase/build/tables/ (rows1.bin), <repo>/tablebase/tables/ (rows2.bin)
export STERNHALMA_GOLDEN_DIR=<golden directory>                      # default: <repo>/cpp/golden
export STERNHALMA_GOLDEN_REQUIRED=1                                  # a missing golden file fails the test instead of skipping it
```

`git lfs pull` in each worktree, then `devenv tasks run tables:unpack` (unpacks `tablebase/tables/rows1.bin.zst` into `tablebase/build/tables/rows1.bin`), then check `shasum -a 256 tablebase/build/tables/rows1.bin tablebase/tables/rows2.bin` against the
constants of WP3 (`85850d6f...` and `d62edf38...`).

## Merging the tracks into `exp/cpp` (WP14)

The worktree of branch `exp/cpp`. The tracks touch disjoint files (checked: no file in the change list of both
branches since the common base), so the merges give no conflict unless `exp/cpp` itself changed a file that a track also changes.
`exp/cpp` changed these shared files in WP14: `cpp/src/core/golden.cpp`, `cpp/src/nn/golden_io.cpp`, `cpp/src/nn/simd_net.cpp`,
`cpp/tests/nn_test.cpp`, `cpp/scripts/build-cuda.sh` (GCC 12.3 warnings and the `SH_EXTRA_CXXFLAGS` hook, see "Ubuntu 22.04
container build") and `cpp/expected-golden-sha256.tsv` (new). If WP13 on `exp/cpp-nn` touches the same lines, resolve in favour
of the version that builds with g++ 12.3 (the container script proves it).

| Order | Merge | State |
|---|---|---|
| 1 | `exp/cpp-nn` (WP8 to WP12, branch tip `c0eeea9`), merge commit `48646af` | done in WP14 |
| 2 | `exp/cpp-classical` (WP1 to WP5 and the `search_bench` code of WP7, branch tip `5bc9dda`), merge commit `3551a0d` | done in WP14 |
| 3 | `exp/cpp-classical` again, after WP7 (measurement) and WP6 (race play, games row; done, see "Race play and the games row") | done: `exp/cpp-classical` merge commit `f12c5a0`, `exp/cpp-nn` (WP13) merge commit `8d40027`, both before WP15 |

Repeat a merge (nothing is lost by a merge commit; `--no-ff` keeps one commit per track and merge):

```sh
cd <worktree of exp/cpp>                               # clean
git merge --no-ff exp/cpp-classical -m "Merge branch 'exp/cpp-classical' into exp/cpp"
git merge --no-ff exp/cpp-nn        -m "Merge branch 'exp/cpp-nn' into exp/cpp"        # when WP13 is committed there
cd cpp && export STERNHALMA_GOLDEN_DIR=<a golden directory with every export> STERNHALMA_DATA_DIR=<data dir> STERNHALMA_TABLE_DIR=<table dir>
cmake --preset debug-asan && cmake --build --preset debug-asan && ctest --preset debug-asan       # gate: all tests pass
scripts/check-rules.sh && scripts/build-ubuntu-container.sh --platform linux/amd64                 # rules and g++ 12.3
../scripts/cloud/test/run-all.sh                                                                   # stub tests of the AWS scripts
```

Check before the merge that the golden files exist for both tracks: the nn track exports `nn-*` files and `MANIFEST.tsv`, the
classical track exports the engine and search files; the exporters keep each other's `MANIFEST.tsv` lines. The merged
`ctest` of 2026-11-15 (10 of 10 tests, `debug-asan`) used the golden directory of the nn worktree, which holds every file.

## AWS scripts of WP14 (`scripts/cloud/`)

All of it is tested with stubs (`scripts/cloud/test/run-all.sh`, 15 test files, no account, no network). State at the time of writing (WP14): nothing had run against AWS yet. The suite ran on 2026-11-15, all 19 steps OK; see "AWS run on the Tesla T4, booked (WP15)" and `results/aws-t4-2026-11-15.txt`.

- **`bootstrap.sh --suite default|cpp`.** `cpp` needs `--backend cuda`. Order: apt, source, GPU driver check, `cpp-toolchain` (g++-12, nvcc
  through `find_nvcc`, compute-sanitizer, nvidia-smi; writes `nvcc-version.txt`, `toolchain.txt`), `cpp-build` (`build-cuda.sh`),
  `cpp-cuda-test`, **gate**, `cpp-sanitizer` (SKIP if the tool is missing, FAIL if it reports errors, the run goes on), JDK,
  TornadoVM, `build`, `print-run-command`, `golden`, `golden-hash`, `cpp-check`, `anchors-start`, `cpp-bench`, `java-crossbench`,
  `anchors-end`, `gpufeed-small`, `gpufeed-large`. The gate stops the run after a failed toolchain, build or CUDA test: `summary.txt`
  gets `gate: STOP after the failure of <step>`, the machine shuts down after 2 minutes (the caller fetches at once), exit 1.
- **`golden-hash`** compares weights, inputs and cases (`must`) of the instance's export with `cpp/expected-golden-sha256.tsv`
  (generated from the Mac manifest) and records the reference outputs (`info`) in `golden-sha256.txt`. WP13 may replace the file
  (same columns: file, sha256, `must|info`).
- **WP13 dependency.** At the time of writing `nn_bench` was the skeleton of WP0 (exit 64); WP13 has landed (merge commit `8d40027`), so the
  steps `anchors-*` and `cpp-bench` run. The arguments are the ones of the plan (`--golden --presets --batches --impls --anchors --kernels --cold`); they are
  in `cpp_anchors` and `cpp_bench` of `bootstrap.sh` and are the only place to adjust if WP13 names an option differently. The Java
  rows run through the command line that `printRunCommand` wrote (`cpp/build/java-nn.cmd`).
- **`aws-spot.sh`:** `--suite` (validated, in the user data), `cost.tsv` (provisional line at the launch, final line at the end and
  in the exit trap; fields: suite, launched, seconds, price_usd_per_h, estimate_usd, worst_case_usd; `suite.txt` next to it),
  exit 4 for no capacity or no quota (`InsufficientInstanceCapacity`, `MaxSpotInstanceCountExceeded`, `VcpuLimitExceeded`,
  `SpotMaxPriceTooLow`; the cleanup still runs), AMI by name when the SSM parameter cannot be read (the id is printed, not stored),
  `--real-weights FILE`.
- **`spot-retry.py`** (uv header, `exclude-newer` set, `spot-retry.py.lock`; the tests run it with `python3 -I`, stdlib only):
  pre-flight per region (exit 3 drops the region), retries only on exit 4 and rotates the regions, cumulative cap from the `cost.tsv`
  files of the suite (a run directory of the suite without `cost.tsv` counts with the worst case), `--detach` (fork and setsid; the
  lock is inherited, so there is no race), `flock` lock with exit 75, `--status` (exit 75 while running, `not running` and 0 otherwise),
  SIGTERM forwarded to `aws-spot.sh` (cleanup runs), summary, exit codes 0, 1, 2, 3, 4, 5, 6 (no DONE before the deadline, not retried), 75, 130, 143.
  Deviations from the plan text: the output goes to the log file directly (dup2 after the fork) instead of through `+errlog`;
  `--on-demand-price region=USD,...` (required for every region with `--on-demand-after`; no default, the prices differ by region) is the price used for the budget check of on-demand attempts and is booked in `cost.tsv` as `<price>-assumed`; `--status` exits 75
  while a run holds the lock (the rule of the project's long-running scripts).
  The owner allowed on-demand, so the command of the plan can take `--on-demand-after K` (for example 4): attempts after K spot attempts
  use `--on-demand`, inside the same cumulative cap of 1 USD.

```sh
export AWS_PROFILE=... AWS_REGION=...        # from the environment, never in the repository
scripts/cloud/spot-retry.py --detach --regions us-east-1,eu-central-1 --attempts 6 --wait 300 --budget-usd 1.00 --suite cpp \
  [--on-demand-after 4 --on-demand-price us-east-1=<usd/h>,eu-central-1=<usd/h>] -- --instance-type g4dn.xlarge --max-price 0.35 --max-minutes 45 --backend cuda --feed-seconds 30 --yes \
  [--real-weights data/loop4/gen-0011/candidate.safetensors]
scripts/cloud/spot-retry.py --status
```

### Real LARGE weights on the instance (`--real-weights`)

The owner allowed the upload of `data/loop4/gen-0011/candidate.safetensors` (1.3 MB) to the short-lived instance. Without the switch
only the generated u5 weights are used. With it:

1. `aws-spot.sh` checks the file (a regular file, name ends in `.safetensors`, 1 byte to 16 MiB), computes its SHA-256 and puts it
   in the user data (`--real-weights-sha256`). The file name and path are not written anywhere.
2. It uploads the file next to the tarball, runs `sha256sum` on the instance and stops the run (cleanup as usual) if the hash
   differs. `cloud-results/<run id>/real-weights.sha256` holds both hashes.
3. `bootstrap.sh` checks it again (`real-weights` step), copies it to `<workdir>/real-data/loop4/gen-0011/`, runs
   `:nn:goldenExport -Psternhalma.golden.real=true` with `STERNHALMA_DATA_DIR` set to that directory (the Java reference outputs for the
   real weights come from the Java code on the instance), and `cpp-check` runs `nn_check` for `--preset gen11` with `cuda`, `simd` and
   `simd-nofma`. `results/real-weights.txt` records the hash before the upload, after the upload and after the run.
4. The weights stay on the instance (it is terminated at the end); the results directory has the hashes and the reference output
   file's hash, never the weights. The file never goes into the repository.

## Ubuntu 22.04 container build

`scripts/build-ubuntu-container.sh [--platform linux/arm64|linux/amd64]` builds and tests the Linux part in an `ubuntu:22.04`
container (OrbStack) with the same `g++-12` as the instance. The worktree, the golden files and the data directory (real weights, local
only) are mounted read-only; the build is in the container (`container-inner.sh` is the script that runs there). It is free and **does
not replace the AWS run**: there is no nvcc and no CUDA device, so `cuda_net.cu` and the real device kernels are not compiled or run.
It closes the gap "does the code build with g++ 12.3 under `-Werror`?".

Run on 2026-11-15 on the Apple M3 Max (OrbStack, Docker 29.8.2): the compiler is `g++-12 (Ubuntu 12.3.0-1ubuntu1~22.04.3) 12.3.0`, the
build is `build-cuda.sh --stub --werror --with-tests` (`-std=c++23 -O3 -ffp-contract=off`, warning set of the plan, `-Werror`,
`-D_GLIBCXX_ASSERTIONS` in the hardened build, two lane counts, hardened and unchecked).

| Check | arm64 (native) | amd64 (Rosetta) |
|---|---|---|
| every Linux source builds, hardened and unchecked, lanes 8 and 16 | ok | ok |
| `check-fma` on the object code | lanes 8: 62 fused / 0, lanes 16: 44 / 0 | lanes 8: 31 / 0, lanes 16: 22 / 0 |
| `java_compat_test`, `nn_test`, `cuda_adapter_test`, `cuda_test_emu`, `cuda_test` (stub) | 8, 31, 16, 4 (2 skipped) passed; stub says "no device" | the same counts |
| `nn_check` `reference` (bit-identical logits), `simd`, `simd-nofma` on SMALL and LARGE, 1,024 positions | within the bound (largest error as a fraction of the bound: SMALL 0.0131, LARGE 0.0433 for `simd`) | the same figures |
| `nn_check` on the unchecked build, `nn_check_l16`, `nn_bench_l8` starts | ok | ok |
| AVX-512 objects (`SH_SIMD_LANES=16`, `-mavx512f -mfma`), compile only | not applicable | accepted: 132 zmm instructions, 13 fused in `simd_net`, 0 in `simd_net_nofma` (after the change of the diagnosis below; before: 309 and 31) |
| assembler of the convolution loop (`simd-loop-report.pl --check`, lanes 8 and 16, with and without `-D_GLIBCXX_ASSERTIONS`) | not applicable | 18 instructions, 8 fma, 0 jumps to an assertion block, 0 stack operands |
| CMake and Ninja | not used: apt has CMake 3.22 and `CMakeLists.txt` needs 3.28; the instance builds with `build-cuda.sh` | same |

Not run: the AVX-512 binaries (Rosetta has no AVX-512), everything with a device, `compute-sanitizer`, nvcc, the Java side, the
engine and search code (not part of the Linux build by design; the plan keeps them Mac-only, and `fingerprint.cpp` and `search_bench.cpp` use `<format>`, which libstdc++ 12 lacks; no source includes `<print>`).

**What it found, and the changes made.** The first run failed with `-Werror` on four constructs that g++ 12.3 flags and the
g++ 13.5 of the Mac stub build and Apple clang do not (all false positives of GCC 12):
`std::string(std::istreambuf_iterator...)` in `src/core/golden.cpp` and `src/nn/golden_io.cpp` (`-Wnull-dereference`, now
`std::ostringstream << rdbuf()`); `std::copy_n` in `pad_rows` of `src/nn/simd_net.cpp` (`-Wstringop-overflow` with the bound
`(size_t)-4`, now an element loop over bounds-checked spans); `(i > 0 ? "," : "") + std::to_string(...)` in `tests/nn_test.cpp`
(`-Wrestrict`). The 16-lane objects on an x86-64 CPU without AVX-512 stop with `-Wpsabi` under `-Werror`; the container build sets
`SH_EXTRA_CXXFLAGS=-Wno-psabi` (new hook of `build-cuda.sh`). On the instance the build has no `-Werror` and an AVX-512 CPU, so none of
these would have stopped it, but they would have filled the build logs. After the changes the Mac `ctest --preset debug-asan` (10 of
10) and `check-rules.sh` are green again.

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
- Settled by the AWS run of 2026-11-15: the code compiles with g++ 12.3 of Ubuntu and nvcc 13.2 (`-ccbin g++-12`), see `results/aws-t4-2026-11-15.txt`, section Correctness.

## Deviations from the plan, made in WP0

- `sha256.{hpp,cpp}` live in `src/core/`, not in `src/tablebase/`: the golden reader in `src/core` needs SHA-256, and the core must not
  depend on the tablebase. Both the CommonCrypto path (macOS) and the portable path are always compiled and tested against each other.
- clang-tidy: the wrapper `clang-tidy` of nixpkgs `clang-tools` adds the Nix libc++ and fails on the Apple SDK (`mbstate_t` error);
  with `-isysroot` alone it still fails. The `tidy` preset therefore uses `clang-tidy-unwrapped` (same package) with
  `--extra-arg=-isysroot<sdk>`, which parses everything. Checks and exclusions are in `.clang-tidy`.

## Network track: golden files, Java benchmark, tolerance (WP8)

### Golden files of the network

`./gradlew --no-build-cache :nn:goldenExport` (add `-Psternhalma.golden.real=true` for the real LARGE weights, read from
`$STERNHALMA_DATA_DIR/loop4/gen-0011/candidate.safetensors`) writes `cpp/golden/` (git-ignored). All files are safetensors with
F32 tensors:

| File | Tensors |
|---|---|
| `nn-weights-small-u5`, `nn-weights-large-u5` | the u5 weights of SMALL and LARGE `classic1`, two players (`Parameters.uniform(config, 5, sqrt(6 / 7C))`) |
| `nn-inputs` | `inputs` [1024][122][13]: 1,024 evenly spaced positions of `Playouts.positions(2, 60, 160, 1)`, encoded with `classic1` |
| `nn-ref-small-u5`, `nn-ref-large-u5`, `nn-ref-large-gen11` (only with `real`) | `logits` [1024][242], `values` [1024][2] of the scalar `Network`; `vlogits`, `vvalues` of `VectorNetwork` (4 lanes, after three warm-up passes) |
| `nn-cases` | TINY `classic1` u5 network as `base.*`; `input` and `dirty_input` [1][122][13]; `base.logits`, `base.values`; NaN case: `nan.conv_in.weight` replaces `conv_in.weight`, expected `nan.logits`, `nan.values` (the values are NaN); saturation cases: `sat_a.head_value.dense2.bias` = {44.3, 100} and `sat_b.head_value.dense2.bias` = {-1000, +inf} replace `head_value.dense2.bias`, expected `sat_a.values` = {1, 1} and `sat_b.values` = {-1, 1} exactly; `dirty_input` has 0.5 in the padding row (plane PROGRESS) and every forward pass must refuse it |

`MANIFEST.tsv` has the columns `file`, `lines`, `sha256`, `commit` of `golden.hpp`. Binary files have the line count 0 (a C++ reader
for them checks the SHA-256 only). Lines that do not start with `nn-` are kept by the exporter, so that it can share the file with
the classical exporter. Comment lines `#<key><TAB><value>` hold measured figures:
`#max_err_frac.<impl>.<preset>` (`impl` is `vector` or `tornado`, `preset` is `small-u5`, `large-u5` or `large-gen11`) is the
largest error of the implementation against the scalar reference as a fraction of the bound 1e-4 + 1e-3 |ref|; if a figure
exceeds 1, `#extra_rel_of_max_logit.<impl>.<preset>` is the extra term (times the largest |logit| of the position, policy only)
that `nn_check` and `CrossBench` add to the bound. The `vector` figures come from `goldenExport`; run
`./gradlew --no-build-cache :nn-tornado:goldenTolerance` afterwards for the `tornado` figures (it rewrites only those lines).

Measured on 2026-11-15 (M3 Max, Metal), all far below 1.0, so no extra term:

| Preset | `VectorNetwork` | `TornadoNetwork` (Metal) |
|---|---|---|
| SMALL u5 | 0.0131 | 0.0196 |
| LARGE u5 | 0.0459 | 0.0499 |
| LARGE gen11 | 0.0078 | 0.0069 |

Two runs of `goldenExport` gave files with equal SHA-256 (also the references of `VectorNetwork`, which repeated bit for bit in
the process).

### Plane set classic1v and format version 3 (C++)

The C++ network code in `src/nn` knows `PlaneSet::Classic1V`: 15 input planes, residual priors on, value head width 3. Planes 0 to 12 match classic1, plane 13 is 1 under the jump rule LONG, and plane 14 is 1 in a three-player game. The file format is described in [docs/network.md](../docs/network.md), section "Format version 3 (variants)".

- `config_from_metadata` accepts format version 3 only with `planes=classic1v`, `players=3` and `input_planes=15`. It rejects classic1v in version 2 and version 3 with any other plane set, the same rules as the Java reader (docs/network.md, "File format rules"). Versions 1 and 2 load as before.
- C++ has no input encoder; it runs the forward pass on inputs that Java encodes. The reference net, the SIMD net and the CUDA and Metal backends read plane count and players from the config, so they needed no change.
- Plane 12 (eval) is 0 under LONG and with three players, so the value prior `k*eval` switches itself off there. The policy prior `beta*16*dist_own` stays active in all four variants.
- `sh::nn::absolute_values` (`src/nn/priors.hpp`) maps network outputs to absolute players: in an n-player position with mover m, player a gets output `(a-m) mod n`. A classic1v net serves n=2 and n=3 from the first n of its 3 outputs. The Java counterpart is `Evaluator`.

`./gradlew --no-build-cache :nn:goldenExport` also writes `nn-weights-tiny-v3`, `nn-weights-small-v3`, `nn-inputs-v3`, `nn-ref-tiny-v3` and `nn-ref-small-v3`. The inputs hold 64 positions, 16 each for two and three players with adjacent and LONG jumps, so every mover occurs; the tensors are `inputs`, `players`, `mover` and `long`. The refs hold `logits`, `values`, `vlogits`, `vvalues` and `absolute` (values from the Java `Evaluator`). The export appends rows to `expected-golden-sha256.tsv` (weights and inputs "must", refs "info") and leaves existing rows unchanged.

`tests/nn_test.cpp` checks that the reference net matches Java with bit-identical logits and values within 1 ulp, and that the SIMD net stays within `|fast-ref| <= 1e-4 + 1e-3*|ref|`. It also checks the structure of planes 12 to 14, `absolute_values` against the Java `Evaluator`, and the prior switch-off. Not covered: the CUDA backend on a real device (only the emulated tests run) and any Metal-specific v3 test beyond the generic code path.

Run `:nn:goldenExport` after `:search:goldenExport`, because the latter rewrites `golden/MANIFEST.tsv` without the `nn-` lines.

### The Java benchmark and its schedule

`./gradlew --no-build-cache :nn-tornado:printRunCommand` writes the full `java` command of `CrossBench` and of `GpuFeedBenchmark`
(`gpuFeed`) to `cpp/build/java-nn.cmd` and `cpp/build/java-gpufeed.cmd`: JVM flags `-Xms4g -Xmx4g`, the default GC (G1), the Vector
API module and the TornadoVM flags of the SDK; the program arguments are appended by the caller (run it from the repository root,
for example `eval "$(cat cpp/build/java-nn.cmd) --golden cpp/golden --presets small,large"`). `CrossBench` (task
`:nn-tornado:crossBench`) prints comment lines (`# ...`: JVM flags with paths masked, GC, Vector API lanes, `UseAVX` and
`MaxVectorSize` (`n/a` on arm), device, load before and after, GC collections per case), then a header line and one line per case
in the shared schema:

```text
lang impl device preset weights batch bucket lanes evals_per_s ms_per_call_median ms_p90 round_min round_max
copy_in_ms h2d_ms kernel_ms d2h_ms copy_out_ms priors_ms first_call_ms load_1min verified
```

The columns are tab-separated; `NA` means "not measured separately". `nn_bench` prints the same header line and columns.
Schedule of every case (the same in `nn_bench`):

- Warm-up: at least 2 s and 10 calls. A CPU case ends the warm-up at 2 s even with fewer calls (a call is then longer than 0.2 s).
- Then 3 rounds of at least 1 s and at least one call. `evals_per_s` is the median round of calls x batch / round time;
  `round_min` and `round_max` are the other two. `ms_per_call_median` and `ms_p90` are over the calls of all rounds.
- The input of call c is the golden positions `c*n .. c*n+n-1` modulo 1,024 (for batches that divide 1,024: slice `c mod (1024/n)`),
  prepared before the warm-up.
- A CPU case whose one call would take more than 3 s at the rate of the smaller batches is skipped (comment line `# skipped`).
- The last call of each case is checked against `nn-ref-*` (`logits`/`values`) with the bound above (plus the manifest's extra
  term); on a violation the program ends with exit code 2 and prints no line for that case.
- `first_call_ms` is the first call of the case: for TornadoVM it contains the plan build and the kernel compilation of the bucket.
- TornadoVM reports one "execute" figure (host to device, kernels, device to host). It goes into `kernel_ms`; `h2d_ms` and
  `d2h_ms` are `NA`. `copy_in_ms`, `copy_out_ms` and `priors_ms` are the per-execution deltas of `TornadoNetwork.timings()` over
  the measured rounds. The CPU paths report `NA` for all phases.
- `--anchors` runs only SMALL and LARGE at batch 256; `--cold` runs one forward pass and prints `# cold: construct .. ms, first
  call .. ms, JVM uptime .. ms`; after the main table a profiled pass (`TornadoNetwork.profiled`, 20 calls per batch) prints
  `#profiled` lines with the median wall time, kernel time, device write time and device read time of the TornadoVM profiler
  (reported separately, because the profiler slows the call; `NA` where the backend reports 0).

One full run on 2026-11-15 (SMALL and LARGE, all impls, six batches) printed 32 lines, all `verified = ok`; the scalar cases at
batch 256 and above (LARGE) and 1024 (SMALL) were skipped by the 3 s rule. This was a functional run on a machine with other work
running (load average above 3), not a measurement.

### TornadoVM math flags (WP8 step 6)

Determined by reading the bytecode of the 7.3.1 backend jars (`javap`), not by a run. The CUDA archive for Linux
(`tornadovm-7.3.1-jdk22plus-cuda-linux-amd64.tar.gz`, SHA-256 equal to the pinned value in `scripts/setup-tornado.sh`) was
unpacked in a scratch directory only for this reading; the Metal jar is the installed one.

| Backend | What the code does | Consequence |
|---|---|---|
| CUDA (NVRTC) | `CUDACompiler` passes `--gpu-architecture=sm_NN` (falls back to `compute_NN` if the device architecture is newer than the NVRTC knows), `--include-path=<dir>` for `cuda_fp16.h` if needed, and `-rdc=true` only for kernels that launch kernels. The compiler flags of a task come from `TornadoOptions.DEFAULT_CUDA_COMPILER_FLAGS` = profile flags + `-Dtornado.cuda.compiler.flags` (default empty) + the FMA flag. The profile (`-Dtornado.cuda.compile.profile`) is `default` (no flags), `fast` (`--use_fast_math --extra-device-vectorization`), `debug` (`-lineinfo`) or `repro` (`--fmad=false`); the FMA flag is `--fmad=false` only if `-Dtornado.enable.fma=False` (default `True`) and no `-fmad` option is given | with the defaults the NVRTC options are the architecture only: **no `--use_fast_math`, and `--fmad` is not passed, so NVRTC's own default (`--fmad=true`: contraction of a*b+c into FMA) applies**. No string `fmad` or `use_fast_math` appears anywhere else in the CUDA driver or the driver-common jar |
| Metal | `MetalContext.createProgramWithSource` passes `fastMathEnabled = TornadoOptions.METAL_FAST_MATH` (`-Dtornado.metal.fastmath`, default `False`) to the Metal compiler; `tornado.metal.compiler.flags` defaults to empty. The code does not set the Metal contraction mode | fast math is off by default (as `docs/tornado.md`, pitfall 8, measured); FMA contraction is left to the Metal compiler's default |

Not confirmed by a run: the printed NVRTC option string. The CUDA part of the AWS run can confirm it with
`-Dtornado.printKernel=True` if the backend logs its options; the document states "read from bytecode" until then.

## CPU SIMD forward pass (WP10)

`src/nn/simd_net.*` ports `VectorNetwork`: vector-extension types (`float __attribute__((vector_size(4 * L)))`), L = `SH_SIMD_LANES`
(NEON 4, AVX2 8, AVX-512 16, overridable with `-DSH_SIMD_LANES=N`), weights repacked into tiles of 2L output channels, 4 cells and 8
accumulators per step, channels padded with zero weights, no allocation in `forward`. Since the x86 diagnosis below the file is an unsafe zone
(`check-rules.sh`): setup and the size checks use `std::span`, the hot loops use raw pointers into ranges checked once per call.

- `SimdVariant::Fma` is `simd_net.cpp`, the only file built with `-ffp-contract=fast`; `SimdVariant::NoFma` is `simd_net_nofma.cpp`, which
  includes the same source with the default `-ffp-contract=off` into the namespace `simd_nofma`.
- `scripts/check-fma.sh <simd_net.o> <simd_net_nofma.o>` proves it in the object code (ctest `simd_object_code`, also run by
  `build-gcc12-stub.sh`). Measured with Apple clang 21 and GCC 13.5 on arm64: fused instructions in the first object (11 at `-O1`, 26 at
  `-O3`), none in the second.
- `nn_check --impl simd|simd-nofma [--batch N]` compares with the Java scalar reference under the bound; `nn_test` also compares with the
  Java `VectorNetwork` outputs. The no-allocation test replaces the global `operator new` in `nn_test` only (`tests/alloc_counter.hpp`, the
  one exception of rule R1 in `check-rules.sh`).

## x86 code generation of the SIMD path (diagnosis 2026-11-15)

**Finding.** The AWS run (Cascade Lake, g++ 12.3, `-O3 -march=native`) contradicted the Mac run: `simd` with 16 lanes was slower than with 8
(batch 256, SMALL: 1,154 against 1,939 evaluations/s), the hardened build was 2.3 times slower than the unchecked one (1,939 against 4,614), and
even the unchecked build was slower than Java's Vector API (5,866). On the Mac (clang, `libc++` hardening FAST) the checks cost about 1 %. That
was a quality problem of this port's code on GCC 12, not a statement about C++ against Java. Offline diagnosis: `g++-12 -S` in the
`ubuntu:22.04` container (amd64 under Rosetta, which cannot run AVX but can compile for it), `-O3 -ffp-contract=fast -march=cascadelake`
(for 16 lanes also `-mprefer-vector-width=512`; without it the result is the same), `-DSH_SIMD_LANES=8|16`, with and without
`-D_GLIBCXX_ASSERTIONS`. `scripts/simd-loop-report.pl` finds the innermost loop around each group of 8 `vfmadd231ps` and counts.

**Cause, from the assembler.** The channel loop is vectorized by hand (vector extensions): `vfmadd231ps` on ymm (8 lanes) and zmm (16 lanes) was
there in every variant; the auto-vectorizer does not touch the loop (`-fopt-info-vec-all`: "couldn't vectorize loop"), and does not have to.
What was wrong around it (the old code, per iteration of the inner loop, 8 fma and 4 broadcasts in every case):

| variant before the change | instructions | conditional jumps (to an assertion block) | stack operands (vector) |
|---|---|---|---|
| 8 lanes, unchecked | 20 | 1 (0) | 0 (0) |
| 8 lanes, `-D_GLIBCXX_ASSERTIONS` | 35 to 39 | 7 (5 to 6) | 6 to 8 (0) |
| 16 lanes, unchecked | 28 | 1 (0) | 2 (2) |
| 16 lanes, `-D_GLIBCXX_ASSERTIONS` | 46 to 49 | 7 (6) | 10 (2) |

1. **Bounds checks in the inner loop.** `x0[ch]`, `wk.subspan(ch * kTile, kTile)` and `wc.subspan(kL)` are checked by libstdc++ on every
   iteration. In the hardened 8-lane loop (`l8-assert.s`) the compare-and-jump pairs sit between the fused multiply-adds, for example
   `cmpq %rdx, 48(%rsp)` / `je .L385` (`.L385` calls `std::__glibcxx_assert_fail`), and the loop pointers live on the stack (`40(%rsp)`,
   `56(%rsp)`). On the Mac the same source costs about 1 % with clang and `libc++` hardening FAST, so there the checks do not sit in the loop in this way (not looked at in the assembler).
2. **A stack round trip for every vector load at 16 lanes.** `load()` copied through a `std::array<float, 16>` with `std::copy_n`. At 8
   lanes that is one `vmovups`; at 16 lanes GCC 12 writes the 64 bytes to a stack buffer as two 32-byte stores and reloads them as one
   64-byte vector, twice per iteration (`vmovdqu %ymm2, (%r14)` / `vmovdqu %ymm5, 32(%r14)` ... `vmovups -128(%rbp), %zmm15`). A load that
   spans two earlier stores cannot be forwarded from the store buffer on Intel cores. This fits the measured order, 16 lanes slower than 8,
   and also the extra cost of the unchecked 16-lane build. It is a reading of the assembler: the stall itself was not measured.
3. The unchecked 8-lane loop was already good (16 instructions of work plus the loop overhead). That build still uses half the width of Java's
   512-bit species on an AVX-512 machine; the comparable C++ build is the 16-lane one.

**Change** (`src/nn/simd_net.cpp`; `simd_net_nofma.cpp` includes it; `apps/nn_check.cpp` got a `digest=` field):

- `load` and `store` are one `__builtin_memcpy` of a vector from or to a raw pointer (a single `vmovups`, no temporary).
- `conv()` cuts every range once per call with `std::span::first` (checked: a hardened build asserts there) and calls `conv_raw()`, which
  indexes with pointers. The gather entries are the only indices that come from data; the constructor proves once that all of them are below
  `kRows` (`gather_ok_`), and `forward()` fails otherwise.
- The same for `dot` (the range of `b` is cut to the length of `a` once) and for the mean pooling of the value head.
- Unchanged: the order of the operations. The accumulators, the order of stencil entry and channel, `splat(x) = x - 0`, the ReLU form, the
  lane order of `reduce_add`, and `-ffp-contract=fast` only for `simd_net.cpp`. The region of raw pointers is marked with
  `#pragma clang unsafe_buffer_usage begin/end` (clang only; gcc has no such pragma).

**Result in the assembler** (g++ 12.3.0; `perl scripts/simd-loop-report.pl --check`, also a check of `container-inner.sh`):

| variant | instructions | fma | conditional jumps (to an assertion block) | stack operands |
|---|---|---|---|---|
| 8 lanes, ymm, unchecked or `-D_GLIBCXX_ASSERTIONS` | 18 | 8 | 1 (0) | 0 |
| 16 lanes, zmm, unchecked or `-D_GLIBCXX_ASSERTIONS` | 18 | 8 | 1 (0) | 0 |

```
.L4:  vmovdqu8 (%rdx), %zmm15   vmovdqu8 64(%rdx), %zmm0   vbroadcastss (%rax), %zmm5   (3 more broadcasts)   addq $4, %rax
      vfmadd231ps %zmm15, %zmm5, %zmm13   vfmadd231ps %zmm0, %zmm5, %zmm12   ... (8 in all)   subq $-128, %rdx   cmpq %rax, %r11   jne .L4
```

The hardened build now has one copy of the loop (the three call sites are no longer inlined three times). The libstdc++ assertions remain
outside the loops (the cuts once per call).

**Equality.** `nn_check --impl simd|simd-nofma --preset small|large|gen11` prints `digest=`, an FNV-1a 64 over the bit patterns of all logits
and values. Old and new source (g++ 12.3, `build-cuda.sh --stub`, lanes 8 and 16, `-ffp-contract=fast` for `simd`) gave 12 equal digests
(2 variants x 3 presets x 2 lane counts), on x86-64 under Rosetta and on arm64 (native NEON FMA). The digests of x86-64 and arm64 are also equal to
each other. `ctest --preset debug-asan`, `release` and `release-hardened` pass (10 of 10 each), `nn_test` included.

**Mac, mechanics only (not for comparisons):** `nn_bench --impls simd,simd-nofma` at batch 256, 4 lanes, release-hardened, old against new
code: SMALL simd 4,163 against 4,208 (new 4,211 in a second run), LARGE simd 709 against 711 evaluations/s; nothing got slower.

**Not checked.** The speed on AVX-512 hardware. Rosetta cannot run AVX, so everything above is the assembler and its equality, not
a time. Whether the 16-lane build is now faster than the 8-lane build, what the hardened build costs, and how it compares with Java's Vector
API will show a second AWS run. The explanation of the store-forwarding stall (point 2) is also a hypothesis until then.

**Result of the second AWS run (2026-11-15, `results/aws-t4-run2-2026-11-15.txt`).** The diagnosis holds. Hardened build, batch 256, evaluations/s,
old against new code on two instances of the same type: `simd` 16 lanes SMALL 1,154 -> 8,388 (7.3x), LARGE 204 -> 1,197 (5.9x); 8 lanes SMALL
1,939 -> 5,140, LARGE 349 -> 819. The hardened build is now as fast as the unchecked one (0.97 to 1.01; run 1: 2.4x slower at 8 lanes), and 16 lanes
beat 8 lanes (1.6x SMALL, 1.5x LARGE; run 1: 0.6x). `nn_check` shows the same error fractions as before for all 12 lines. Against Java's Vector API
(16 lanes) C++ `simd` 16 lanes is now ahead: paired ratios 1.83 to 2.25 (SMALL) and 1.30 to 1.35 (LARGE), block quotients 1.48 and 1.30. By the rule
these ratios are only indicative (Java anchors unsettled in both presets, C++ SMALL anchor 2.7 %; the C++ LARGE anchor is valid). The two fixes went in
together, so their shares are not separable, and the store-forwarding stall itself was not measured.

## Metal backend, first half (WP11a)

`src/gpu/metal/` ports `TornadoNetwork` on Metal. `metal_net.hpp` is plain C++ (pimpl, no Objective-C type); `metal_net.mm` is built with
`-fobjc-arc`; `kernels.metal` is embedded as a raw string and compiled at run time (`newLibraryWithSource`, `MTLMathModeSafe`, once per process).

| Part | What it does |
|---|---|
| `conv_naive` | one thread per (row of the batch, output channel); sum over k, then ch, ascending; bias; optional residual; ReLU `v < 0 ? 0 : v`; padding row 0 |
| `policy_head`, `value_head` | one thread per (position, logit); one thread group of 256 per position with the partial-sum pooling, dense, dense; `apply_tanh = 0` for `classic1` |
| tanh | `x > 8.5 ? 1 : x < -8.5 ? -1 : tanh(x)` (`DeviceMath.tanh`), only where `apply_tanh = 1` |
| buckets | powers of two up to `max_batch` (1,024), buffers built on first use (`plans_built()`), chunks above `max_batch`; host priors with `apply_host_priors` |
| memory | `copy` mode: private device buffers, a blit of the input to the device and of the head buffer back, one command buffer per call |

`MetalNet::create` refuses `Tiled`, `Simdgroup` and `MemoryMode::Shared` for now (WP11b). `nn_check --impl metal [--variant naive] [--memory copy]`
checks it against `nn-ref-*` with the `tornado` extra term of the manifest (none is needed).

Measured on 2026-11-15 (Apple M3 Max, debug-asan and release-hardened binaries, all 1,024 positions, batch 64), largest error as a fraction of the bound:

| Preset | `nn_check --impl metal` | `TornadoNetwork` (Java, WP8) |
|---|---|---|
| SMALL u5 | 0.0196 | 0.0196 |
| LARGE u5 | 0.0499 | 0.0499 |
| LARGE gen11 | 0.0068 (logits 0.0011) | 0.0069 |

`metal_test` also ran once with `MTL_DEBUG_LAYER=1 MTL_SHADER_VALIDATION=1` ("Metal API Validation Enabled", "Metal GPU Validation Enabled"): no finding.

Mutation check of the kernels (each one at a time, `metal_test` red unless listed): pooling over 120 cells, bias dropped, ReLU as `v > 0 ? v : 0`, residual dropped,
policy logits swapped, padding row not zeroed, device tanh saturating to 0.5. **Survivors:** the threadgroup barrier before the last dense layer of `value_head`
(removed: 12 of 12 tests green in three runs, also with `C = 8, H = 1024` at batch 1,024); the Java test found its barrier mutants only under load, and on an idle GPU the
SIMD groups of a thread group stay in step. The barrier is in the kernel; the test cannot prove it.

Deviations from the plan: `sh_metal` links `sh_nn` (the header uses `Parameters`, `NetworkConfig` and the bucket rule), one word in `CMakeLists.txt`; the same line is needed on `exp/cpp`.

## Metal backend, second half (WP11b)

`kernels.metal` gains `conv_tiled` and `conv_simdgroup`; `MetalNet` gains the variant rule, the `shared` memory mode, the phase timing and the instrumented mode.

| Part | What it does |
|---|---|
| `conv_tiled` | thread group 8 x 16, tile 64 rows x 32 channels x 32 columns, 4 x 4 register tile interleaved (row `ty + 16 i`, channel `tx + 8 j`), local tiles padded by one column; every thread reaches every barrier |
| `conv_simdgroup` | 128 threads = 4 SIMD groups, 32 x 32 x 32 tile, 2 x 2 blocks of 8 x 8 per SIMD group with `simdgroup_multiply_accumulate`, W loaded transposed, result through a threadgroup tile, then bias, residual, ReLU |
| variant rule | `Options::variant` empty (the default) = `nn::variant_for`: `naive` while bucket * C <= 128, else `simdgroup`; a value forces one kernel (`nn_check --variant auto\|naive\|tiled\|simdgroup`, default `auto`) |
| `shared` mode | `--memory shared`: the input buffer and the head output buffer are `MTLStorageModeShared`, the CPU writes and reads them, no blit; activations stay private |
| phase timing | `timings()`: CPU clock for copy in, commit-and-wait, copy out, host priors, plus GPU time (`GPUEndTime - GPUStartTime`), totals since `reset_timings()`; `setup()` per bucket: pipeline creation and buffer allocation; `library_compile_ms()` once per process |
| instrumented mode | `Options::instrumented`: one command buffer per kernel and per blit, each timed by the GPU clock, `kernel_times()` lists them (`blit_in`, `conv_in`, `blockN.conv1`, `blockN.conv2`, `policy_head`, `value_head`, `blit_out`); its wall time is not a result |

`metal_test` additions: every conv shape of TINY, SMALL and LARGE with each of the three kernels at batch 1, 3 and 67; sizes that are not multiples of a tile (1->1, 5->70, 64->31, 9->33 ...); the hand-computed case for all three; NaN kept through ReLU in all three; the whole network in the two memory modes times auto and three forced variants, at `max_batch` 1,024 and 16, against `nn-ref-*`; a position gives the same bits in slot 0, 1, 2 of a bucket of 8, in a bucket of 4 and in a bucket of 1 (every mode); timing, set-up record and variant rule; instrumented mode gives the same bits as plain mode.

Measured on 2026-11-15 (Apple M3 Max), mechanics only, no timing figures (the machine was not quiet): `nn_check --impl metal` for `auto`, `naive`, `tiled`, `simdgroup`, each in `copy` and `shared`, with the `debug-asan` and `release-hardened` binaries: 16 runs, all within the bound, with the same largest errors as the naive kernel (SMALL 0.0196, LARGE 0.0499, gen11 0.0068, fractions of the bound). On SMALL at 64 positions the logits of `tiled` and `simdgroup` equal those of `naive` bit for bit (0 of 15,488 differ), as the Java test found on this device; nothing guarantees it.

Mutation check of the new kernels (each at a time, `metal_test` red unless listed): `simdgroup` W not transposed, `simdgroup` padding row not zeroed, `tiled` interleave changed, `tiled` barrier after the tile loads removed, `simdgroup` bias replaced, `tiled` ReLU as `v > 0 ? v : 0` (found only by the NaN test added for it), `simdgroup` k loop one step short. Equivalent mutant: clamping the weight index of `tiled` beyond the shape (the A tile is zero there).

`metal_test` also ran once with `MTL_DEBUG_LAYER=1 MTL_SHADER_VALIDATION=1`: no finding.

Deviation: `Options::variant` became `std::optional<ConvVariant>` (empty = rule) instead of a value with default `Naive`; `nn_check` default is now `--variant auto`. The per-bucket pipelines are created by the net without the process-wide cache so that `setup()` shows the creation cost of every bucket; Metal's own binary cache can still make the second one cheap.

## CUDA backend (WP12)

The CUDA code could not be compiled or run where it was written: there is no nvcc and no NVIDIA GPU on the Mac. What was done instead
is listed under "What was checked, and what was not". The first real run of `cuda_net.cu` is the CUDA gate of the AWS run.

### Files

| File | What it is |
|---|---|
| `src/gpu/cuda/kernels.cuh` | the kernels `hexconv_naive`, `hexconv_tiled`, `head_policy`, `head_value`, the launch geometry (plain numbers) and `run_forward`, the template that gives the order of the launches of one pass (conv_in, per block conv1 and conv2 with the ping-pong of the three buffers, policy head, value head). One unsafe zone, the whole file in `unsafe_buffer_usage` regions. Includes only standard headers |
| `src/gpu/cuda/cuda_net.cu` | the thin host layer, nvcc only: RAII classes `DeviceBuffer`, `PinnedBuffer`, `Stream`, `Event` (deleted copy), one plan per (bucket, variant), one stream, events around host to device, kernels and device to host, an event after every kernel in instrumented mode. Includes only `cuda_net.hpp`, `kernels.cuh`, standard and CUDA headers |
| `src/gpu/cuda/cuda_net.hpp` | the C++20 interface `CudaNet` (abstract class, standard headers only): shape and weights as spans, `forward_raw(input, Call, logits, values, CallTimes&)` for one chunk of one bucket. Errors are strings |
| `src/gpu/cuda/cuda_net_stub.cpp` | the Mac build: `CudaNet::create` returns "no CUDA device in this build" |
| `src/nn/cuda_backend.{hpp,cpp}` | the C++23 adapter `CudaBackend`: packing of a `Parameters` object (`pack_weights`), bucket rule and variant rule (`naive` while bucket * C <= 128, else `tiled`; `simdgroup` is refused), chunks above `max_batch`, dirty padding row refused, host priors, phase totals. `create` takes an optional `NetFactory`, so that the tests can pass an emulated device |
| `tests/cuda_emu.hpp` | the CPU emulation of the CUDA execution model: the real `kernels.cuh` is compiled with the keywords as macros, a block is one `std::thread` per CUDA thread, `__syncthreads()` is a `std::barrier`, device buffers are exact-size vectors (NaN-filled, so that an element that a kernel does not write shows up) |
| `tests/cuda_adapter_test.cpp` | on the Mac: stub behaviour, argument packing against the `Parameters` layout, launch geometry, every convolution shape with both kernels against `hex_conv`, both heads against a double computation, the whole network through the adapter and the emulated device against `ReferenceNet` |
| `tests/cuda_test.cpp` | on the instance: the real device against `ReferenceNet`, no golden file (`tests/nn_random.hpp` makes weights and inputs from `SplittableRandom`). Built a second time as `cuda_test_emu` against the emulated device by `build-gcc12-stub.sh` ("a test of the test") |
| `scripts/build-cuda.sh` | the build on the instance (and, with `--stub`, on the Mac) |
| `scripts/ptx-check.sh`, `scripts/ptx-shim/cuda_runtime.h` | syntax and type check of `cuda_net.cu` with clang in CUDA mode |
| `scripts/cloud/test/test_build_cuda.sh` | `build-cuda.sh` with a fake nvcc, g++-12 and objdump |

### Kernels and flags

The kernels are the Metal kernels of WP11a and WP11b without `simdgroup`: the same sums in the same order (k, then ch, ascending; bias last; optional residual; ReLU `v < 0 ? 0 : v`),
tile 64 x 32 x 32 with a 4 x 4 register tile and block 8 x 16 for `tiled`, one block of 256 per position for the value head with the partial-sum pooling, `tanhf` inside |x| <= 8.5 and exactly +-1 outside (only where `apply_tanh` is set: plane sets without host priors). Naive blocks are 32 channels x 8 rows (the Java side leaves
the choice to the runtime, this is a choice of this port). Kernel names do not equal a CUDA math function (TornadoVM pitfall 15).

`build-cuda.sh` compiles `cuda_net.cu` with `nvcc -std=c++20 -ccbin g++-12 -O3 -lineinfo --fmad=true -gencode arch=compute_75,code=sm_75 -gencode arch=compute_75,code=compute_75 -Xcompiler -ffp-contract=off`
and no `--use_fast_math`. `--fmad=true` is what TornadoVM's NVRTC call does by default (WP8 step 6, read from bytecode, not confirmed by a run). Everything else is `g++-12 -std=c++23 -O3 -march=native -ffp-contract=off`
(`-mcpu=native` on arm64), `simd_net.cpp` alone with `-ffp-contract=fast` after it. The flags and tool versions of a build go to `<out>/toolchain/`.

```sh
cpp/scripts/build-cuda.sh [--nvcc PATH] [--cxx PATH] [--out DIR] [--stub] [--werror] [--with-tests] [--jobs N]
```

Output `<out>/hardened/bin` (`-D_GLIBCXX_ASSERTIONS`, the main rows and the tests) and `<out>/unchecked/bin` (the "cost of the checks" rows), each with `nn_check_l8`, `nn_check_l16`,
`nn_bench_l8`, `nn_bench_l16` and `cuda_test`; `l8` and `l16` are `SH_SIMD_LANES` (the application and the SIMD objects must agree, because `simd_net.hpp` is lane dependent). Exit codes: 3 no nvcc, no g++-12 or no `compute_75`
in `nvcc --list-gpu-arch`; 4 a compile or link failed; 5 the object-code proof of `check-fma.sh` failed (run for both lane counts); 64 usage. `-Werror` only with `--werror`: the Mac stub build proves "no warning" with GCC 13.5,
but the instance does not stop for a warning that only g++ 12.3 gives.

`cuda_test [--allow-no-device] [--batches 1,3,67,256]`: presets TINY (classic1 and plain), SMALL, LARGE and a 33-channel network (a width that is no multiple of a tile), variants auto, naive, tiled, both host-memory modes
(`pageable`, `pinned`), batches 1, 3, 67, 256, each against `ReferenceNet` within the bound; NaN stays in its position; exact saturation of the value head; timings and per-kernel events; whether a position gives the same bits in bucket 1
and bucket 8 is printed as information (CUDA gives no guarantee, the Metal port has it). `nn_check --impl cuda [--variant auto|naive|tiled] [--memory pageable|pinned]` works like the Metal one; in the stub build it answers exit 64.

### What was checked here

| Check | Result (2026-11-15, Apple clang 21, GCC 13.5) |
|---|---|
| `ctest --preset debug-asan` | 10 of 10 tests pass; `cuda_adapter_test`: 16 tests, 2,489 checks |
| Kernels run on the CPU by `cuda_emu.hpp` against the reference | every shape of TINY, SMALL, LARGE and odd sizes (1 -> 1, 5 -> 70, 64 -> 31, 9 -> 33), both kernels, batch 1, 3 and 67, every combination of residual and ReLU; both heads for C = 1 to 1024; the whole network through `CudaBackend` for the three kernel choices, chunks 4 + 3 and 3 + 3 + 1 |
| Mutation check of `kernels.cuh` (one at a time, `cuda_adapter_test` red unless listed) | residual dropped (naive, tiled), tiled interleave, ReLU as `v > 0 ? v : 0`, policy logits swapped, pooling over 120 cells, tanh threshold 0.85, padding row not 0, no ping-pong swap, residual from the wrong buffer, row guard of the tiled store removed (AddressSanitizer), wrong output offset (AddressSanitizer), `apply_tanh` inverted, three `__syncthreads()` removed one at a time (the emulation runs real threads, so a missing barrier is a race that the comparison sees), the barrier before the last dense layer of the value head. **No survivor** (unlike the Metal check, where that barrier survived) |
| `cmake --preset tidy` on `sh_cuda_stub`, `cuda_adapter_test`, `cuda_test`, `nn_check` (clang-tidy 21, warnings, no gate) | 56 warnings over the files it also parses (`metal_net.hpp`, `simd_net.*` among them). In the new CUDA files: C arrays in `kernels.cuh` (the `__shared__` tiles and the register tile, inherent to the kernels), widening of `int` products in the tests, the reserved identifiers `__global__` and the like that `cuda_emu.hpp` defines as macros on purpose, public members of `EmuNet`. The two in product code (special members of `CudaNet`, the base type of `ConvKind`) were fixed |
| `scripts/ptx-check.sh` | host and device side of sm_75 parse and type-check with Apple clang in CUDA mode against `ptx-shim/cuda_runtime.h`; `--self-test` shows that a kernel calling a host function and a launch with a wrong argument list are found; a misspelt member in `kernels.cuh` was found too |
| `scripts/build-gcc12-stub.sh` (GCC 13.5, `build-cuda.sh --stub --werror --with-tests`) | every Linux source builds with `-O3 -Werror` without a warning, hardened and unchecked, both lane counts; `check-fma` finds 62 (lanes 8) and 44 (lanes 16) fused instructions in `simd_net.o` and 0 in `simd_net_nofma.o`; `java_compat_test`, `nn_test`, `cuda_adapter_test`, `cuda_test_emu` pass; `nn_check` for `reference`, `simd`, `simd-nofma` on SMALL and LARGE, all 1,024 positions, within the bound (`reference` bit-identical) |
| `scripts/cloud/test/test_build_cuda.sh` | 55 checks with a fake nvcc, g++-12 and objdump: the command lines, exit 3 (no nvcc, no g++-12, no `compute_75`), exit 5, exit 64, `--stub`, `--werror`, `--nvcc` and `--cxx`; six mutations of `build-cuda.sh` (no `-ccbin`, no PTX gencode, fast contraction everywhere, no `compute_75` check, a fast-math flag, C++17) each turn it red |

### What was NOT checked, and what can still fail on the instance

This list is the risk list from before the paid run. The run of 2026-11-15 settled the nvcc build (nvcc 13.2 with g++-12), g++ 12.3, `cuda_test` (244 checks, 6 passed) and `compute-sanitizer` (0 errors); see `results/aws-t4-2026-11-15.txt`, section Correctness. Still open: the PTX, register use and occupancy were not inspected. The items below are kept as written then.

- **At the time of writing, nvcc had never read `cuda_net.cu` or `kernels.cuh`** (settled by the AWS run: it builds). Clang accepts what nvcc may reject (a construct that nvcc 13.0 with `-std=c++20 -ccbin g++-12` does not support, a host/device attribute detail, `constexpr` variables in device code). This is the most likely cause of a failed build; the gate stops the instance after about 6 minutes (0.02 USD).
- **The shim is not the CUDA header.** The signatures in `ptx-shim/cuda_runtime.h` are written from memory of the runtime API; a wrong one would pass here and fail on the instance.
- **No PTX was generated**: Apple clang has no NVPTX target. Register use, shared-memory size (9 KB per block for `head_value`, 12.25 KB for `tiled`, computed from the array sizes), occupancy and the launch bounds were not seen.
- **The device behaves differently from the emulation** in what the emulation cannot show: warps and divergence, memory ordering across blocks, the fused multiply-add of `--fmad=true` (the emulation is unfused; the bound 1e-4 + 1e-3 |ref| is what covers it), `tanhf` of the device (not bit-equal to the host's), pinned memory, events, the stream. `cuda_test` is the first test of these.
- **g++ 12.3 of Ubuntu** was never run (GCC 13.5 stands in for it, as in WP0). The CUDA AMI may have no `g++-12`, no `compute_75` in nvcc, no `objdump` (the proof then reports "skipped") or a different `-march=native` situation (AVX-512 lanes 16 on the host).
- **`-march=native` objects with `-DSH_SIMD_LANES=16`** assume an AVX-512 host; on an AVX2-only host the lane-16 binaries still work (vector extensions lower to two registers) but are not meant to be compared.
- **`compute-sanitizer`** had not run then; it ran on the AWS run with 0 errors (WP14/WP15 call it on `cuda_test --batches 1,67`; `-lineinfo` is set for it).
- `nvcc`'s linking (`nvcc -ccbin g++-12 ... objects -o binary`, which adds `cudart` statically) was only tested with the fake.

### Deviations from the plan, made in WP12

- `CudaNet` is an abstract class with `create` as the factory instead of a class with a hidden implementation (pimpl): the same header serves the real net, the stub and the test emulation. The adapter's `create` takes a `NetFactory` for the same reason.
- `forward_raw` takes a `Call` (n, bucket, variant, `apply_tanh`) and returns a string; the bucket, chunk and variant rules stay in the adapter, because `cuda_net.cu` may not include a project header.
- Both host-memory modes stage through host buffers (`std::vector` for `pageable`, `cudaHostAlloc` for `pinned`): the only difference is page-locked memory, as in the JVM's off-heap buffer against a pinned buffer.
- `cuda_test` without a device: exit 0 in the stub build (`ctest` on the Mac stays green without a change of `CMakeLists.txt`), exit 1 in a build with device code unless `--allow-no-device` is given (the plan says: exit 0 only with the flag).
- The slot-and-bucket bit equality is information in `cuda_test`, not a check (see above).
- `cuda_net.cu` is about 455 NCLOC, not the 200 of the plan: the RAII classes and the launcher with its error handling and the events are in it. The kernels are 270, the adapter 120 (without headers).
- New files beyond the plan's list: `tests/cuda_emu.hpp`, `tests/nn_random.hpp`, `scripts/ptx-shim/cuda_runtime.h`. `cuda_test_emu` is built by `build-cuda.sh --with-tests`, not by CMake (no new target).
- `ptx-check.sh` checks syntax and types, it does not emit PTX (no NVPTX target); with a clang that has one (`CLANG=...`) it also counts the kernel entries.
- `apps/nn_check.cpp`: the Metal part is guarded by `defined(__APPLE__) && defined(__clang__)` (GCC on the Mac has no Objective-C++), the CUDA part is always compiled.

## Load limit of the measurement sessions

The plan's load limit of 2.0 (1-minute average, Mac) could not be met on this machine: with no benchmark and no build running, the
1-minute load average of the owner's Mac is 3.0 to 3.7 at rest (window server about 50 % of a core, audio routing about 30 %, the
terminal about 10 %; measured on 2026-11-15 with `uptime` and `ps`, for example 3.38, 3.34, 3.49, 2.66, 3.27, 3.55). The limit of
the measurement scripts (`scripts/bench-mac.sh`, `scripts/bench-nn-mac.py`, default of `--max-load`) is therefore **4.0**. It is a
limit for that base load only. What stays strict: no process of the plan's list (`java`, `gradle`, `clang`, `cmake`, `ninja`, `g++`,
`docker`) other than the session's own, checked by name before every step of `bench-nn-mac.py`; a limit of 350 % of one core for the
CPU use of all processes except the benchmark child (sampled every 10 s with `ps`; the base load at rest is 140 to 175 %); the power
adapter; the thermal state. The self-ratio of 0.98 to 1.02 (classical) and the anchor rows of 2 % (network) remain what decides
whether a session is valid. The 1-minute average after a session contains the benchmark's own busy thread (about 1.0), so the
check "load after" waits up to 5 minutes for the average to fall below the limit and records both values.

OrbStack's Kubernetes system pods (5 idle containers) run at rest; they are part of the base load and are listed in every result file.

## Network benchmark and Mac measurement (WP13)

```sh
cmake --preset release-hardened && cmake --build --preset release-hardened   # also: release
cpp/build/release-hardened/nn_bench --golden cpp/golden --presets small,large --batches 1,16,64,256,512,1024 --impls simd,metal-copy
cpp/build/release-hardened/nn_bench --golden cpp/golden --anchors            # SMALL and LARGE at batch 256
cpp/build/release-hardened/nn_bench --golden cpp/golden --presets small,large --batches 1,64,512 --kernels
cpp/build/release-hardened/nn_bench --golden cpp/golden --presets large --batches 64 --impls metal-copy --cold
python3 -I cpp/scripts/bench-nn-mac.py --detach            # the whole session, see the header of the script; log path and PID are printed
python3 -I cpp/scripts/bench-nn-mac.py --status            # running? (lock probe, then status file)
perl cpp/scripts/merge-bench.pl <work dir>                  # the summary of a session (the script calls it itself)
```

`nn_bench` prints the shared schema of `CrossBench` (see above) plus comment lines: `# cpu:` (thread CPU time over wall time of the
measured rounds of a case), `# gpu:` (GPU time of the Metal command buffer per call), `# <impl> <preset> <weights>: metal ...` (library
compile and per-bucket set-up), `#instrumented` (Metal instrumented mode, the counterpart of `#profiled`), `#kernels` (the convolution
kernels alone). Implementations: `reference`, `simd`, `simd-nofma`, `metal-copy`, `metal-shared`, `cuda-pageable`, `cuda-pinned`
(an implementation that the build or the machine lacks gives exit 64); `--variant` forces a convolution kernel (the label of the row
then reads `metal-copy:naive`). Every case ends with the check of its last call against `nn-ref-*` (exit 2 and no line on a violation;
`reference` must also be bit-identical).

A session (`bench-nn-mac.py`): hygiene, anchor rows (start, middle, end), N = 3 pairs of processes J, C, ... (the table of SMALL and
LARGE u5 with all implementations, the gen11 rows, the cost of the checks as release-hardened against release at batch 256 with the
order alternating between pairs), the kernel table (Java `KernelsBenchmark` against `nn_bench --kernels`, two rounds) (the clocks differ: Java reports the TornadoVM profiler's `kernelNanos`, C++ the GPU time of one command buffer with one dispatch, so the C++/Java column is indicative only), the cold-start runs
(three rounds, LARGE, batch 1 and 256), then the checks: anchors of the end against the start within 2 % for every program, no foreign
process, no foreign CPU load above the limit, no CPU case with a thread CPU time below 0.8 of the wall time. A failed check makes the
session invalid (exit 4): no result file, the files stay in the work directory; after three invalid sessions in a row measuring stops.

### GPU anchor drift on the Mac (finding of 2026-11-15, no valid session; booked under the amendment below)

Five full sessions (about 46 minutes each) had no valid GPU anchors. The CPU anchors were stable in every session (`simd`
LARGE 709.3 / 709.5 / 705.0 evals/s at start / mid / end of the last one); the GPU anchors moved by 8 to 40 % and Java TornadoVM
and C++ Metal moved together. In the last session (LARGE, batch 256, evals/s, start / mid / end): TornadoVM 33,487 / 23,891 /
30,798, Metal copy 33,034 / 25,737 / 29,242. Isolated tests of the same day: six consecutive Metal anchor runs of 10 s each gave
32,783 to 33,286 evals/s (within 1.5 %, rising slightly), and a CPU-heavy run before an anchor changed nothing (33,267 against
33,313). The GPU therefore sits at about 33,000 after an idle period and drops to 24,000 to 26,000 after the GPU-heavy middle of
a session, without a thermal warning (`pmset -g therm`). Cause not proven (power or clock management of the SoC under sustained
GPU load). `bench-nn-mac.py` now stops before the first step if the gen11 weights are missing (`STERNHALMA_DATA_DIR`) and returns
exit 4 at the first failed step instead of crashing while writing the raw file.

### Amendment 2026-11-15 (lead): booking of the network measurement

The abort rule of the plan is reached (five invalid Mac sessions), and the lead changed the rule for this measurement; the full text is
in `docs/cpp-comparison-plan.md`, section "Decision rule", "Amendment 2026-11-15 (lead)". In short:

- CPU rows are **valid** if the anchor of their program lies within 2 % between start and end of the session, otherwise **unsettled**
  (not dropped, not smoothed).
- GPU rows (Metal, TornadoVM) are **unsettled**, with the anchors at start, middle and end in the row. A Java:C++ ratio comes only from
  the two processes of one pair, is labelled "unsettled (GPU clock drift)", and says nothing about a difference below 5 %.
- Reason: both GPU programs fall together by 20 to 30 % in the GPU-heavy middle of a session (TornadoVM 33,487 / 23,891 / 30,798 and
  Metal copy 33,034 / 25,737 / 29,242 evals/s, LARGE, batch 256, start / mid / end), while six isolated Metal anchor runs from idle
  agree within 1.5 % and CPU load does not disturb the GPU anchor.
- No further Mac session is run. Session 5 is booked by `scripts/book-nn.py` (tests: `scripts/test_book_nn.py`):

```sh
python3 -I cpp/scripts/book-nn.py <work dir of the session> cpp/results/mac-nn-2026-11-15 --date 2026-11-15 --build "<text>" --size "<text>"
python3 -I cpp/scripts/test_book_nn.py
```

  It fails (exit 3) on any step with a non-zero exit code, any row whose `verified` column is not `ok`, and any step whose rows plus
  skipped cases do not equal the schedule in its header. Results: `results/mac-nn-2026-11-15.tsv` (one row per figure: table, program,
  case, batch, metric, median, min, max, status, anchor start/mid/end, note), `results/mac-nn-2026-11-15.txt` (summary),
  `results/mac-nn-2026-11-15-raw.tsv` (raw output of every run) and `results/mac-nn-2026-11-15-summary.txt` (`merge-bench.pl`).

## AWS run on the Tesla T4, booked (WP15)

The run of the cpp suite (`g4dn.xlarge`, spot, Frankfurt; all 19 steps OK, 1,651 s, about 0.15 USD) is booked by `scripts/book-aws.py`
(tests: `scripts/test_book_aws.py`) into `results/aws-t4-2026-11-15.tsv` and `.txt`. The `.txt` starts with "Status: read this first".

```sh
python3 -I cpp/scripts/book-aws.py <run dir>/results cpp/results/aws-t4-2026-11-15 --mac-tsv cpp/results/mac-nn-2026-11-15.tsv \
  --cost <run dir>/cost.tsv --cost <other run dir>/cost.tsv --commit "<text>"
python3 -I cpp/scripts/test_book_aws.py
```

Rules applied: a row is `valid` if all four anchor values of its program (batch 256: two runs at the start, two at the end) lie within 2 % of
each other, else `unsettled` with the drift; a Java:C++ ratio comes only from the four adjacent anchor pairs (C, J, C, J); a division of two
blocks is `unpaired`; no statement below 5 %; n = 1. The script fails (exit 3) on a step that is not OK, a row whose `verified` is not `ok` and a
block whose rows plus skipped cases differ from its schedule. Result in short: the GPU programs (C++ CUDA, TornadoVM CUDA) are valid and
within 3 % of each other at batch 256 (no statement); the CPU programs are unsettled (steps of 34 % between consecutive anchor runs); C++ CUDA is
slower than TornadoVM at batch 1 (LARGE: 1.86 against 0.90 ms; the auto variant is the naive kernel set there). The CUDA test, the sanitizer and
`nn_check --impl cuda` pass. Not measured: the cold process of C++ CUDA and TornadoVM (the instance's `--cold` step ran one case only). For a second run `bootstrap.sh` now starts
one process per case: `nn_bench_l8 --presets P --batches B --impls I --cold` for SMALL and LARGE, batch 1 and 256, `simd`, `cuda-pageable`,
`cuda-pinned`, and `crossBench ... --cold` for `vector` and `tornado`, in 3 rounds (`--cold-rounds N`); the stub tests in
`scripts/cloud/test/test_suite_order.sh` count the processes and check that each names its preset, batch and impl. A mid-block anchor is not
in the plan for the instance (anchors at the start and end, C, J, C, J) and was not added.

### Second run, booked (WP15, run 2)

The second run (`g4dn.xlarge`, spot, 1,619 s, all 19 steps OK, about 0.14 USD; source: `exp/cpp` at the SIMD change and the per-case cold start) is booked
the same way into `results/aws-t4-run2-2026-11-15.tsv` and `.txt` (starts with "Status: read this first"):

```sh
python3 -I cpp/scripts/book-aws.py <run 2 dir>/results cpp/results/aws-t4-run2-2026-11-15 --run 2 --prev <run 1 dir>/results \
  --commit 2446bd1 --cost <aborted run dir>/cost.tsv --cost <run 1 dir>/cost.tsv --cost <run 2 dir>/cost.tsv
```

`--run 2` writes the data-driven text: before/after of the SIMD fix next to run 1 (tables `simd_fix` and `gpu_repeat` in the TSV), the pairs, the
anchor patterns, and the cold start per case (table `cold_start`: median, lowest and highest of 3 fresh processes for construct, first call and process to
first result; status `unanchored`, since no anchor applies to a cold figure). The same rules apply (anchors within 2 %, pairs only, nothing below 5 % on
the GPU, n = 1 per instance). Result in short: the GPU programs are valid and within 2.0 % of run 1; C++ CUDA and TornadoVM are within 3 % of each other at
batch 256 (no statement) and TornadoVM is 1.7 to 2.1 times faster at batch 1; the CPU rows are unsettled except C++ LARGE (Java Vector SMALL has two
levels, about 3,600 to 4,600 in the anchors and 5,700 in its block). Cost of the cpp suite over three runs: 0.396 USD (the aborted run counted as its
upper bound), the 5 EUR budget about 13 % used (estimate, with the owner's 0.3 USD).

## Race play and the games row (WP6)

`src/search/solitaire.*` (the solitaire move generator, the parity lower bound and the beam search `plan`) and `src/search/race_move.*` are
faithful ports of `Solitaire.java` and `RaceMove.java`; `AlphaBetaPlayer` takes an optional `RaceMove` and `PlayerSpec::create` builds it for
`race=1|2` (`racerows` 1 or 2; the shared tables are loaded once per process). Deviations: the Java option `racetable=0` (beam only, no table) is
not ported; the two `IllegalStateException`s of `Solitaire.plan` (a move the generator cannot reproduce, equal states in a plan) return "no plan"
instead; `RaceMove::choose` copies the position once to generate the legal moves (`Position::generate` is not const).

Equality (all with `STERNHALMA_GOLDEN_REQUIRED=1`): every line of `race-moves.tsv` (332 rows: 261 table moves, 71 plan moves, move and origin equal),
and all nine constants of `BestSpecFingerprintTest` for R2, R3 and BEST, from the `debug-asan`, `release` and `release-hardened` binaries of
`fingerprint` (the output of each equals the golden file `fingerprint-{r2,r3,best}.txt` byte for byte).

Games row: `scripts/bench-mac.sh --games 2 --specs best --out cpp/results/mac-classical-games-<date>.txt`. New options: `--games N` (passes `--games N` and
`--warm-games` to every run), `--specs` (a session with BEST_SPEC only; R2 has no race play), and in the result file the games alone, their ratios and the
check that Java and C++ played the games with equal node totals and move counts. `--warm-games` (Java `Bench` and `search_bench`): the untimed warm-up
also plays the full games. It was added because the first session stopped at the JIT trend check (the Java runs rose by 1.2 % over the three
repetitions while the games were not warmed up), which is the plan's "add one warm-up pass".

## Jump rule: the Halma long hop

The engine plays two jump rules, as the Java engine does (`RuleSet.JumpRule`, rules in `docs/rules.md`, section "Jump rule").
`RuleSet::JumpRule::Adjacent` is the default: a piece jumps over one adjacent piece. `RuleSet::JumpRule::Long` is the Halma long hop:
in each direction the first piece P at distance k is jumped, and the piece lands on the mirror cell at distance 2k. All cells between
the start and P, all cells between P and the landing, and the landing itself must be empty and on the board. Chained hops use the same
depth-first search as the adjacent rule, so the order of the generated moves is the same as in Java. The default rule keeps its code
path: `Position::generate` selects the long variant once per call from a flag set at construction. Measured on 2026-11-19 (release-hardened,
Apple silicon): the browser-spec search ran 2.49 and 2.50 M nodes/s before the change and 2.53 M nodes/s twice after it; a
depth-5 perft over the start position took 0.082 s before and 0.085 s after (noise level).

- **Search.** The `PlayerSpec` key `jump=long` (default `jump=adjacent`) names the rule a player plays. Race play (`race=1`, `race=2`) and
  the evaluators `racelin1-r3` and `racelin-r3` are refused with `jump=long`: the race table and its arithmetic count single jumps only.
  `choose()` throws `std::invalid_argument` for a position of the other rule (check `plays(position)` first where throwing is not an option).
  `kBrowserLongSpec` in `player_spec.hpp` equals Java `Strategies.BROWSER_LONG_SPEC`.
- **Golden check.** `long_jump_test` reads the Java file `engine/src/test/resources/long-jump-golden.tsv` in place (the path comes from
  CMake, so no second copy can drift) and reproduces all 30 rows: the move count, the SHA-256 of the sorted moves and the SHA-256 of the
  moves in generation order, once from the listed pieces and once from the seeded playout. The test fails, and does not skip, if the file
  is missing. It also compares `generate()` with a coordinate-based oracle over seeded playouts.
- **Browser API** (`src/wasm/wasm_api.cpp`). `shw_game_new2(jump, players)` and `shw_player_new(nodes, jump)` take the rule (0 adjacent,
  1 long); `shw_game_new(jump)` equals `shw_game_new2(jump, 2)`. An omitted argument from JavaScript is 0, which keeps every old call
  unchanged (`game.mjs` still shows the benchmark fingerprint).
  `shw_jump_rule()` returns the rule of the current game. `shw_choose` returns -2 for a player of the other rule. `shw_analyse` follows
  the rule of the game: for a long-hop game it uses `eval=linear-r3`, `tt=16` and `jump=long`, without race arithmetic. The tests are
  `wasm_api_test` (native, no Emscripten) and `cpp/wasm/test/longhop.mjs` (run by `scripts/web/test-wasm.zsh`).

## Foreign corners

The engine plays the three values of `RuleSet::ForeignCamps`, as the Java engine does (`RuleSet.ForeignCamps`, rules in `../docs/rules.md`,
section "Foreign corners"). `Closed` is the default and the rule the engine always played: steps and every landing of a jump chain only on
home cells (hexagon, own start corner, own goal corner). `PassThrough` lets a chain land on a foreign corner and jump on, but a landing
there is not a move. `Open` lets every move end there. `Position::generate` (`src/engine/position.hpp`) picks the tables per rule: the layout's tables
(`Layout::step_table`, `Layout::jump_table` in `src/engine/layout.hpp`, home cells only) for `Closed`, the board's landing table `geometry::land_table()`
(`src/engine/geometry.hpp`) for the chains of `PassThrough` and `Open`, and the board's step table `geometry::step_table()` for `Open`;
`PassThrough` filters the end cells. The order of the generated moves is the order of the Java generator (`Position.generate` in
`engine/src/main/java/sternhalma/engine/Position.java`, from the repository root). Unless the rule is `Closed`, race play, the
race evaluation and race adjudication (section "Race play and the games row (WP6)" above) are off, and `IncrementalLinear::reset` (`src/search/incremental_linear.hpp`) declines the position, so the full evaluation runs. `Layout::distance` gives a foreign
cell the step count over the whole board (at most 9; the home cells keep their values, at most 13). On the 5-row board the goal is the 15-cell house; the Java layout gives the bounds 8 and 12 there (`TriangleRowsTest`), and the C++ layout
is built by the same rule but has no test of these two bounds.

`tests/foreign_camps_test.cpp` reads the Java golden file `engine/src/test/resources/foreign-camps-golden.tsv` (from the repository root) in place (48 rows: both
values other than `Closed`, two and three players, both jump rules) and compares `generate` with a coordinate oracle over seeded playouts
of all three values. The wasm API has `shw_game_new4(jump, players, ranking, camps)` (camps 0 closed, 1 pass-through, 2 open;
`shw_game_new4` returns -1 for any other value and leaves the game unchanged) and `shw_foreign_camps()` (0, 1 or 2);
`wasm/test/camps.mjs` checks them under node.

### Search and the wasm API on 5-row boards

`RuleSet::TriangleRows::Five` (15-cell houses, 15 pieces; `docs/rules.md`, section "Board size (triangle rows)") reaches the search as follows.
The linear evaluator and the three-player search read their tables from the layout of the position's house size
(`linear_tables(rows)` in `src/search/linear_eval.hpp`, `tables3(position)` in `src/search/three_player_search.cpp`); the weights are those of the
4-row board and are not calibrated for 5 rows. One correction keeps their scale: the feature `dsumPhase` (the difference of the summed piece
distances, times the total remaining distance of both sides / 100) counts that total in units of 10 pieces, so 15 pieces multiply it by 10/15
(`LinearEvaluator::combine`, `phaseDistance` in Java). Without it the larger total of 15 pieces made a step towards the goal lower the score, and the
three-player browser player put no piece into its goal in 1,500 plies (measured 2026-11-20, `results/rows5-v1-2026-11-20.txt`). With 10 pieces the
factor is not applied, so 4-row scores are bit-identical. The futility near-win count is `goal_size() - depth`. The race code (`RaceEvaluator::rules_apply`,
`RaceAdjudication`, `RaceMove`) and `IncrementalLinear::reset` accept 4-row houses only, because the race table and the jump graph are computed for the
10-cell corners. `PlayerSpec` and `ThreePlayerSpec` take `rows=4|5`; `rows=5` is refused together with `race=1`, `race=2` and the racelin evaluators.
A player plays games of its own house size only (`plays()` checks it, `choose()` throws otherwise). `kBrowserRows5Spec`, `kBrowserRows5LongSpec`,
`kBrowser3pRows5Spec` and `kBrowser3pRows5LongSpec` are the browser players of 5 rows.

The wasm API adds `shw_game_new5(jump, players, ranking, camps, rows)` (rows 4 or 5, else -1 and the game is unchanged), `shw_triangle_rows()`,
`shw_move_limit()` (250 plies for two players and 375 for three on 5 rows), `shw_player_new_rows(nodes, jump, rows)` and
`shw_player_new3_rows(nodes, jump, rows)`. The cached analysis players are keyed on the house size. A move list now holds up to 1815 moves (7.3 KB), so
the two lists of `shw_analyse` are static storage and the module links with `-sSTACK_SIZE=1048576`. Every caller must size its move buffer for 1815 moves.

Tests: `tests/rows5_search_test.cpp` reproduces the Java golden file `search/src/test/resources/triangle-rows-search-golden.tsv` bit for bit (static
values of linear-r1, linear-r3 and linear-r2, and the move, score, depth and node count of 48 searches; written by
`TriangleRowsSearchGolden` in the search module), checks the sums and `bestGain` against references written from the layout, and checks the gates.
`tests/wasm_api_test.cpp` and `wasm/test/rows5.mjs` cover the new calls. A one-off link with `-sSTACK_OVERFLOW_CHECK=2` (2026-11-20) passed
`rows5.mjs`, `threeplayer.mjs`, `analyse.mjs`, `longhop.mjs`, `camps.mjs` and `ranking.mjs`.

## Three players

The engine also plays the three-player game, as the Java engine does (rules in `docs/rules.md`, section "Three-player specifics"; the search in
`docs/search-and-evaluation.md`, section "Three-player browser player"). `RuleSet::players()` is 2 or 3, and `RuleSet::defaults(players)` sets
the move limit to 200 plies for two players and 300 for three. `Layout::of(players)` returns `Layout::two_players()` or
`Layout::three_players()`; `Position::layout()` returns this shared layout by pointer. In play order the three players start in the top corner
(r < -4), the lower right corner (s < -4) and the lower left corner (q < -4), with coordinates as in `docs/rules.md`, section "Screen orientation and clockwise order"; each goal is the opposite
corner. `Outcome` has a `players` field and three values (unused entries are zero): a win gives the winner +1 and each other player -1/2.
Two-player play is unchanged in result and speed: the wasm benchmark game (the fingerprint that `cpp/wasm/test/game.mjs` prints) keeps its
checksum -1986550544382302149 and its 800000 nodes, and ran 1.88-1.89 M nodes/s before the three-player change and 1.90-1.92 M nodes/s after
it (node, wasm, same machine, two runs each).

- **Golden checks.** Two Java files are read in place (path from CMake), and both tests fail, not skip, if the file is missing.
  `three_player_test` reproduces all 40 rows of `engine/src/test/resources/three-player-golden.tsv` bit for bit, once from the seeded playout
  and once from the listed pieces. The file comes from `ThreePlayerGolden.java` in `engine/src/test/java/sternhalma/engine/` (Java
  `ThreePlayerGoldenTest` there checks it against a fresh run):
  both jump rules, seeded random and greedy playouts, 3 wins and 2 move-limit rows; per row the pieces, side to move, ply, Zobrist hash, move
  count, SHA-256 of the sorted and of the ordered move list, and the outcome with the bit patterns of its values. `three_player_search_test`
  reproduces every column of `search/src/test/resources/three-player-choose-golden.tsv` (from
  `search/src/test/java/sternhalma/search/ThreePlayerChooseGolden.java`): for 26 positions the chosen move, score, depth, node count and
  complete flag (false if the node limit stopped an iteration) at 5000 and at 20000 nodes, each with a fresh player. A mutation
  showed that the test catches an off-by-one: the node limit check changed from `>=` to `>` failed with 5001 against 5000 nodes.
- **Search.** `src/search/three_player_search.*` is a node-for-node port of Java
  `search/src/main/java/sternhalma/search/ThreePlayerSearch.java`: paranoid alpha-beta (both opponents
  play against the player at the root), the linear-r2 evaluation (weights `kRound2` in `linear_weights.hpp`) with a lateral term per player,
  a transposition table keyed by hash and root player, killer moves and gain-first move order. Exact equality needed no deviation, because
  all scores are integers and the floating-point terms use the Java order of operations. `ThreePlayerSpec::parse("ab3:...")` in
  `three_player_player.*` takes `eval` (`linear-r2` default, `linear-r1`, `linear`), `nodes` (20000), `depth` (30), `ms`, `tt` (16) and
  `jump`. `kBrowser3pSpec` is `ab3:eval=linear-r2,nodes=20000,tt=16`, and `kBrowser3pLongSpec` adds `,jump=long`. Unlike Java, `depth`
  below 1 and `tt` outside 0..30 are parse errors (Java accepts depth 0 and fails later on a bad `tt`). `ThreePlayerPlayer::choose()` throws
  `std::invalid_argument` for a position that is not three-player or has the other jump rule; check `plays(position)` first.
- **Browser API** (`src/wasm/wasm_api.cpp`, all new functions in the export list in `cpp/CMakeLists.txt`):

  | Call | Result |
  |---|---|
  | `shw_game_new2(jump, players)` | 0; -1 for `jump` not 0/1 or `players` not 2/3 (text in `shw_error()`), game unchanged |
  | `shw_players()` | 2 or 3 |
  | `shw_player_new3(nodes, jump)` | player id; -1 for `jump` not 0/1 or `nodes` < 1. Ids share one table with `shw_player_new` |
  | `shw_choose(id)` | plays for the side to move, whichever of the three it is; -1 no move; -2 (text in `shw_error()`) if the player's player count or jump rule differs from the game's |
  | `shw_game_new3(jump, players, ranking)` | like `shw_game_new2`, plus the ranking rule: `ranking` 0 ends the game at the first winner (`FIRST_WINNER_ONLY`), 1 plays on for the lower ranks (`FULL_RANKING`; changes nothing with two players); -1 for any other value. `shw_game_new2(jump, players)` is `shw_game_new3(jump, players, 0)` |
  | `shw_ranking_rule()` | 0 first winner only, 1 full ranking |
  | `shw_rank(i)` | the absolute player of rank `i` (0 = first place), or -1 if that rank is not decided: in a running full-ranking game the players that have finished, by rank; after the end the whole ranking (a first-winner game: only rank 0, the winner) |
  | `shw_finished_mask()` | the players that have finished, as bits (bit p for player p); 0 outside a full-ranking game with three players. `shw_legal_moves` returns no moves once the game is over |
  | `shw_outcome()` | -1 running; 0 draw (move limit, or the side to move has no legal move); 1 + winner, so 1, 2 or 3 |
  | `shw_analyse(...)` | works for three players as for two. Every legal move (or the given subset) is scored by `ThreePlayerPlayer::analyse_move`: a player of the browser three-player spec (`kBrowser3pSpec`, or `kBrowser3pLongSpec` for the long hop) with the node budget of the call and its own table, cleared before every move and separate from the game players. The score is the mover's paranoid score, without negation, and mate scores shift by one as for two players. Codes as for two players: -1 illegal move, -2 game over, -3 capacity too small, -4 player not created, -5 `nodes` < 1; the former -3 for a three-player game is gone. With `FULL_RANKING`, after a player has finished, the moves are those of the players still in play; a move that finishes the mover scores `kMate - 1` (first place) or `kSecond - 1` (second place), where `kMate` is the mate score and `kSecond = kMate / 2` (`src/search/three_player_search.hpp`), at depth 0 with 1 node, and second-place scores shift by one like mate scores. `three_player_search_test` reproduces `search/src/test/resources/three-player-ranking-analyse-golden.tsv` (16 positions) bit for bit |
  | `shw_evaluate3(int32_t* out3)` | writes the static evaluation of the current three-player position to `out3[0..2]`, one integer per absolute player, without a search. After a player has finished in a full-ranking game, that player holds a rank constant: 1000000 for first place, 500000 for second, and -1000000 for the player left last once the ranking is complete. Returns 0, or -1 (text in `shw_error()`) for a two-player game. Bit-exact with Java `ThreePlayerPlayer.evaluation`: `three_player_search_test` reproduces `search/src/test/resources/three-player-analyse-golden.tsv` (26 positions, both jump rules, 2000 and 5000 nodes, every legal move) bit for bit |
  | `shw_features(double* out13, int32_t* out_score_side, int32_t* out_dist242)` | the `linear-r3` evaluation of the current position, term by term, for the evaluation figure of the web app's Info pages. Writes the 13 features of `LinearEvaluator::features` with `GainMode::Both` (Java `LinearEvaluator.GAIN_NAMES` order, side to move minus opponent) to `out13`, the `linear-r3` score for the side to move and the side to move to `out_score_side[0..1]`, and `Layout::distance(p, cell)` to `out_dist242[p * 121 + cell]`. No search and no race table; it reads only the position of the last replay, never a game or analysis player. Returns 0, or -1 (text in `shw_error()`) for a game that is not two players, adjacent jumps, 4 rows and closed foreign corners. Equal to Java `WebCore.features` (`web/src/engines/features.cross.test.ts`); `wasm_api_test` and `cpp/wasm/test/features.mjs` check the position of `web/src/about/data/eval-mid.json` (score 179) and that calls between moves leave the `shw_choose` results unchanged. Exported through `EMSCRIPTEN_KEEPALIVE`, like `shw_analyse` |

  `shw_legal_moves`, `shw_play`, `shw_unmake`, `shw_owner` and `shw_side_to_move` work for three players (the side cycles 0, 1, 2).
  `shw_last_score`, `shw_last_depth`, `shw_last_nodes`, `shw_has_result`, `shw_total_nodes` and `shw_player_clear` work for both kinds of
  player; `shw_race_moves` is 0 for a three-player player, which has no race play (section "Race play and the
  games row (WP6)" above). Tests: four new tests in `wasm_api_test` (native), and `cpp/wasm/test/threeplayer.mjs`
  (run by `scripts/web/test-wasm.zsh`, section "--- three players"). It plays one full game per jump rule with an engine for each of the three players (node budgets
  200, 300, 400), twice, and checks legality, side order, the end within 300 plies, determinism, unmake, the mismatch errors, and
  `shw_analyse` for three players (all moves, a subset, determinism, and the codes -1, -3 and -5); it loads no race table. On the measured run the adjacent game ended after 127 plies with player 0 winning, the long-hop game
  after 74 plies with player 1 winning.
