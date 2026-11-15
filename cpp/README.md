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
