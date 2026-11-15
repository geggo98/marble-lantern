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

## CPU SIMD forward pass (WP10)

`src/nn/simd_net.*` ports `VectorNetwork`: vector-extension types (`float __attribute__((vector_size(4 * L)))`), L = `SH_SIMD_LANES`
(NEON 4, AVX2 8, AVX-512 16, overridable with `-DSH_SIMD_LANES=N`), weights repacked into tiles of 2L output channels, 4 cells and 8
accumulators per step, channels padded with zero weights, no allocation in `forward`. The file needs no unsafe region: loads and stores
go through `std::span`, `std::copy_n` and `std::bit_cast`.

- `SimdVariant::Fma` is `simd_net.cpp`, the only file built with `-ffp-contract=fast`; `SimdVariant::NoFma` is `simd_net_nofma.cpp`, which
  includes the same source with the default `-ffp-contract=off` into the namespace `simd_nofma`.
- `scripts/check-fma.sh <simd_net.o> <simd_net_nofma.o>` proves it in the object code (ctest `simd_object_code`, also run by
  `build-gcc12-stub.sh`). Measured with Apple clang 21 and GCC 13.5 on arm64: fused instructions in the first object (11 at `-O1`, 26 at
  `-O3`), none in the second.
- `nn_check --impl simd|simd-nofma [--batch N]` compares with the Java scalar reference under the bound; `nn_test` also compares with the
  Java `VectorNetwork` outputs. The no-allocation test replaces the global `operator new` in `nn_test` only (`tests/alloc_counter.hpp`, the
  one exception of rule R1 in `check-rules.sh`).

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
