# Plan: C++ port and performance comparison

**TL;DR:** Port two parts of the project to modern, memory-safe C++23 in a new directory `cpp/`. The first part is the classical player `BEST_SPEC`: engine, linear evaluation with its incremental state, race table reader and alpha-beta search. The second part is the forward pass of the `classic1` network on the CPU (scalar and SIMD), on Metal (local Mac) and on CUDA (one short AWS run).
Equality comes before any timing. The C++ search must reproduce the recorded constants of `BestSpecFingerprintTest`, and every timing binary checks its own node counts or outputs against the Java golden files on every run. The C++ network must reproduce the Java reference outputs within the tolerance of [network](network.md#numeric-conventions).
The main classical row compares Java with the bounds-checked C++ build (`release-hardened`), because the request asks for memory-safe C++; the unchecked build is a second column. The TornadoVM part is split into kernel time, transfers, plan build and compilation, host priors, batch-1 latency, throughput at batch 256 to 1,024, lines of code, build time and binary size.
The plan has 22 work packages and a total time box of 65 agent-hours (about 58 planned without the cuttable race play package). The AWS part is one `g4dn.xlarge` spot run of about 27 minutes, expected cost about 0.13 USD. All C++-suite runs together, retries and a possible rerun included, are capped at 1 USD.
The CUDA host code is built and run on the Mac with gcc 12 before the paid run; on the instance, the riskiest steps (C++ build and CUDA correctness test) run first and stop the instance at once if they fail.

## How to read this plan

| You want | Read |
|---|---|
| What is ported and what is left out | [Scope](#scope) |
| The rules for the C++ code | [Memory-safety rules](#memory-safety-rules), [Java compatibility rules](#java-compatibility-rules) |
| Directory layout, branches, build and test commands | [Layout and build](#layout-and-build) |
| The steps in order, with acceptance criteria | [Work packages](#work-packages) |
| How the numbers are taken and when a difference counts | [Measurement method](#measurement-method) |
| What the TornadoVM breakdown measures | [TornadoVM breakdown](#tornadovm-breakdown) |
| The AWS run | [AWS run](#aws-run) |
| Process, time box, risks, open questions | the sections after the AWS run |
| What the plan review changed | [Review log](#review-log) |

Terms used throughout:

- **Java side:** the existing Gradle modules (`engine`, `tablebase`, `search`, `nn`, `nn-tornado`).
- **Golden files:** files that a Java exporter writes and the C++ tests and benchmarks read: positions, move lists, evaluation values, fingerprint lines, weights, encoded inputs and reference outputs. They are generated, never edited, and never committed (`cpp/golden/` is git-ignored). Each export writes `MANIFEST.tsv` with the SHA-256 of every file; the C++ golden reader checks it.
- **Faithful port:** the C++ code does the same work as the Java code. It uses the same algorithms, the same data structures, the same move order and the same tie-breaking. Idiomatic C++ is fine where it does not change the work. Optimisations beyond the Java code are out of scope; the only exceptions are the GPU variants that [TornadoVM breakdown](#tornadovm-breakdown) names.
- **NCLOC:** non-comment, non-blank lines of code, counted by `cpp/scripts/ncloc.pl` (WP0) for Java and C++ alike.
- **Agent-hour:** one hour of work by one implementation agent, as a rough size.
- **u5 weights:** `Parameters.uniform(config, 5, sqrt(6 / (7C)))`, the generated weights that `GpuFeedBenchmark` uses (seed 5, the initialisation bound for C channels).
- **Anchor rows:** the network cases SMALL and LARGE at batch 256, measured repeatedly to detect drift of the machine during a session.

## Facts this plan relies on

Measured or read on 2026-11-15 on the development Mac (Apple M3 Max, macOS with Darwin 25.6, Apple clang 21.0.0 from the Command Line Tools), unless stated otherwise:

| Fact | How it was checked |
|---|---|
| `clang++ -std=c++23` compiles `<expected>`, `<print>` and `<span>`, with `-D_LIBCPP_HARDENING_MODE=_LIBCPP_HARDENING_MODE_EXTENSIVE` and `-fsanitize=address,undefined` | a test program compiled and ran |
| `-Wunsafe-buffer-usage -Werror` rejects `p[3]` on a raw pointer, and `#pragma clang unsafe_buffer_usage begin/end` exempts a marked region | a test file |
| Metal shaders compile at run time from MSL source (`newLibraryWithSource:options:error:`) without Xcode; `simdgroup_float8x8` and `simdgroup_multiply_accumulate` compile; `MTLCompileOptions.mathMode = MTLMathModeSafe` is accepted | an Objective-C++ test program with `-fobjc-arc -framework Metal -framework Foundation` ran a kernel on the M3 Max |
| Metal.framework, Accelerate.framework and CommonCrypto are in the Command Line Tools SDK | directory listing |
| Apple clang has no NVPTX target (`clang++ -print-targets`) | command output |
| No cmake, ninja, clang-tidy, nvcc or Xcode on the Mac; Nix and devenv are present | environment check |
| `gcc12` is **removed** from current nixpkgs (evaluation throws "gcc12 has been removed from Nixpkgs"). The `nixos-25.05` branch has `gcc12` 12.4.0 for `aarch64-darwin`, and both the wrapper and the compiler are in the public binary cache. Ubuntu 22.04's `g++-12` is 12.3, so both use the libstdc++ of GCC 12 | `nix eval` and `nix path-info --store https://cache.nixos.org` |
| libstdc++ 12 has `<expected>` and `<span>` under `-std=c++23`; it has no `<print>`, no `std::format` and no `<mdspan>` | GCC 12 release notes (to be confirmed by the WP0 gcc spike) |
| CUDA 13.0 removed offline compilation for compute capabilities below 7.5 (Maxwell, Pascal, Volta); Turing (`sm_75`, the T4) is on the supported side. The 2026-11-15 runs also compiled CUDA C for the T4 at run time with the CUDA 13.0 NVRTC | NVIDIA CUDA 12.9 and 13.0 release notes and the CUDA features archive; `bootstrap.log` of the 2026-11-15 runs. `build-cuda.sh` checks `nvcc --list-gpu-arch` for `compute_75` before compiling (WP12) |
| The CUDA AMI of the earlier runs has CUDA 13.0 (`/usr/local/cuda-13.0/.../libnvrtc.so.13`), driver 595.112.07. `nvcc`, `compute-sanitizer` and `nvidia-smi` are not recorded in the result files | `bootstrap.log` of the 2026-11-15 runs; the new run must find and log all three, and `compute-sanitizer` must not block the run |
| `aws-spot.sh` already has `preflight` (spot quota of the instance family, read-only), `--on-demand`, `--budget-usd` (per run, default 2.50) and exit code 3 for a pre-flight finding. It does **not** write the cost of a run into `cloud-results/` (the cost line goes to the terminal only) | `scripts/cloud/aws-spot.sh` header and code |
| `aws-spot.sh` reads `AWS_PROFILE` and `AWS_REGION` from the environment, else from `.env` in the repository root of the checkout it runs from | `scripts/cloud/aws-spot.sh` lines 44 to 50 |
| `data/` (with `data/loop4/gen-0011/candidate.safetensors`, LARGE `classic1`, generation 11) and `.env` are git-ignored; a new worktree has neither. The TornadoVM installation of `scripts/setup-tornado.sh` is also local to a checkout | `.gitignore`, `git check-ignore` |
| `tablebase/tables/rows1.bin` (242 MB) and `rows2.bin` (12 MB) are Git LFS files and excluded from the AWS tarball; a new worktree may hold only pointer files until `git lfs pull` | `.gitattributes`, `aws-spot.sh` |
| `VectorNetwork` always uses fused multiply-add (`a00 = w0.fma(v0, a00)`, `nn/src/main/java/sternhalma/nn/VectorNetwork.java:312`) | source |
| The tolerance 1e-4 + 1e-3 · \|ref\| holds for `VectorNetwork` against `Network` with the init bound √(6 / 7C), which is the u5 weights' bound (largest error 0.031 of the bound for SMALL, 0.042 for LARGE). It does not hold for bound 0.5 (up to 1.03 times the bound). For `TornadoNetwork` on u5 weights no figure is recorded | `docs/network.md`, section "Numeric conventions" |
| NCLOC of the Java code to port: classical part 4,546 (engine, read-only tablebase, search core, evaluators, race play); `nn` inference 1,506 (of which `Safetensors` 543); TornadoVM inference path 1,372 (`TornadoNetwork`, `KernelsHexConv`, `KernelsHexConvLayer`, `OpsKernels`, `OpsHeads`, `OpsParameters`, `KernelsDense`, `DeviceMath`, `DeviceBackend`, `TornadoSupport`, `OpsCopy`, `ExecutionProfile`) | perl counter over the files |

Not yet a fact, checked by a work package: TornadoVM's NVRTC options on CUDA (whether `-fmad` or `--use_fast_math` is passed) and the flags of its Metal compilation (WP8, step 6).

## Scope

### What is ported

| Part | Java classes | C++ size (estimate, without tests) | Why |
|---|---|---|---|
| Engine, two players | `Geometry`, `Layout`, `RuleSet` (two players, default rules), `Outcome`, `Move`, `Corner`, `Position` (Zobrist keys from `SplittableRandom`) | 800 | everything the search touches |
| Race table, read only | `Rows`, `Ranking`, `RaceCounts`, `RaceTable` (load and lookup), `TableFile` (header parse) | 350 | `racelin1-r3` and race play read the rows>=1 table |
| Evaluation | `LinearEvaluator` (12 and 13 features, `GainSearch`), `LinearWeights` (R1, ROUND3, SCALE), `IncrementalLinear`, `JumpGraph`, `RaceEvaluator`, `RaceAdjudication.passed` | 950 | evaluator of R2 (`linear-r1`) and of R3 and `BEST_SPEC` (`racelin1-r3`) |
| Search | `AlphaBeta` with every `SearchOptions` field, `SearchResult`, `Scores`, `PlayerSpec` (only the keys used), `AlphaBetaPlayer` | 850 | `BEST_SPEC` options: `pvs,killers,history,lmr=2,asp,ttrep,mate,partial,fut=1,futk=20,fut2=1` |
| Race play (cuttable, WP6) | `RaceMove`, `Solitaire` (beam plan) | 650 | `race=1,racerows=1`; needed only for the R3 and BEST game parts of the fingerprint and for the games timing row |
| Fingerprint and bench | `Fingerprint`, the timing part of `Bench` | 450 | equality proof and timing |
| Network core | `NetworkConfig`, `PlaneSet` (as an enum), `Parameters` (load only), `Safetensors` (read only, all rejection rules), `HexGrid`, `ReferenceOps`, `Network`, `ResidualPriors` | 900 | scalar reference and file reader |
| CPU SIMD | `VectorNetwork` | 400 | counterpart of the Vector API path |
| Metal backend | the kernels of `KernelsHexConv` (`naive`, `tiled`, `simdgroup`), `OpsKernels` (`policyHead`, `valueHead`), `TornadoNetwork` (buckets, variant rule, host priors) | 950 (MSL 500, Objective-C++ 450) | counterpart of TornadoVM on Metal |
| CUDA backend | the same kernels without `simdgroup` (`naive`, `tiled`, heads), a thin host layer in `.cu`, the host logic in `.cpp` | 850 (kernels 450, `.cu` host 200, `.cpp` adapter 200) | counterpart of TornadoVM on CUDA |
| Benchmarks and tools | `nn_bench`, `nn_check`, `search_bench`, `fingerprint`, golden reader | 950 | |
| Tests | per module | 1,800 | |

Total: about 9,900 C++ lines including tests (about 9,250 without race play). Java additions: two golden exporters (about 350 lines), one cross-language benchmark (about 300 lines) and two Gradle tasks that print a run command. Script additions: about 350 lines (the retry runner is a Python script) plus stub tests.

### What is left out, and why the comparison survives it

| Left out | Why it does not hurt the comparison |
|---|---|
| Building race tables (`RetrogradeBuilder`, `TableValidation`) | The search only reads the tables. C++ loads the committed LFS files and checks their SHA-256. |
| Three-player rules | Alpha-beta supports two players only, in Java too, and every fingerprint and benchmark plays two players. The C++ `RuleSet` accepts only two players and the default rules; any other value is a load error. |
| `leaveGoal = ALLOWED` | No measured spec uses it. The C++ `RuleSet` rejects it. |
| `DistanceEvaluator`, `linear-r2` (`LinearWeights.ROUND2`) | No fingerprint constant and no timing row uses them. |
| MCTS, self-play, training, `Encoder`, `ClassicalPlanes` | The forward benchmarks of both languages read pre-encoded inputs from a golden file, as `TornadoNetworkBenchmark` does. Encoding is host work outside the device path. |
| The legal-move softmax of `Evaluator` | It runs in the game threads, not in the device path. The host work that `TornadoNetwork` does (the residual priors) is ported. |
| `GpuFeedBenchmark` in C++ | It needs encoding and virtual-thread games. Its device thread spends 91 % (SMALL) to 98 % (LARGE) of its time in `plan.execute()` on the T4 ([cloud](cloud.md#gpu-feed)), so the forward benchmark captures what matters. The Java feed still runs on the same instance as context. |
| The training kernels | Not part of the request. |
| Tensor cores (CUDA WMMA, FP16) | The Java side computes in FP32; the T4 has no TF32. Lower precision would change the results beyond the tolerance, and the comparison would no longer be like for like. |
| Java `kernelBenchmark` on the T4 | The C++ per-kernel times on the T4 come from `cudaEvent` at no extra cost and are reported. Running the Java kernel benchmark on the instance would add about 4 minutes of paid time for rows that the Mac table already covers for the same kernel code. Stated in the document as a deliberate gap. |

## Memory-safety rules

The C++ code follows these rules. Each rule names the tool that enforces it.

| Rule | Enforced by |
|---|---|
| C++23 (`-std=c++23`), RAII for every resource | review, clang-tidy `cppcoreguidelines-owning-memory` |
| No `new`, `delete`, `malloc`, `free`, `realloc` outside the GPU wrappers; ownership through `std::unique_ptr`, `std::vector`, `std::array` | clang-tidy `cppcoreguidelines-no-malloc`, `cppcoreguidelines-owning-memory`; grep check in `cpp/scripts/check-rules.sh` |
| No raw-pointer arithmetic or indexing; buffers are `std::span`, `std::array` or `std::vector` | `-Wunsafe-buffer-usage -Werror` (clang). Exceptions only inside `#pragma clang unsafe_buffer_usage begin/end` regions in the files listed in [Unsafe zones](#unsafe-zones); `check-rules.sh` fails on such a pragma in any other file |
| No C arrays, no C-style casts, no `reinterpret_cast` | `-Wold-style-cast`, clang-tidy `cppcoreguidelines-avoid-c-arrays`, `cppcoreguidelines-pro-type-reinterpret-cast` |
| Bytes are read with `std::copy_n` from a `std::span<const std::byte>` into a `std::array<std::byte, N>`, then `std::bit_cast` to the value type (`le_bytes.hpp`). `std::memcpy` only where the WP0 spike shows that `-Wunsafe-buffer-usage` accepts it; otherwise it stays inside an unsafe zone | WP0 spike, `-Wunsafe-buffer-usage`, `check-rules.sh` (fails on `memcpy` outside the unsafe-zone files) |
| Optional values are `std::optional`, errors from file readers are `std::expected<T, Error>`; no exceptions across the GPU interfaces | review |
| Signed overflow never happens on purpose: Java's wrapping arithmetic (checksums, hashes) uses `std::uint64_t` | UBSan (`-fsanitize=undefined`, `-fno-sanitize-recover=all`) in every test run |
| Bounds checks in debug and in a hardened release build | libc++ `_LIBCPP_HARDENING_MODE_DEBUG` (debug preset) and `_LIBCPP_HARDENING_MODE_FAST` (`release-hardened`); `-D_GLIBCXX_ASSERTIONS` with libstdc++ (gcc builds) |
| Every test runs under AddressSanitizer and UndefinedBehaviorSanitizer | the `debug-asan` preset; `ctest --preset debug-asan` is the gate of every work package |
| Warnings are errors | `-Wall -Wextra -Wpedantic -Wshadow -Wconversion -Wno-sign-conversion -Wnon-virtual-dtor -Wold-style-cast -Wcast-align -Wnull-dereference -Wimplicit-fallthrough -Werror`, plus `-Wunsafe-buffer-usage` on clang only |
| Floating-point contraction is off everywhere (`-ffp-contract=off`), with one named exception: `src/nn/simd_net.cpp` is built with `-ffp-contract=fast` for its FMA variant (see WP10) | `check-rules.sh`: fails if `-ffp-contract=off` is missing from `CMakePresets.json` or `build-cuda.sh`, and if `-ffp-contract=fast` appears for any file other than `simd_net.cpp` |
| Static analysis | clang-tidy (from nixpkgs `clang-tools`, see WP0) with `bugprone-*`, `cppcoreguidelines-*`, `clang-analyzer-*`, `performance-*`, `modernize-*`; the exclusions are in `cpp/.clang-tidy`, each with a one-line reason. If clang-tidy cannot parse the Apple SDK headers, the tidy step runs with `--extra-arg=-isysroot$(xcrun --show-sdk-path)`; if that fails too, the risk table records it and the step is skipped (not hidden) |
| GPU memory checks | CUDA: `compute-sanitizer --tool memcheck` on the correctness test (AWS, non-blocking, its absence is logged). Metal: the correctness test runs once with `MTL_DEBUG_LAYER=1` and `MTL_SHADER_VALIDATION=1` (best effort; the result is recorded either way) |
| Every source that the Linux build compiles also builds on the Mac under Apple clang with all of the rules above | the `debug-asan` and `tidy` presets build `src/core`, `src/nn` and the CUDA adapter with the stub backend (WP12) |

### Unsafe zones

This list is complete for the plan; WP0's spike can add entries, and WP18 publishes the list as built. Anything not listed here must pass `-Wunsafe-buffer-usage`.

| Zone | File | Why it is unsafe | Containment |
|---|---|---|---|
| Metal buffer contents | `cpp/src/gpu/metal/metal_net.mm` | `[buffer contents]` returns a raw pointer | wrapped at once into `std::span<float>` of `[buffer length] / sizeof(float)` elements; nothing else touches the pointer |
| Objective-C++ | `cpp/src/gpu/metal/metal_net.mm` | Objective-C objects; `-Wunsafe-buffer-usage` does not cover them | compiled with `-fobjc-arc` (reference counting by the compiler); the public header `metal_net.hpp` is plain C++ with a pimpl and no Objective-C type |
| MSL kernels | `cpp/src/gpu/metal/kernels.metal` | no bounds checks on the device (like TornadoVM pitfall 4 in [tornado](tornado.md#pitfalls)) | NaN-filled output buffers in the tests (a skipped element stays NaN), every shape test of `KernelsHexConvTest` repeated, shader validation run |
| CUDA host layer | `cpp/src/gpu/cuda/cuda_net.cu` | `cudaMalloc`/`cudaFree`, `cudaHostAlloc`, raw device pointers; built by nvcc and g++ only, so `-Wunsafe-buffer-usage` never sees it | one RAII class `DeviceBuffer<T>` with deleted copy, `std::span` views on host memory, at most about 200 lines; `compute-sanitizer memcheck` on AWS |
| CUDA kernels | `cpp/src/gpu/cuda/kernels.cuh` | raw pointer indexing is the only way to write a kernel | same tests as Metal; `compute-sanitizer` |
| SIMD loads and stores | `cpp/src/nn/simd_net.cpp` | vector-extension loads from `float` data | loads through `std::copy_n` or `std::memcpy` from a `std::span` sub-range whose size is checked once per tile; if that costs measurable speed, the inner loop becomes a marked unsafe region and the region is listed here |
| Byte reader | `cpp/src/core/le_bytes.hpp` | only if the WP0 spike shows that `std::copy_n`/`std::bit_cast` on spans is flagged or slow and `std::memcpy` is needed | one function per width, each a marked region of a few lines, with size checks before it |
| Command-line arguments | `cpp/apps/args.hpp` | `argv[i]` is indexing a raw pointer | converted once into `std::vector<std::string_view>` inside one marked region; `main` touches nothing else |
| SHA-256 | `cpp/src/tablebase/sha256.cpp` | CommonCrypto `CC_SHA256_Update` takes a pointer and a length | called with `span.data()` and `span.size()` in one marked region; the portable fallback (Linux, not used by the AWS build) is span-only |

What no rule enforces, stated plainly:

- **Lifetime.** A `std::span` or `std::string_view` that outlives its buffer is not caught at compile time by any flag used here. It is caught only at run time, by AddressSanitizer, and only on the paths the tests exercise.
- **Leaks.** LeakSanitizer is not available with AddressSanitizer on macOS arm64; leaks are not checked on the Mac. RAII is the only defence.
- **The Linux build** (gcc 12 on the instance) has only `-D_GLIBCXX_ASSERTIONS`: no `-Wunsafe-buffer-usage`, no sanitizers. That is why the same sources must pass every rule on the Mac.
- **GPU device code** has no bounds checks at all; the tests and the sanitizers named above are the only check.

There is **no** `mmap`: the race table is read into a `std::vector<std::uint8_t>` (242 MB), as the Java code reads it into a `byte[]`.

## Java compatibility rules

These rules decide whether node counts match. Put them in `cpp/src/core/java_compat.hpp` with tests in `cpp/tests/java_compat_test.cpp`.

| Java behaviour | C++ rule |
|---|---|
| `Math.round(double)` rounds half up and returns 0 for NaN; `Math.round(0.49999999999999994)` is 0 | `java_round(double) -> std::int64_t`, not `std::round` (which rounds half away from zero) and not `floor(x + 0.5)` (wrong for 0.49999999999999994). Test cases: 2.5 → 3, −2.5 → −2, −0.5 → 0, 0.49999999999999994 → 0, NaN → 0, ±1e300 → saturated. `AlphaBeta.terminalScore` and `LinearEvaluator.score` use it. |
| `(int) double` saturates, NaN gives 0 | `java_d2i(double)`; never a plain `static_cast` from an out-of-range double (undefined behaviour in C++). |
| `long` and `int` arithmetic wraps | checksums (`h = h * 31 + x`) in `std::uint64_t`; a Java `int` added to a `long` is sign-extended first. Same for hash mixing. |
| `SplittableRandom(seed).nextLong()` | port exactly: `seed += 0x9e3779b97f4a7c15`, then `z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9`, `z = (z ^ (z >>> 27)) * 0x94d049bb133111eb`, `z ^ (z >>> 31)`, all unsigned. The keys of `Position` come from seed `0x5E7A11A100000001`; the golden file holds the 366 keys and the test compares them. |
| `>>>`, `byte` | unsigned shift on `std::uint64_t` / `std::uint32_t`; Java `byte` is `std::int8_t` (the owner array holds −1 for empty). |
| `double` expressions are evaluated in order, without fused multiply-add | compile everything with `-ffp-contract=off` and without `-ffast-math`. **Apple clang contracts `a * b + c` to an FMA by default on arm64**, which would change `LinearEvaluator` scores in the last bit and therefore node counts. A test pins this: `linear_eval_test` compares every golden value bit for bit. |
| `Math.max(float, 0f)` keeps NaN and turns −0 into +0 | `java_max(float, float)`; not `std::max` (which keeps −0). |
| `Math.tanh` | `std::tanh` may differ in the last bit; values are compared within 1 ulp (network values only; the search uses no transcendental function). |
| `int` `/` and `%` | truncation toward zero, as in C++; no change. |
| Iteration order | every loop keeps the Java order (move generation order, piece list order, `pick` selection order); `std::sort` is never used where Java uses a stable or hand-written order. |

## Layout and build

### Branches and worktrees

Three worktrees, so that the two implementation tracks never share a Git index or a working tree:

| Worktree | Branch | Created | Used for |
|---|---|---|---|
| `../stern-halma-cpp` | `exp/cpp` | WP0, from `main` | the skeleton, the merges, the AWS run (WP14, WP15), reviews and documentation |
| `../stern-halma-cpp-classical` | `exp/cpp-classical` | end of WP0, from `exp/cpp` | WP1 to WP7 |
| `../stern-halma-cpp-nn` | `exp/cpp-nn` | end of WP0, from `exp/cpp` | WP8 to WP13 |

```sh
git worktree add ../stern-halma-cpp -b exp/cpp main
# after WP0 is committed on exp/cpp:
git worktree add ../stern-halma-cpp-classical -b exp/cpp-classical exp/cpp
git worktree add ../stern-halma-cpp-nn        -b exp/cpp-nn        exp/cpp
```

WP0 creates every CMake target, every source file (empty or with a stub) and every `devenv.nix` and `.gitignore` entry that a later package needs. After WP0 the classical track touches only `cpp/src/{engine,tablebase,search}`, `cpp/apps/{fingerprint_main,search_bench}.cpp`, `cpp/tests/{engine,tablebase,eval,search}_test.cpp`, `search/` and `cpp/results/mac-classical-*`. The network track touches only `cpp/src/{nn,gpu}`, `cpp/apps/nn_*.cpp`, `cpp/tests/{nn,metal,cuda,cuda_adapter}_test.*`, `nn/`, `nn-tornado/`, `cpp/scripts/{build-cuda,ptx-check,merge-bench}*` and `cpp/results/mac-nn-*`. If a track needs a change in a shared file (`cpp/CMakeLists.txt`, `cpp/CMakePresets.json`, `cpp/src/core/`, `devenv.nix`), it stops and the change is made on `exp/cpp` and merged into both branches.

Merges into `exp/cpp`: `exp/cpp-nn` at the start of WP14 (the AWS run archives `HEAD` of `exp/cpp`); `exp/cpp-classical` after WP7 (and again after WP6, if done); both before WP17. Nothing goes to `main` until the owner decides ([Open questions](#open-questions)).

### Local data that a worktree lacks

A worktree has no git-ignored files. WP0 handles each one:

| Missing | Handling |
|---|---|
| LFS tables `rows1.bin`, `rows2.bin` | `git lfs pull` in each worktree, then `shasum -a 256` of both files against the constants of WP3 |
| `data/` (real gen-11 weights for `--real`) | the environment variable `STERNHALMA_DATA_DIR` points to the `data/` directory of the main checkout; exporters and benchmarks read it (default `<repo>/data`). No absolute path is written into the repository |
| Race tables for C++ | `STERNHALMA_TABLE_DIR` (default `<repo>/tablebase/tables/`) |
| TornadoVM installation | `scripts/setup-tornado.sh` in `../stern-halma-cpp-nn` and in `../stern-halma-cpp` |
| `.env` (AWS profile and region) | not copied. `AWS_PROFILE` and `AWS_REGION` are exported in the shell that starts the AWS run; `aws-spot.sh` reads the environment first |

### Directory layout

```text
cpp/
  CMakeLists.txt  CMakePresets.json  .clang-tidy
  cmake/          Warnings.cmake  Hardening.cmake  Sanitizers.cmake  EmbedText.cmake
  src/core/       java_compat.hpp  splittable_random.hpp  le_bytes.hpp  check.hpp (test harness)  json_min.{hpp,cpp}
                  golden.{hpp,cpp} (golden reader: MANIFEST.tsv check, STERNHALMA_GOLDEN_REQUIRED)
  src/engine/     geometry  layout  rules  outcome  move  position   (.hpp/.cpp each)
  src/tablebase/  rows  ranking  race_table  sha256 (CommonCrypto on macOS, small portable fallback elsewhere)
  src/search/     scores  options  player_spec  evaluator.hpp  linear_weights  linear_eval
                  incremental_linear  jump_graph  race_eval  race_adjudication  solitaire  race_move
                  alpha_beta  alpha_beta_player  fingerprint
  src/nn/         config  safetensors  parameters  gather  reference_net  simd_net  priors  golden_io  buckets
                  cuda_backend.{hpp,cpp} (C++23 adapter: Parameters -> raw arrays, host priors, timing)
  src/gpu/metal/  metal_net.hpp (plain C++)  metal_net.mm  kernels.metal (embedded as a raw string at build time)
  src/gpu/cuda/   cuda_net.hpp (C++20, standard headers only)  cuda_net.cu  kernels.cuh  cuda_net_stub.cpp
  apps/           args.hpp  fingerprint_main.cpp  search_bench.cpp  nn_check.cpp  nn_bench.cpp
  tests/          one test executable per module (*_test.cpp, metal_test.mm, cuda_test.cpp, cuda_adapter_test.cpp)
  scripts/        ncloc.pl  check-rules.sh  scan-sensitive.pl  build-cuda.sh  build-gcc12-stub.sh  ptx-check.sh
                  bench-mac.sh  merge-bench.pl
  golden/         generated by the Java exporters (git-ignored)
  results/        committed result files (model and OS only, see the disclosure rule)
```

The test harness is project-owned (`check.hpp`, about 100 lines: `CHECK`, `CHECK_EQ`, test registration, exit code), so the AWS build needs no test framework. Each test executable is one `ctest` test.

### Toolchain

| Where | Compiler | Build |
|---|---|---|
| Mac | Apple clang 21 from the Command Line Tools (`/usr/bin/clang++`), Objective-C++ for Metal | CMake and Ninja from nixpkgs through `devenv.nix` (`packages = [ pkgs.cmake pkgs.ninja pkgs.llvmPackages.clang-tools ]`; `stdenv` stays `stdenvNoCC`, so `c++` stays Apple clang) |
| Mac, Linux-compatibility build | `g++` 12.4 from `nixos-25.05` (`nix shell github:NixOS/nixpkgs/nixos-25.05#gcc12`, ad hoc, not in `devenv.nix`) | `cpp/scripts/build-gcc12-stub.sh`: the same file list and flags as `build-cuda.sh`, with `cuda_net_stub.cpp` in place of `cuda_net.cu` |
| AWS (Ubuntu 22.04) | `g++-12` (apt, 12.3) for all `.cpp`; `nvcc -ccbin g++-12` of the CUDA 13.0 toolkit for the `.cu` file | `cpp/scripts/build-cuda.sh`, one shell script with explicit command lines: no CMake on the instance, fewer things that can fail on paid time |

The CUDA split, which keeps nvcc away from C++23:

- `cuda_net.cu` contains the kernels (through `kernels.cuh`) and a thin host layer: device buffers, streams, events, launches. It includes only `cuda_net.hpp`, `kernels.cuh` and standard and CUDA headers, never a project header. It compiles with `nvcc -std=c++20 -ccbin g++-12`.
- `cuda_net.hpp` is C++20 with standard headers only (`<cstdint>`, `<span>`, `<memory>`, `<string>`): a class `CudaNet` that takes the network shape and the packed weights as `std::span<const float>` and runs `forward_raw(input, batch, logits, values, Timings&)`.
- `cuda_backend.cpp` (C++23, `g++-12 -std=c++23`) adapts `Parameters` to `CudaNet`, runs the host priors and is what `nn_check`, `nn_bench` and the tests call.
- `cuda_net_stub.cpp` implements `cuda_net.hpp` without CUDA: `CudaNet::create` returns an error "no CUDA device in this build". It lets the Mac build and run everything except the device code.

Language rule for the code that the Linux build compiles (`src/core`, `src/nn`, `src/gpu/cuda`, `apps/nn_*`, `tests/cuda_test.cpp`, `tests/nn_test.cpp`): C++23 language mode, only library features that libstdc++ 12 has. In particular no `std::print`, no `std::format` (use `std::to_chars` and a small `format.hpp`), no `std::mdspan`. `std::expected` and `std::span` are fine. The gcc-12 build on the Mac (WP12) proves the rule before the paid run. The engine and search code runs on the Mac only and may use all of C++23 that Apple clang supports.

### Presets

| Preset | Flags | Use |
|---|---|---|
| `debug-asan` | `-O1 -g -fsanitize=address,undefined -fno-sanitize-recover=all`, libc++ hardening `DEBUG` | every test run, the gate of every work package |
| `release` | `-O3 -DNDEBUG -mcpu=native`, hardening `NONE` | timing: the "cost of the checks" column |
| `release-hardened` | `release` plus hardening `FAST` | timing: the main C++ row |
| `tidy` | `release` plus `CMAKE_CXX_CLANG_TIDY` | static analysis |

All presets: `-ffp-contract=off` (except `simd_net.cpp`, see WP10), no LTO, no PGO, `STERNHALMA_GOLDEN_REQUIRED=1` in the `ctest` environment once the golden exporter of the track exists (WP1, WP8). `-mcpu=native` (Mac) and `-march=native` (Linux) are used for timing, because the JVM's JIT also uses the CPU it runs on.

Commands (inside `devenv shell`, in `cpp/`):

```sh
cmake --preset debug-asan && cmake --build --preset debug-asan && ctest --preset debug-asan
cmake --preset release-hardened && cmake --build --preset release-hardened
scripts/check-rules.sh                      # rule checks of the table above
scripts/scan-sensitive.pl $(git diff --cached --name-only)   # before every commit
```

## Work packages

The order respects the dependencies. After WP0 the classical track (WP1 to WP7, then WP6) and the network track (WP8 to WP13) run in their own worktrees. Each package ends with: `ctest --preset debug-asan` green with `STERNHALMA_GOLDEN_REQUIRED=1` (from WP1 or WP8 on), `scripts/check-rules.sh` green, `scripts/scan-sensitive.pl` clean on the staged files, one commit on the track's branch with a conventional message (`feat(cpp): ...`, `test(cpp): ...`).

**Measurement packages and the other track.** WP7, WP13 and the Mac part of WP6's games row measure. They run only while the other track is paused, and while no other agent, build or training runs; the owner is asked first. This is part of their acceptance: `bench-mac.sh` records `pgrep -fl 'gradle|java|clang|ninja|cmake'` (its own processes excluded) before and after, and a session with a foreign process is invalid.

### Overview

| ID | Title | Depends on | Estimate |
|---|---|---|---|
| WP0 | Worktrees and local data, devenv packages, full CMake skeleton, presets, rules and scan scripts, spikes (unsafe-buffer, gcc 12) | none | 3 h |
| WP1 | Java golden exporter, classical | WP0 | 1.5 h |
| WP2 | Engine in C++ (two players) | WP1 | 2.5 h |
| WP3 | Race table reader | WP2 | 2 h |
| WP4a | `LinearEvaluator` and weights, bit-exact | WP3 | 2.5 h |
| WP4b | `IncrementalLinear`, `JumpGraph`, `RaceEvaluator` | WP4a | 3 h |
| WP5 | Alpha-beta, player spec, fingerprint positions and R2 games | WP4b | 4 h |
| WP7 | Search benchmark and Mac measurement, positions | WP5 | 2.5 h |
| WP6 | Cuttable: race play, remaining game fingerprints, games row | WP7 | 4 h |
| WP8 | Java golden exporter, network; Java cross-language benchmark; tolerance and TornadoVM-flag checks | WP0 | 3.5 h |
| WP9 | Network core: safetensors, parameters, scalar reference, priors | WP8 | 3.5 h |
| WP10 | CPU SIMD forward pass, FMA and no-FMA | WP9 | 3 h |
| WP11a | Metal: `naive` conv, heads, whole network within the bound, `copy` mode | WP9 | 3 h |
| WP11b | Metal: `tiled`, `simdgroup`, `shared` mode, phase timing, instrumented mode | WP11a | 3.5 h |
| WP12 | CUDA backend, gcc-12 stub build on the Mac, `build-cuda.sh` with stub tests, PTX check | WP11a | 4 h |
| WP13 | Network benchmark and Mac measurement | WP10, WP11b | 3 h |
| WP14 | Merge `exp/cpp-nn`; AWS scripts: suite, cost file, exit 4, retry runner, stub tests | WP12, WP13 | 4 h |
| WP15 | The AWS run | WP14 | 1.5 h |
| WP16 | Optional, only after the owner's explicit yes: replay of the TornadoVM-generated kernels | WP13 | 3 h |
| WP17 | Implementation reviews and fixes | WP7, WP15 (WP6, WP16 if done) | 4 h |
| WP18 | Documentation `docs/cpp-comparison.md` | WP17 | 3 h |

Total: about 58 agent-hours without WP6 and WP16, 62 with WP6. The time box and the cut order are in [Time box and stop rules](#time-box-and-stop-rules).

### WP0: worktrees, local data, skeleton, spikes

Files: `devenv.nix` (packages line), `.gitignore` (`/cpp/golden/`, `/cpp/build/`), every file under `cpp/` from the layout (empty, or a stub that compiles), `cpp/scripts/{ncloc.pl,check-rules.sh,scan-sensitive.pl}`, `cpp/src/core/{check.hpp,java_compat.hpp,splittable_random.hpp,le_bytes.hpp,golden.hpp,golden.cpp}`, `cpp/apps/args.hpp`, `cpp/tests/java_compat_test.cpp`.

Steps:

1. Create `../stern-halma-cpp` on `exp/cpp` ([Branches and worktrees](#branches-and-worktrees)). Run `git lfs pull` and check the SHA-256 of `rows1.bin` and `rows2.bin` against the constants of WP3. Run `scripts/setup-tornado.sh`. Export `STERNHALMA_DATA_DIR` and `STERNHALMA_TABLE_DIR` in the session as described in [Local data that a worktree lacks](#local-data-that-a-worktree-lacks).
2. Add `cmake`, `ninja` and `llvmPackages.clang-tools` to `devenv.nix`. Check that `which c++` inside `devenv shell` is still `/usr/bin/c++`.
3. Write `CMakeLists.txt` with **every** library, app and test target of the layout (`sh_core`, `sh_engine`, `sh_tablebase`, `sh_search`, `sh_nn`, `sh_metal`, `sh_cuda_stub`; apps; tests), the presets, the warning and hardening modules. `sh_metal` is built only on Apple (`if(APPLE)`), with `-fobjc-arc` for `.mm`. `sh_cuda_stub` builds `cuda_net_stub.cpp` and `cuda_backend.cpp`. Later packages fill files; they add no target.
4. `check-rules.sh`: fails on `new `/`delete `/`malloc(`/`free(` outside `src/gpu/`; on `memcpy` and on `unsafe_buffer_usage` pragmas outside the files of [Unsafe zones](#unsafe-zones); on `-ffast-math` anywhere; if `-ffp-contract=off` is missing from `CMakePresets.json` or `build-cuda.sh`; if `-ffp-contract=fast` appears for any file other than `simd_net.cpp`.
5. `scan-sensitive.pl <files>`: the scan of [Disclosure and sensitive data](#disclosure-and-sensitive-data). It prints the number of lines it read per file and exits 1 on a hit; `scan-sensitive.pl --self-test` feeds it one planted example per pattern and fails unless every one is found.
6. `ncloc.pl <files>`: the counter of [Facts](#facts-this-plan-relies-on); prints per file and total.
7. `golden.cpp`: opens `cpp/golden/MANIFEST.tsv`, checks the SHA-256 of a requested file, and on a missing file skips with a message, or fails if `STERNHALMA_GOLDEN_REQUIRED=1` is set.
8. `java_compat_test`: the cases of [Java compatibility rules](#java-compatibility-rules); the `SplittableRandom` part reads the golden keys of WP1 through `golden.cpp`.
9. **Unsafe-buffer spike** (30 minutes): compile, with the real flags, a scratch file that uses `std::memcpy` from a span, `std::copy_n` plus `std::bit_cast`, `argv[i]`, `std::span(ptr, n)`, `std::string_view(argv[i])` and `CC_SHA256_Update`. Record which ones `-Wunsafe-buffer-usage` (including `-Wunsafe-buffer-usage-in-libc-call`) flags. Adjust the unsafe-zone list and `le_bytes.hpp` to the result (the default is `std::copy_n` plus `std::bit_cast`).
10. **gcc-12 spike** (30 minutes): `nix shell github:NixOS/nixpkgs/nixos-25.05#gcc12 -c g++ -std=c++23 -O2 -D_GLIBCXX_ASSERTIONS -ffp-contract=off` on `src/core` and a file that uses `std::expected`, `std::span` and `std::bit_cast`. If gcc 12 does not work on the Mac, record it, and WP12 falls back to the newest working gcc from nixpkgs with `check-rules.sh` rejecting `<print>`, `<format>`, `<mdspan>` and `<generator>` in the Linux file list.
11. Commit on `exp/cpp`; create the two track worktrees; run `git lfs pull` in each and `scripts/setup-tornado.sh` in `../stern-halma-cpp-nn`.

Acceptance: `./gradlew --no-build-cache :search:test --tests '*BestSpecFingerprintTest'` is green in `../stern-halma-cpp` and in `../stern-halma-cpp-classical`; all presets configure and build on the Mac; `ctest --preset debug-asan` runs `java_compat_test` (skipping the golden part); a deliberately added `int* p; p[1]` in a scratch file fails the build (then removed); `scan-sensitive.pl --self-test` passes; `ncloc.pl` reproduces the 1,372 NCLOC of the TornadoVM inference path; the two spike results are written into `cpp/README.md` (created here; a few lines per spike), and the unsafe-zone list is adjusted to the first one.

### WP1: Java golden exporter, classical

Files: `search/src/test/java/sternhalma/search/GoldenExport.java`, Gradle task `goldenExport` in `search/build.gradle.kts` (`JavaExec`, test runtime classpath, `workingDir = rootDir`, argument: output directory, default `cpp/golden`), Gradle task `printRunCommand` in `search/build.gradle.kts` (writes the `java` command line of `bench`, with JVM arguments and classpath, to `cpp/build/java-search.cmd`; see [Classical measurement](#classical-measurement)).

The exporter writes text files (tab-separated, one item per line, so a failing C++ test can print the first differing line and `diff` works):

| File | Content |
|---|---|
| `zobrist.tsv` | the side keys and the piece keys generated with the same `SplittableRandom` sequence as `Position`, plus `Position.initial(RuleSet.defaults(2)).hash()` as a cross-check |
| `geometry.tsv` | per cell: q, r, s, the 6 neighbours, the 6 jump landings, corner; `Layout` distance and allowed flag per player for 2 players |
| `positions.tsv` | 2,000 positions of seeded random playouts (two players, default rules, plies 0 to 200, seed fixed in the file header): the move list from the initial position, side to move, ply, hash, the legal moves **in generator order**, the outcome (or none), and `evaluate()` of `linear-r1`, `linear-r3` and `racelin1-r3`, plus `futilitySafe` of `racelin1-r3` at depth 1 and 2 |
| `reference.tsv` | the 64 move lists of `ReferencePositions.moveLists()` and the 2 full-game openings |
| `fingerprint-r2.txt`, `fingerprint-r3.txt`, `fingerprint-best.txt` | the output of `Fingerprint.main` for each spec: every per-position line (with node count and move), the game lines and the three checksums |
| `race-counts.tsv` | for the passed positions of `positions.tsv` and of the fingerprint games: the rows>=1 table count of both sides (−1 if not covered) |
| `race-moves.tsv` | for the same passed positions: `new RaceMove(RaceTable.rows1(), 1).choose(pos)` (−1 if none), and whether it came from the table or the beam plan |
| `MANIFEST.tsv` | file name, line count, SHA-256, the git commit of the exporter |

Acceptance: `./gradlew --no-build-cache :search:goldenExport` writes all files in under a minute; a second run gives byte-identical files (the manifest SHA-256 sums match); the checksums in the fingerprint files equal the constants of `BestSpecFingerprintTest` (R2 `-8723226000912328943`, `-6000360130468970639`, `-1429733491373947983`; R3 = BEST `4852147404152522458`, `2660956470353287877`, `-3579989785793258016`). From this package on, the classical track sets `STERNHALMA_GOLDEN_REQUIRED=1` in every `ctest` preset.

### WP2: engine in C++

Files: `cpp/src/engine/*`, `cpp/tests/engine_test.cpp`.

Interface (sketch; the implementer mirrors the Java public methods that the search and the tests use):

```cpp
namespace sh {
inline constexpr int kCells = 121, kDirections = 6, kPieces = 10, kMaxMoves = kPieces * kCells;
struct Move { static constexpr int of(int from, int to) { return from | (to << 8); } /* from(), to(), to_string() */ };
class Position {
 public:
  static Position initial(const RuleSet&);   // RuleSet: two players, default rules only
  Position copy() const;                     // explicit, as in Java; the copy constructor is deleted to avoid accidental copies
  int generate(std::span<int, kMaxMoves> out); // legal moves in Java order, returns the count
  void make(int move); void unmake();
  std::uint64_t hash() const; int side_to_move() const; int ply() const;
  std::optional<Outcome> outcome() const; Outcome no_moves_outcome() const;
  // owner(), pieces(), piece_count(), piece_cell(), goal_occupied(), distance_sum(), rules(), layout()
};
}
```

Tests (all against the golden files): every Zobrist key; geometry tables; for every line of `positions.tsv`: replaying the move list gives the same hash, side, ply, outcome, and `generate` gives the same moves in the same order; `make` then `unmake` restores hash and owner array; repetition detection on a constructed cycle (`REPETITION_IS_DRAW`); a `RuleSet` with three players or `leaveGoal = ALLOWED` is refused.

Acceptance: all 2,000 positions agree; the `debug-asan` run is clean.

### WP3: race table reader

Files: `cpp/src/tablebase/*`, `cpp/tests/tablebase_test.cpp`.

- `RaceTable::load(path, Region)` returns `std::expected<RaceTable, LoadError>`. It rejects a Git LFS pointer file (first line `version https://git-lfs.github.com/spec/v1`), a wrong magic or version, a header that does not match the region (leave-goal rule, minimum row, 10 pieces, the cell list, the state count of `Ranking`), and a file whose SHA-256 differs from `85850d6f47f526daf45ac5cd3a0dad8841244cb50d0d8d15a5b6a54f09d1bfd1` (rows1) or `d62edf38bbccb229ca055da3054a11e6daffae817d2293dbfa523ad8bb3678e3` (rows2). These constants are the SHA-256 of the **whole file**: `RaceTable.tryLoad` compares them with `TableFile.sha256Hex(file)`, and they equal the Git LFS object ids. The header stores a second SHA-256 over the body only; check it too, as `TableFile.read` does.
- `Ranking` (combinatorial ranking of piece configurations), `covers`, `count`.
- The table path: `STERNHALMA_TABLE_DIR` or `<repo>/tablebase/tables/`. The error message for a pointer file says "run git lfs pull".

Tests: every line of `race-counts.tsv`; each rejection case with a small crafted file in a temporary directory (pointer, wrong magic, truncated body, wrong SHA-256).

Acceptance: all counts agree; loading rows1 takes under 2 s on the Mac (measured and printed, not asserted).

### WP4a: linear evaluator, bit-exact

Files: `cpp/src/search/{evaluator.hpp,linear_weights,linear_eval}.*`, `cpp/tests/eval_test.cpp`.

- `Evaluator` is an abstract class with `evaluate(const Position&)`, `futility_safe(const Position&, int depth)` and `incremental() -> std::unique_ptr<EvalState>` (null if none). The concrete classes are `final`, as in Java. The calls stay virtual; devirtualisation is not part of the port.
- `LinearWeights`: copy R1, ROUND3 and SCALE as `constexpr std::array<double, N>` literals with all digits (use the Java `Double.toString` output, which round-trips exactly).
- Keep the double operations of `LinearEvaluator.features`, `combine` and `score` in the Java order ([Java compatibility rules](#java-compatibility-rules)), including `GainSearch`.

Tests: the `linear-r1` and `linear-r3` values of `positions.tsv`, bit for bit.

Acceptance: 0 differences out of 2,000 × 2 values. If any differ, the first suspect is FMA contraction; check the object code for `fmadd` with `objdump -d`.

### WP4b: incremental evaluation and race evaluator

Files: `cpp/src/search/{incremental_linear,jump_graph,race_adjudication,race_eval}.*`, tests in `eval_test.cpp`.

- `IncrementalLinear` and `JumpGraph`: 128-bit sets as two `std::uint64_t`; `std::countr_zero` for bit scans where Java uses `Long.numberOfTrailingZeros`.
- `RaceEvaluator` (`racelin1-r3`) with `RaceAdjudication.passed` and the table lookup of WP3.
- A `VerifyingEvalState` for tests: compares incremental and full evaluation at every node.

Tests: the `racelin1-r3` values and the `futility_safe` values of `positions.tsv`, bit for bit; the incremental state along every move list of `positions.tsv` equals the full evaluation after each move and after each `unmake`.

Acceptance: 0 differences out of 2,000 × 3 values; the incremental walk agrees everywhere.

### WP5: alpha-beta, player spec, fingerprint positions and R2 games

Files: `cpp/src/search/{scores,options,player_spec,alpha_beta,alpha_beta_player,fingerprint}.*`, `cpp/apps/fingerprint_main.cpp`, `cpp/tests/search_test.cpp`.

- `AlphaBeta`: same table layout (`keys`, `scores`, `info` bit packing, generations for depth-preferred replacement), same move buffers, killers, history (decay `>>= 2` per search), LMR tables, aspiration, futility, mate handling, `partial`, node counting, iterative deepening, key salt. Time limits are supported but untested; the comparison uses node limits only.
- `PlayerSpec::parse`: the keys of `R2_SPEC`, `R3_SPEC` and `BEST_SPEC` (`eval`, `pvs`, `killers`, `history`, `lmr`, `asp`, `ttrep`, `mate`, `partial`, `fut`, `futk`, `fut2`, `race`, `racerows`, `nodes`, `tt`, `depth`); an unknown key is an error, as in Java. The `eval` values `distance` and `linear-r2` are refused with "not ported".
- `fingerprint_main <spec>` prints exactly the lines of `Fingerprint.main`, so `diff cpp/golden/fingerprint-r2.txt <(build/release-hardened/fingerprint "<R2_SPEC>")` shows the first divergence. Until WP6 is done, a spec with `race=1` prints the position lines and the positions checksum and then stops with a "race play not ported" line instead of the game lines.

Tests: the positions checksum and every per-position line for R2 and R3 (and BEST, which equals R3); the R2 game lines and checksums at 1,000 and 5,000 nodes (R2 has no race play).

Acceptance: 4 of the 9 constants reproduced (R2 positions, R2 game at 1,000, R2 game at 5,000, R3 positions); BEST positions equal R3 positions. The run passes under `debug-asan` **and** with the `release` and `release-hardened` binaries of `fingerprint` (the timing binaries must compute the same trees as the tested one).

### WP7: search benchmark and Mac measurement, positions

Files: `cpp/apps/search_bench.cpp`, `cpp/scripts/bench-mac.sh` (classical part).

`search_bench <specA> [<specB>] [--positions K] [--games G] [--reps R] [--golden cpp/golden]` mirrors the timing part of `Bench`: one untimed warm-up pass, then R repetitions; per item (position search with a fresh searcher, full game with two fresh players) the thread CPU time (`clock_gettime(CLOCK_THREAD_CPUTIME_ID)`) around each `search` or `choose` call; nodes per CPU-second, CPU milliseconds per move, wall time for information; A and B interleaved and alternating as in `Bench`; median ratio; the identical-tree check (node counts of A and B equal per item). The output lines use the same wording as `Bench`, so one parser reads both. **In every run**, `search_bench` compares the node count and move of each position with the per-position line of the matching golden fingerprint file; on a difference it prints the item, exits 2 and writes no result line.

`bench-mac.sh` runs the session of [Classical measurement](#classical-measurement): the self-checks, the interleaved Java and C++ processes, the load, thermal and process checks, and writes `cpp/results/mac-classical-<date>.txt`.

Acceptance: the C++ self-ratio (spec against itself) and the Java self-ratio of the same session lie in 0.98 to 1.02; the trend check of [Classical measurement](#classical-measurement) passes; the positions rows of the table are filled for Java, `release-hardened` and `release`; the result file passes `scan-sensitive.pl`.

### WP6 (cuttable): race play, remaining game fingerprints, games row

Files: `cpp/src/search/{solitaire,race_move}.*`, tests in `search_test.cpp`, the games row in `bench-mac.sh`.

Tests: every line of `race-moves.tsv` (move and source); R3 and BEST game lines and checksums.

Acceptance: all 9 constants of `BestSpecFingerprintTest` reproduced, with the `debug-asan`, `release` and `release-hardened` binaries; then the games row (`--games 2`) of [Classical measurement](#classical-measurement) is measured in a session with the same hygiene as WP7.

If WP6 is cut ([Time box and stop rules](#time-box-and-stop-rules)), the document states that equality rests on 4 of the 9 constants plus the BEST positions checksum, and the games row is missing.

### WP8: Java golden exporter, network, Java cross-language benchmark, checks

Files: `nn/src/test/java/sternhalma/nn/GoldenNnExport.java` (Gradle task `:nn:goldenExport`), `nn-tornado/src/test/java/sternhalma/tornado/CrossBench.java` (Gradle task `:nn-tornado:crossBench`, set up like `benchmark`), Gradle task `:nn-tornado:printRunCommand` (writes the full `java` command line of `crossBench` and of `gpuFeed`, with JVM arguments, TornadoVM flags and classpath, to `cpp/build/java-nn.cmd` and `cpp/build/java-gpufeed.cmd`).

Steps:

1. `GoldenNnExport [--out cpp/golden] [--real]` writes safetensors files (the C++ reader is needed anyway, so no second format). `--real` reads `$STERNHALMA_DATA_DIR/loop4/gen-0011/candidate.safetensors`.

   | File | Content |
   |---|---|
   | `nn-weights-small-u5.safetensors`, `nn-weights-large-u5.safetensors` | the u5 weights of SMALL and LARGE `classic1`, two players: exactly the weights of `GpuFeedBenchmark` |
   | `nn-inputs.safetensors` | tensor `inputs` [1024][122][13]: 1,024 positions of `Playouts.positions(2, 60, 160, 1)` (the feed's position set), taken evenly spaced, encoded with `classic1` |
   | `nn-ref-small-u5.safetensors`, `nn-ref-large-u5.safetensors` | `logits` [1024][242], `values` [1024][2] of `Network` (the scalar reference), and `vlogits`, `vvalues` of `VectorNetwork` |
   | `nn-ref-large-gen11.safetensors` | the same for the real weights, only with `--real` (Mac only, see [Open questions](#open-questions)) |
   | `nn-cases.safetensors` | hand-checkable cases: one position with NaN in `conv_in.weight` (expected NaN), dense2 biases of 44.3, 100, −1000, +∞ (expected exact ±1), a dirty padding row (must be refused) |
   | `MANIFEST.tsv` | file name, SHA-256, git commit, and the measured tolerance figures of step 2 |

2. **Tolerance check on the u5 weights.** The exporter (or a test next to it) runs `VectorNetwork` and `TornadoNetwork` (Metal) on all 1,024 inputs for SMALL and LARGE u5 and records the largest error of each against `Network` as a fraction of the bound 1e-4 + 1e-3 · \|ref\| in `MANIFEST.tsv` (keys `max_err_frac.<impl>.<preset>`). If a figure exceeds 1.0, the exporter also writes the extra term needed, in the form of [network](network.md#numeric-conventions) (`extra_rel_of_max_logit`), and `nn_check` reads and applies it. Expected from `docs/network.md`: about 0.03 to 0.04 for `VectorNetwork`; `TornadoNetwork` is new.
3. `CrossBench --golden cpp/golden --presets small,large[,gen11] --batches 1,16,64,256,512,1024 --impls scalar,vector,tornado [--anchors] [--cold]` runs the schedule of [Network measurement](#network-measurement) on the Java side with the same files, and prints one TSV line per case in the shared schema (below). It uses `TornadoNetwork` without the profiler and reads `timings()` for the phase split; a second pass with `TornadoNetwork.profiled` collects the per-task kernel times (reported separately, because the profiler slows the call; see [tornado](tornado.md#findings-1), finding 1). `--anchors` runs only the anchor rows. `--cold` runs one forward pass of one bucket and exits, for the start-up measurement. The header prints the JVM flags, the GC, the Vector API species and its lane count, `UseAVX` and `MaxVectorSize`.
4. `CrossBench` verifies the outputs of the last call of each case against `nn-ref-*` with the same bound as `nn_check`; on a violation it exits 2 and prints no TSV line for that case.
5. Shared TSV schema (`cpp/scripts/merge-bench.pl` reads both):

   ```text
   lang impl device preset weights batch bucket lanes evals_per_s ms_per_call_median ms_p90 round_min round_max
   copy_in_ms h2d_ms kernel_ms d2h_ms copy_out_ms priors_ms first_call_ms load_1min verified
   ```

   A phase that an implementation cannot measure separately is `NA`, never 0. TornadoVM reports one "execute" figure that contains H2D, kernels and D2H; it goes into `kernel_ms` with `h2d_ms` and `d2h_ms` as `NA`, and the TSV comment line says so. `verified` is `ok` (a line is printed only then).
6. **TornadoVM math flags as facts.** Read and record, in `cpp/README.md`: the NVRTC options that TornadoVM 7.3.1's CUDA backend passes (search the backend classes of the installed SDK for the option strings, and confirm with `-Dtornado.printKernel=True` plus any option-printing flag the backend has), in particular `-fmad`, `--use_fast_math` and `-arch`; the Metal compile options (fast math off by default, `tornado.metal.fastmath`). If the NVRTC options cannot be determined, the document says "not determined" and names what was tried.

Acceptance: both tasks run on the Mac; `goldenExport` is deterministic for weights, inputs and references (SHA-256 of two runs equal); the tolerance figures are in `MANIFEST.tsv`; `CrossBench` prints all rows for SMALL and LARGE; step 6 is written down. From this package on, the network track sets `STERNHALMA_GOLDEN_REQUIRED=1` in every `ctest` preset.

### WP9: network core

Files: `cpp/src/core/json_min.*`, `cpp/src/nn/{config,safetensors,parameters,gather,reference_net,priors,golden_io,buckets}.*`, `cpp/apps/nn_check.cpp`, `cpp/tests/nn_test.cpp`.

- `safetensors::read(path) -> std::expected<Contents, Error>` with every rejection rule of [network](network.md#safetensors-file) (header length larger than the file, not an object, duplicate name, offsets that do not match the shape, overlaps, gaps, trailing bytes, dtype other than F32, product above 2^31 − 1). `json_min` parses only what the header needs (objects, arrays, strings with escapes, integers) and rejects everything else.
- `Parameters::load`: metadata keys, size limits, names and shapes against `Parameters.shapes`.
- `ReferenceNet::forward(std::span<const float> input, int batch, std::span<float> logits, std::span<float> values)`: the operations of `ReferenceOps` and `Network`, accumulating in double in ascending index order; ReLU with `java_max`; the residual priors of `ResidualPriors` and `VectorNetwork` for `classic1`.
- `nn_check --golden cpp/golden --impl reference|simd|simd-nofma|metal|cuda --preset small|large|gen11` compares an implementation with `nn-ref-*`, applies the bound and any extra term from `MANIFEST.tsv`, prints the largest error as a fraction of the bound for logits and values, and exits 1 if the bound is broken.

Tests: one malformed file per rejection rule (built in the test, as the Java tests do); the C++ reference against `nn-ref-*`: **logits bit-identical** to Java `Network`, values within 1 ulp; the cases of `nn-cases.safetensors`; batch results equal single results.

Acceptance: the bit-identity of the logits holds for SMALL and LARGE; `nn_check --impl reference` passes with the `debug-asan`, `release` and `release-hardened` binaries. If bit-identity fails, the plan expects the cause to be FMA contraction or a different summation order, and the fix is in the code, not in the criterion.

### WP10: CPU SIMD forward pass

Files: `cpp/src/nn/simd_net.*`, tests in `nn_test.cpp`, the per-file flag in `CMakeLists.txt` (prepared in WP0) and in `build-cuda.sh`.

Port of `VectorNetwork`: weights repacked into tiles of 2L output channels (L = float lanes), 4 cells per inner step, 8 accumulators, channels padded with zero weights to a multiple of 2L, no allocation in `forward`. Vectors use the compiler vector extension `float __attribute__((vector_size(4 * L)))`, which gcc and clang both map to NEON (L = 4), AVX2 (L = 8) or AVX-512 (L = 16). L is a compile-time constant `SH_SIMD_LANES`, defaulting from the target (`__ARM_NEON` → 4, `__AVX512F__` → 16, `__AVX2__` → 8) and overridable, printed by the benchmark header like the Java species.

FMA, to match `VectorNetwork`, which always uses `fma`:

- **`simd` (default) uses FMA.** The multiply-add is written as `acc + w * x` on vector types, and `simd_net.cpp` alone is compiled with `-ffp-contract=fast` (the named exception in `check-rules.sh`). This works with both clang and g++ 12, which has no `__builtin_elementwise_fma`.
- **`simd-nofma` is the second variant**: the same source compiled a second time into `simd_net_nofma.o` with `-ffp-contract=off` and a different namespace macro. It is the extra row "without FMA".
- Proof in the object code: `objdump -d` of `simd_net.o` shows `fmla` (arm64) or `vfmadd` (x86-64) in the inner loop, and of `simd_net_nofma.o` shows none. `build-cuda.sh` repeats the check on the instance and logs it.

Tests: `nn_check --impl simd` and `--impl simd-nofma` within the bound on SMALL, LARGE and gen11; batches 1, 3, 64, 67; the object-code check.

Acceptance: within the bound for both variants, with the `debug-asan` and `release-hardened` binaries; one thread; `forward` allocates nothing (checked with a counting allocator in the test build).

### WP11a: Metal backend, first half

Files: `cpp/src/gpu/metal/{metal_net.hpp,metal_net.mm,kernels.metal}`, `cpp/cmake/EmbedText.cmake` (turns `kernels.metal` into a raw string literal header at build time), `cpp/tests/metal_test.mm`.

Port of `TornadoNetwork` and its kernels, faithful to the TornadoVM versions so that the difference that remains is the runtime. This half covers:

| Part | Must match the Java side |
|---|---|
| `naive` conv | one thread per (row, output channel), gather, ascending column, bias last, optional residual, ReLU `v < 0 ? 0 : v`, padding row 0 |
| heads | `policyHead` one thread per (position, logit); `valueHead` one thread group of 256 per position, the reduction of [tornado](tornado.md#how-the-value-head-reduces), `applyTanh = 0` for `classic1` |
| tanh | `DeviceMath.tanh`: ±1 for \|x\| > 8.5, Metal `tanh` inside (only where `applyTanh = 1`) |
| buckets | one pipeline set and buffer set per power-of-two bucket up to `maxBatch` 1,024, built on first use; chunks above `maxBatch` |
| host priors | after the results are back, the code of WP9 (`priors`) |
| math mode | `MTLMathModeSafe` (no fast math, like `tornado.metal.fastmath=False`) |
| memory | `copy` mode (TornadoVM-like): private device buffers, explicit blit copies of the input to the device and of the head buffer back, as TornadoVM does on Metal ([tornado](tornado.md#backends), "No kernel assumes unified memory") |

One command buffer per forward call, all kernels in one compute encoder, `waitUntilCompleted`.

Tests (`metal_test`, all with NaN-filled outputs): every conv layer shape of TINY, SMALL and LARGE with `naive` at batch 1, 3 and 67 against `ReferenceNet`'s convolution; the hand-computed one-channel case of `KernelsHexConvTest`; heads with C = 32, 64, 100, 300; the whole network (all layers `naive`) against `nn-ref-*` within the bound; saturation and NaN cases of `nn-cases.safetensors`.

Acceptance: `nn_check --impl metal --variant naive` within the bound for SMALL, LARGE and gen11 in `copy` mode.

### WP11b: Metal backend, second half

Files: as WP11a.

| Part | Must match the Java side |
|---|---|
| `tiled` conv | thread group 8 × 16, tile 64 rows × 32 channels × 32 columns, 4 × 4 register tile interleaved (row `ty + 16i`, channel `tx + 8j`), local tiles padded by one column |
| `simdgroup` conv | 4 SIMD groups of 32, 32 × 32 × 32 tile, 2 × 2 blocks of 8 × 8 with `simdgroup_multiply_accumulate`, W loaded transposed |
| variant rule | `naive` while bucket · C ≤ 128, else `simdgroup` (overridable with `--variant` for the kernel table) |
| `shared` memory mode | one `MTLStorageModeShared` buffer for the input and one for the output, no blits; the CPU writes and reads them directly. This is what a hand-written Apple Silicon program would do |

Phase timing: CPU clock around the copy into the buffer, around commit-and-wait, around the copy out and around the priors; GPU time from `MTLCommandBuffer.GPUStartTime`/`GPUEndTime`; per-kernel time from a second, instrumented mode with one command buffer per kernel (reported separately, like the TornadoVM profiler). The first call of a bucket records library compilation (`newLibraryWithSource`, once per process), pipeline creation (per bucket) and buffer allocation.

Tests: every conv shape with `tiled` and `simdgroup` at batch 1, 3 and 67; the whole network in both memory modes and with the automatic variant rule; a position gives the same bits in every slot and bucket (the property that lockstep self-play relies on); one run with `MTL_DEBUG_LAYER=1 MTL_SHADER_VALIDATION=1`.

Acceptance: `nn_check --impl metal` within the bound for SMALL, LARGE and gen11, both memory modes, every variant, with the `debug-asan` and `release-hardened` binaries.

### WP12: CUDA backend, gcc-12 stub build, build script, PTX check

Files: `cpp/src/gpu/cuda/{cuda_net.hpp,cuda_net.cu,kernels.cuh,cuda_net_stub.cpp}`, `cpp/src/nn/cuda_backend.{hpp,cpp}`, `cpp/tests/cuda_test.cpp`, `cpp/tests/cuda_adapter_test.cpp`, `cpp/scripts/{build-cuda.sh,build-gcc12-stub.sh,ptx-check.sh}`, `scripts/cloud/test/test_build_cuda.sh`.

- Kernels `naive`, `tiled` (same parameters as Metal; CUDA block 8 × 16), `policyHead`, `valueHead` (block of 256, shared-memory reduction), the same `DeviceMath` semantics (`tanhf` inside \|x\| ≤ 8.5). No `simdgroup` (TornadoVM refuses it on CUDA too). Variant rule as on the Java side for CUDA: `naive` while bucket · C ≤ 128, else `tiled` (`TornadoNetwork.variantFor` returns `TILED` where `useSimdgroup` is false).
- Kernel names never equal a CUDA math function name (TornadoVM pitfall 15).
- The split of [Toolchain](#toolchain): `cuda_net.cu` holds kernels and the thin host layer (`DeviceBuffer<T>` RAII, one stream, `cudaEvent` around H2D, kernels and D2H, per-kernel events in an instrumented mode); `cuda_backend.cpp` holds everything else. Two host-memory modes, both measured: `pageable` (like the JVM's off-heap buffers) and `pinned` (`cudaHostAlloc`).
- `cuda_test.cpp` (a `.cpp`, built by g++-12 and linked with `cuda_net.o`): generates deterministic weights and inputs in C++ (the `SplittableRandom` port, seed fixed, SMALL and LARGE shapes), runs `CudaNet` for each variant, memory mode and batch 1, 3, 67, 256, and compares with `ReferenceNet` within the bound. It needs **no golden file**, so it can run on the instance before the Gradle build. With the stub backend it reports "no CUDA device" and exits 0 only if `--allow-no-device` is given.
- `nvcc -std=c++20 -ccbin g++-12 -O3 -gencode arch=compute_75,code=sm_75 -gencode arch=compute_75,code=compute_75`; `--use_fast_math` is **not** used; `-fmad` is set to match what WP8 step 6 found for TornadoVM (default on if not determined), and recorded in the results.
- `build-cuda.sh [--nvcc PATH] [--cxx PATH] [--out DIR]`: finds `nvcc` (`command -v nvcc`, then `/usr/local/cuda/bin/nvcc`, then the newest `/usr/local/cuda-*/bin/nvcc`) and `g++-12`; prints `nvcc --version`, `nvcc --list-gpu-arch` and `g++-12 --version` into the result directory; fails with exit 3 if `nvcc` or `g++-12` is missing, and with exit 3 if `compute_75` is not in the architecture list. It compiles `cuda_net.cu` with nvcc and everything else (`src/core`, `src/nn`, `apps/nn_check.cpp`, `apps/nn_bench.cpp`, `tests/cuda_test.cpp`) with `g++-12 -std=c++23 -O3 -march=native -ffp-contract=off`, twice: once with `-D_GLIBCXX_ASSERTIONS` (the hardened binaries, used for the tests and the main rows) and once without (the "cost of the checks" rows). `simd_net.cpp` is built as in WP10, for `SH_SIMD_LANES=8` and `=16` (two `nn_bench` binaries) and its object code is checked for `vfmadd`.
- `build-gcc12-stub.sh` (Mac): runs `build-cuda.sh` with `--cxx` set to the nixos-25.05 `g++` and with `cuda_net_stub.cpp` in place of `cuda_net.cu` (a `--stub` switch of `build-cuda.sh`), so the Linux file list and flags are exercised on the Mac. Without `-march=native` problems on arm64: the script maps `-march=native` to `-mcpu=native` when `uname -m` is `arm64`.
- `test_build_cuda.sh` (stub test in `scripts/cloud/test/`): fake `nvcc` and `g++-12` scripts that log their arguments. Checks: `-ccbin g++-12` and `-std=c++20` on the nvcc line, `-std=c++23` and `-ffp-contract=off` on every g++ line, `-ffp-contract=fast` only for `simd_net.cpp`, both `-gencode` arguments; a missing nvcc gives exit 3; an architecture list without `compute_75` gives exit 3.
- `ptx-check.sh` (best effort, Mac): compiles `cuda_net.cu` device-only with a nixpkgs clang that has the NVPTX target (`clang++ -x cuda --cuda-device-only --cuda-gpu-arch=sm_75 -nocudainc -nocudalib -S`, with the few CUDA declarations it needs provided by a small shim header). It catches syntax and type errors in the kernels before the paid run. Time box 30 minutes; if it does not work, record that in `cpp/README.md` and continue.

Acceptance (Mac): `build-gcc12-stub.sh` builds every Linux source with gcc 12 without a warning, and the resulting `nn_check --impl reference` and `--impl simd` pass on SMALL and LARGE; `cuda_adapter_test` (clang, `debug-asan`, stub backend) checks the argument packing of `cuda_backend.cpp` against `ReferenceNet`'s parameter layout; `test_build_cuda.sh` green; `ptx-check.sh` green or documented as not working. Acceptance (AWS, in WP15): `cuda_test` and `nn_check --impl cuda` within the bound; `compute-sanitizer --tool memcheck` reports 0 errors on `cuda_test` at batches 1 and 67, or its absence is logged.

### WP13: network benchmark and Mac measurement

Files: `cpp/apps/nn_bench.cpp`, `cpp/scripts/bench-mac.sh` (network part), `cpp/scripts/merge-bench.pl`.

`nn_bench --golden cpp/golden --presets ... --batches ... --impls reference,simd,simd-nofma,metal-copy,metal-shared,cuda-pageable,cuda-pinned [--variant naive|tiled|simdgroup|auto] [--anchors] [--kernels] [--cold]` runs the schedule of [Network measurement](#network-measurement) and prints the shared TSV schema. After the timed rounds of each case it compares the outputs of the last call with `nn-ref-*` (same bound as `nn_check`); on a violation it exits 2 and prints no TSV line for that case. `--kernels` runs the conv kernels alone (the kernel table). The header prints compiler, flags, `SH_SIMD_LANES` and the hardening mode.

Measurement on the Mac: see [Network measurement](#network-measurement) and [TornadoVM breakdown](#tornadovm-breakdown). Results go to `cpp/results/mac-nn-<date>.tsv`.

Acceptance: every row of the tables in those sections has a C++ and a Java value or a stated reason why not; the anchor rows agree within 2 % between start and end of the session; `nn_check` passes with the binaries used for timing; the result file passes `scan-sensitive.pl`.

### WP14: merge, AWS scripts

Files: `scripts/cloud/bootstrap.sh`, `scripts/cloud/aws-spot.sh`, new `scripts/cloud/spot-retry.py` with `scripts/cloud/spot-retry.py.lock`, new tests in `scripts/cloud/test/`.

Step 0: merge `exp/cpp-nn` into `exp/cpp` in `../stern-halma-cpp`; `ctest --preset debug-asan` green there.

Minimal changes:

1. **`bootstrap.sh --suite default|cpp`** (default `default`, the current behaviour unchanged). With `cpp`, the steps run in this order, the risky C++ steps first:
   1. the existing package step (apt), unchanged;
   2. `cpp-toolchain`: `apt-get install -y g++-12`; find `nvcc` (function `find_nvcc`, testable like `find_nvrtc`); find `compute-sanitizer` and `nvidia-smi`; write `results/nvcc-version.txt` and `results/toolchain.txt`;
   3. `cpp-build`: `cpp/scripts/build-cuda.sh`;
   4. `cpp-cuda-test`: `cuda_test` (C++ reference, no golden file needed);
   5. **gate:** if `cpp-build` or `cpp-cuda-test` failed, the suite stops here: results are collected and the instance shuts down (cost about 0.02 USD). `summary.txt` names the failing step;
   6. `cpp-sanitizer`: `compute-sanitizer --tool memcheck cuda_test --batches 1,67` (timeout 300 s). **Non-blocking:** if `compute-sanitizer` is missing, the step is `SKIP` with the reason in the summary; if it reports errors, the step is `FAIL` and the run continues;
   7. the existing JDK, TornadoVM and Gradle build steps, then `printRunCommand` for `nn-tornado`;
   8. `golden`: `./gradlew :nn:goldenExport --args="--out $SRC/cpp/golden"` (weights and inputs are generated on the instance; no upload of data files). Then `sha256sum` of the weight and input files is compared with `cpp/expected-golden-sha256.tsv` (committed in WP13 from the Mac manifest; hashes of generated test data, nothing sensitive). A difference in weights or inputs is a `FAIL` of this step (the comparison would not be like for like); a difference in the reference outputs is recorded only (the JVM's `Math` functions may differ by one ulp between platforms);
   9. `cpp-check`: `nn_check --impl cuda`, `--impl simd` and `--impl simd-nofma` for SMALL and LARGE;
   10. `anchors-start`: `nvidia-smi --query-gpu=clocks.sm,clocks.mem,temperature.gpu,utilization.gpu,power.draw --format=csv`, then the anchor rows in the order C++, Java, C++, Java (`nn_bench --anchors --impls cuda-pinned,simd`, `crossBench --anchors --impls tornado,vector`);
   11. `cpp-bench`: `nvidia-smi` line, then `nn_bench` for SMALL and LARGE, batches 1, 16, 64, 256, 512, 1024, impls `simd` (both lane builds), `simd-nofma`, `cuda-pageable`, `cuda-pinned`, plus `--kernels` (LARGE, batch 1, 64, 512) and `--cold`, with the hardened binary; the unchecked binary for the batch-256 rows only;
   12. `java-crossbench`: `nvidia-smi` line, then `crossBench` with the same arguments (impls `vector`, `tornado`) and `--cold`, started with the command line from `printRunCommand`;
   13. `anchors-end`: as `anchors-start`;
   14. `gpufeed-small`, `gpufeed-large`: the existing feed rows at batch 256 with `--feed-seconds` (30 s for this suite).

   The device-test step and the three Vector API benchmarks are skipped in the `cpp` suite (they were validated in the two earlier runs; this keeps the paid time short).
2. **`aws-spot.sh --suite`**: validated against `^(default|cpp)$`, passed into the user data. `--dry-run` shows it.
3. **Cost file in `aws-spot.sh`**: at the end of every run that launched an instance, write `cloud-results/<run id>/cost.tsv` with one line: `suite`, `launched` (ISO time), `seconds`, `price_usd_per_h`, `estimate_usd`, `worst_case_usd`. Today the cost line goes to the terminal only. If the price is unknown, `estimate_usd` is the worst case.
4. **Exit code 4 in `aws-spot.sh`**: "no capacity or no quota, nothing was launched" (`InsufficientInstanceCapacity`, `MaxSpotInstanceCountExceeded`, `VcpuLimitExceeded`, `SpotMaxPriceTooLow` from `run-instances`). Today these end with 1 together with real failures.
5. **AMI by name**: when the SSM parameter cannot be read and `--ami` is not given, `resolve_ami` falls back to `aws ec2 describe-images --owners amazon --filters "Name=name,Values=Deep Learning Base OSS Nvidia Driver GPU AMI (Ubuntu 22.04)*"` and takes the newest. The chosen AMI id is printed, never committed.
6. **`spot-retry.py`**, a Python script (stdlib only, PEP 723 header with `#!/usr/bin/env -S uv --quiet run --frozen --script`, `exclude-newer` set, lock file `spot-retry.py.lock` from `uv lock --script`), following the project's rules for long-running scripts:

   ```text
   spot-retry.py [--status] [--detach] --regions r1,r2 --attempts N --wait S --budget-usd B --suite cpp
                 [--on-demand-after K] -- <aws-spot.sh run arguments>
   ```

   - **Pre-flight:** before the first attempt, `aws-spot.sh preflight --instance-type <type> --region <r>` for each region; a region that ends with exit 3 (for example no spot quota for G instances) is removed from the list and named in the log. No region left: exit 3, nothing launched.
   - **Retry:** calls `aws-spot.sh run --region <r> --budget-usd <remaining>`; retries **only on exit 4**, rotating through the remaining regions, waiting S seconds between attempts. Any other exit code stops the loop and is returned. `--on-demand-after K` switches to `--on-demand` after K spot attempts (only if the owner allows it, see [Open questions](#open-questions)).
   - **Cumulative budget:** before each attempt it sums `estimate_usd` of every `cloud-results/*/cost.tsv` with `suite = cpp` (a cpp run directory without `cost.tsv` counts with the worst case of the current arguments), adds the worst case of the next attempt, and refuses the attempt if the sum exceeds B. B is 1.00 USD for all cpp runs together, including a WP17 rerun.
   - **Detached start:** `--detach` forks (`os.fork`, `os.setsid`), writes its output through `+errlog` into a log file in `cloud-results/`, prints the log path and the PID, and returns at once. The harness's background jobs die after about 10 minutes; a detached run survives that.
   - **Lock:** `fcntl.flock` on `cloud-results/.spot-retry.lock`, held for the whole run. A second start while the lock is held prints "already running: pid=… since …" and exits 75.
   - **Status:** a status file `cloud-results/.spot-retry.status` (PID, start time, attempt, region, run id; for display only). `--status` probes the lock, then reads the status file and `ps -o etime=`; with a free lock it prints "not running".
   - **Abort:** SIGINT and SIGTERM set a flag; the runner forwards SIGTERM to the running `aws-spot.sh` and waits for it, so its cleanup and verification run; then it prints the summary and exits 130 or 143. Between attempts the flag stops the loop at once.
   - **Summary:** attempts per region, exit code of each, run ids, cost of each from `cost.tsv`, cumulative cpp cost, and what another run would do.
   - **Exit codes:** 0 a run succeeded; 1 a run failed; 3 no usable region; 4 no capacity after all attempts; 5 budget reached; 75 already running; 130/143 aborted.

   The AWS profile and region come from the environment (`AWS_PROFILE`, `AWS_REGION`).

Stub tests (no AWS account, as the existing ones; `scripts/cloud/test/run-all.sh` runs them all; shellcheck clean):

| Test | Checks |
|---|---|
| `test_suite_option.sh` | valid values accepted; `cpp;rm` refused with exit 2; the user data contains `--suite cpp` |
| `test_suite_order.sh` | with stub commands in `bootstrap.sh`: the cpp steps run in the order above; a failing `cpp-build` stops the suite before the Gradle build and calls the shutdown; a missing `compute-sanitizer` gives `SKIP` and the run continues |
| `test_retry.sh` | stub `aws-spot.sh` returning 4, 4, 0 → three attempts across two regions; returning 1 → one attempt, exit 1; budget reached by earlier `cost.tsv` files → refused with exit 5; a region whose pre-flight returns 3 is never tried |
| `test_retry_lock.sh` | a second start while the first holds the lock → exit 75 and the message names the PID; `--status` during and after the run |
| `test_retry_signal.sh` | SIGTERM during an attempt → the stub `aws-spot.sh` receives SIGTERM, its cleanup marker file appears, the runner exits 143 with a summary |
| `test_find_nvcc.sh` | fake directories `cuda-12.4`, `cuda-13.0` → the newest; none → empty |
| `test_exit_capacity.sh` | stub `aws` that fails `run-instances` with `InsufficientInstanceCapacity` → exit 4 and cleanup ran |
| `test_cost_file.sh` | a stubbed run writes `cost.tsv` with all six fields |
| `test_build_cuda.sh` | from WP12 |

Acceptance: `scripts/cloud/test/run-all.sh` green; `aws-spot.sh run --suite cpp ... --dry-run` prints the plan; `bash -n` and shellcheck pass; the Python script passes `python3 -m py_compile`.

### WP15: the AWS run

See [AWS run](#aws-run). Acceptance: the run directory contains `summary.txt` with every step OK (or a failing step with its log; `cpp-sanitizer` may be `SKIP`), the TSV files of both benchmarks with both anchor blocks, `nvcc-version.txt`, `toolchain.txt`, the `nvidia-smi` lines, the golden hash comparison, the sanitizer log or the reason for its absence, and `cost.tsv`; the verification at the end says that nothing is left. Before any value is copied into the repository, `scan-sensitive.pl` runs over the files that are copied.

### WP16 (optional, only after the owner's explicit yes): replay of the TornadoVM-generated kernels

Goal: separate TornadoVM's code generation from its runtime. Dump the kernel source that TornadoVM generates for the LARGE `simdgroup` (Metal) convolution and the value head (`-Dtornado.printKernel=True`, or `-Dtornado.metal.source.dump=True` on Metal), load it in the C++ harness (`newLibraryWithSource`), and time it with the launch geometry that `-Dtornado.threadInfo=True` reports. Then:

```text
Java end-to-end − C++ running TornadoVM's kernels   ≈ TornadoVM runtime cost (dispatch, transfers, synchronisation)
C++ with TornadoVM's kernels − C++ hand-written port ≈ code quality of the generated kernels
```

Time box: 3 hours, Metal only (a CUDA replay would need a second AWS run). The generated kernels take TornadoVM's argument layout (array headers, a kernel-context argument); if the arguments cannot be reproduced within the time box, stop and record what blocked it. The package can fail for reasons outside the project; the comparison stands without it. Without the owner's yes it is not started.

### WP17: implementation reviews and fixes

1. Merge `exp/cpp-classical` into `exp/cpp` if not yet done.
2. An Opus review of the whole branch diff against `main` (`git diff --no-ext-diff --no-textconv main...exp/cpp`), with this plan as the checklist: faithful port, the rules, the unsafe-zone list, measurement fairness, the sensitive-data scan of every committed result file.
3. The skill `/pr-review-toolkit:review-pr` on the same branch diff.
4. Fix the findings that hold up; record the rejected ones with a reason in the commit message.
5. If a fix touches CUDA code, a second AWS run is allowed within the **same cumulative** 1 USD cap (`spot-retry.py` enforces it).

### WP18: documentation

Write `docs/cpp-comparison.md` with the skill `technical-writing` (English): TL;DR first, then the result tables, the decision rule and which differences it resolves, method, the TornadoVM breakdown, the safety rules and the unsafe-zone list as built, the deviations from this plan, and open points. Add a results section for the run to [cloud](cloud.md) and one line with a link to the new document in `README.md`. Run the fresh-eyes review of the skill on the new document. Delete nothing of this plan; it stays as the record of what was intended.

## Measurement method

### Hygiene (both parts)

| Rule | How |
|---|---|
| No parallel load | No other agent, build or training runs during a measurement, and the other track is paused; the owner is asked before WP7, WP6's games row, WP13 and WP15. The 1-minute load average is logged before and after (`sysctl -n vm.loadavg` on the Mac, `/proc/loadavg` on Linux), together with the `pgrep` list of WP7. A run whose load before exceeds 2.0 on the Mac is not started, and a run whose load after exceeds 2.0 is repeated. |
| Self-ratio | Each session starts with a self-check: the same spec against itself in one process, for Java (`Bench <spec> <spec>`) and for C++ (`search_bench <spec> <spec>`). A ratio outside 0.98 to 1.02 means the machine is not quiet; the session is stopped and repeated. Time-based numbers count only after this check. [search-and-evaluation](search-and-evaluation.md) (section "Round 2 yardstick") records why: on identical code the Bench ratio moved between 0.980 and 1.005 between runs. For the network, the same check is the anchor rows at the start and at the end of a session; the two medians of each program must agree within 2 %. |
| Abort rule | If three consecutive sessions fail the self-ratio or anchor check, measuring stops and the owner is asked (the machine is not quiet enough, or something drifts). No number from a failed session is reported. |
| Thermal state | `pmset -g therm` before and after (CPU speed limit must read 100); power adapter connected; on Linux, an `nvidia-smi` line (SM and memory clock, temperature, utilisation, power) before each block. |
| Warm-up | Java: the JIT and TornadoVM compilation happen in an untimed warm-up (Bench: one pass; network: at least 2 s and 10 calls per case). C++ runs the same warm-up, although it has no JIT, so that caches, the GPU clock and the bucket set-up are in the same state. |
| JIT settled | Classical: the per-repetition nodes per CPU-second of repetitions 1, 2 and 3 must not rise monotonically by more than 1 % in total; if they do, the session adds one warm-up pass and repeats. Recorded in the result file. |
| Repetitions | Median of 3 repetitions (classical: 3 repetitions of all items; network: 3 rounds of at least 1 s), with the range reported. |
| Same inputs | The 64 reference positions and 2 game openings of `ReferencePositions` for the search; the golden weights and the 1,024 golden inputs for the network, cycled in the same order by both languages. |
| Same threads | One thread for the search and for the CPU network paths; one host thread driving the GPU. |
| Interleaving | Java and C++ cannot share a process, so processes alternate: J, C, J, C, J, C (three each); the reported cross-language ratio is the median of the three adjacent pairs. On AWS see [AWS run](#aws-run). |
| JVM | Java is started with `java` directly, not through Gradle, using the command line that `printRunCommand` wrote once (WP1, WP8). Fixed flags for every Java measurement: `-Xms4g -Xmx4g`, the default GC (G1), `--add-modules jdk.incubator.vector`, and for TornadoVM the flags of the existing Gradle task. The flags are part of every result file. |
| C++ | No LTO, no PGO, virtual `Evaluator` calls as in Java, `-O3 -mcpu=native`/`-march=native`. Recorded in every result file. |

### Decision rule

A cross-language difference counts as real only if it exceeds the resolution of the measurement:

| Part | Counts as real | Reported as "not resolved" |
|---|---|---|
| Classical (Mac) | ratio outside 0.98 to 1.02 (more than 2 %) | 0.98 to 1.02 |
| Network on the Mac | more than 5 %, and outside the round range of both sides | otherwise |
| Network on the T4 (n = 1 run on a shared instance) | more than 5 %, and the run is not marked unsettled | otherwise; a run marked unsettled reports ratios as indicative only |

The document states these thresholds next to the tables.

### Classical measurement

| Row | Java | C++ `release-hardened` (main) | C++ `release` (cost of the checks) |
|---|---|---|---|
| Nodes per CPU-second, `BEST_SPEC,nodes=20000`, 64 positions (node-limited and depth-4 searches), `--games 0` | `java … sternhalma.search.Bench <spec> <spec> --games 0` (command from `printRunCommand`) | `search_bench <spec> <spec> --games 0` | same |
| Nodes per CPU-second, R2 (`linear-r1`, no race table): isolates the evaluator without the table | | | |
| The same as row 1 with the two full games (`--games 2`), only if WP6 is done | | | |
| CPU ms per move (with the games row) | | | |
| Start-up: process start to first search result, including the race table load | `time` around a one-position run with `java` directly | same | |

The Java figure is side A of its own self-check run; the C++ figure is side A of its self-check run. Both runs also prove identical trees: the C++ run must report "identical on 64/64 positions" against itself, and its node counts must equal the golden fingerprint lines (checked inside `search_bench`).

### Network measurement

Schedule per case (implementation × preset × batch), identical in Java (`CrossBench`) and C++ (`nn_bench`): warm-up of at least 2 s and 10 calls, then 3 rounds of at least 1 s; per call the wall time; per round evaluations per second; the median round is reported, with the range; batch 1 additionally reports p50 and p90 of the per-call times over all rounds. The input for a call of batch n is the next n golden positions, wrapping around after 1,024. Each case ends with the output check against `nn-ref-*`.

| Dimension | Values |
|---|---|
| Presets | SMALL `classic1` (u5 weights), LARGE `classic1` (u5 weights), LARGE gen11 (real weights, Mac only) |
| Batches | 1, 16, 64, 256, 512, 1,024 |
| CPU implementations | Java `Network` (scalar), Java `VectorNetwork`, C++ `reference`, C++ `simd` (FMA, the main row), C++ `simd-nofma` (extra row) |
| Lane counts | Mac: NEON, L = 4 for both languages (the Java preferred species on the M3 Max is 128 bit). T4 host: C++ is built with L = 8 and L = 16; the row with Java's printed lane count is the comparison row, the other is shown as extra |
| GPU implementations, Mac | Java `TornadoNetwork` (Metal), C++ `metal-copy`, C++ `metal-shared` |
| GPU implementations, T4 | Java `TornadoNetwork` (CUDA), C++ `cuda-pageable`, C++ `cuda-pinned` |
| Kernel table | Mac: the conv kernels alone at SMALL and LARGE block shape, batch 1, 64, 512, every variant, C++ against `:nn-tornado:kernelBenchmark` (rerun in the same session). T4: C++ only (LARGE, batch 1, 64, 512); the Java side is a deliberate gap ([Scope](#what-is-left-out-and-why-the-comparison-survives-it)) |
| Hardening | the GPU and SIMD rows use the hardened binaries; the unchecked binary adds one row per platform at batch 256 |

The scalar CPU paths are slow (LARGE about 33 evaluations per second in Java); like `TornadoNetworkBenchmark`, a CPU case whose single call would take more than 3 s is skipped and marked.

### Differences of method that remain, and how they are reported

| Difference | Report |
|---|---|
| Java measures `forward` with `System.nanoTime`, C++ with `std::chrono::steady_clock` | both are monotonic wall clocks; stated |
| TornadoVM's "execute" contains H2D, kernels and D2H; C++ splits them | C++ reports both the split and the sum; the comparison table uses the sum |
| Java copies the input into an off-heap `FloatArray`, then TornadoVM copies it to the device; C++ `metal-shared` copies once and transfers nothing | both C++ memory modes are reported; `metal-copy` is the like-for-like row |
| TornadoVM kernel times come from its profiler, which slows calls | reported in a separate column, labelled as profiled |
| The JVM may deoptimise or collect garbage during a round | the round range shows it; `-Xlog:gc` is enabled in `CrossBench` and GC pauses during measured rounds are counted |
| Java checks array bounds always; C++ only in the hardened build | the main rows use the hardened C++ build; the unchecked build is shown as "cost of the checks" |
| `-fmad` of TornadoVM's NVRTC call | as determined in WP8 step 6, or stated as not determined |

### Disclosure and sensitive data

Every result file records date, CPU or GPU model, OS name and version, compiler and flags, JDK and JVM flags, TornadoVM version, load before and after, and the git commit. It records **no** hostname, user name, absolute path, IP address, AMI id, instance id, account id, region-specific resource id or profile name.

`cpp/scripts/scan-sensitive.pl` runs before every commit on the staged files and before values from `cloud-results/` are copied into the repository. It searches formats, not words: 12-digit numbers (account ids), `ami-`, `i-`, `sg-`, `sir-`, `vol-` followed by hex, IPv4 addresses, `ip-` and `ec2-` host names, `compute.internal` and `amazonaws.com` host names, e-mail addresses, absolute home paths (`/Users/`, `/home/`), and the values of `AWS_PROFILE` and `USER` from the environment. It prints how many lines it read per file, so an empty result can be told apart from a scan that saw nothing; `--self-test` proves that each pattern finds its planted example.

## TornadoVM breakdown

What the comparison answers for the TornadoVM part, and which numbers answer it:

| Question | Java number | C++ number | Derived |
|---|---|---|---|
| Pure kernel time | profiled per-task times (with the profiler's floor of about 0.5 ms per task, see [tornado](tornado.md#training-kernels), "Measuring by difference") | GPU timestamps per command buffer (Metal) or `cudaEvent` (CUDA), whole pass and per kernel | kernel time ratio; WP16 refines it on Metal |
| Host-device transfers | not separable inside "execute"; the bytes moved are known (input bucket and head buffer) | H2D and D2H separately, pageable and pinned (CUDA), copy and shared (Metal) | transfer share of a call; the saving of unified memory on Apple Silicon |
| Plan build, compilation, warm-up | JVM and TornadoVM start (about 0.3 s), first plan per bucket (Graal compile, code generation, driver compile; about 160 ms for one LARGE bucket in [tornado](tornado.md#findings-1), finding 3), from `--cold` and from the first call of each bucket | Metal: `newLibraryWithSource` once, pipeline creation per bucket; CUDA: module load at the first call (code compiled ahead of time by nvcc) | time to first result, per bucket set-up |
| Host-side priors | `timings()` "host priors" | the same code, timed the same way | per-position cost of both |
| Latency at batch 1 | p50 and p90 per call | same | ratio, and the batch where each GPU path overtakes one CPU thread |
| Throughput at batch 256, 512, 1,024 | evaluations per second | same | ratio, and the unexplained extra wall time of TornadoVM at large batches ([tornado](tornado.md#findings-1), finding 2) checked against C++ |
| Development effort: code | NCLOC of the TornadoVM inference path (1,372, measured) | NCLOC of `metal_net.mm` + `kernels.metal`, and of `cuda_net.cu` + `kernels.cuh` + `cuda_backend.cpp` | lines per backend; Java has one source for both backends, C++ has two; the doc states this and the agent-hours per backend |
| Development effort: build | clean compile time of `nn` and `nn-tornado` (`./gradlew --no-build-cache --rerun-tasks :nn-tornado:compileJava`), size of the jars, TornadoVM SDK size | clean build time per target (wall clock, Mac and instance), size of the stripped binaries | stated as context; the JIT and TornadoVM compile time at run time is in the plan-build row |

## AWS run

| Item | Value |
|---|---|
| Instance | `g4dn.xlarge` (Tesla T4, 4 vCPUs Cascade Lake), spot, one-time request, terminate on interruption |
| Before the run | `scripts/cloud/test/run-all.sh` green; the Mac part of WP12 done (gcc-12 stub build, stub tests); `aws-spot.sh ... --dry-run` read once; the owner's go-ahead, as for the earlier runs |
| Command | `scripts/cloud/spot-retry.py --detach --regions us-east-1,eu-central-1 --attempts 6 --wait 300 --budget-usd 1.00 --suite cpp -- --instance-type g4dn.xlarge --max-price 0.35 --max-minutes 45 --backend cuda --feed-seconds 30 --yes`, from `../stern-halma-cpp`, with `AWS_PROFILE` and `AWS_REGION` exported in the shell. Progress: `spot-retry.py --status` and the log path it printed |
| Order on the instance | apt, C++ toolchain, C++ build, CUDA correctness test (gate: stop and shut down on failure), sanitizer (non-blocking), JDK, TornadoVM and Gradle, golden export and hash check, `nn_check`, anchors, `nn_bench`, `crossBench`, anchors, feed rows (WP14, item 1) |
| Expected duration | about 27 minutes: boot and ssh 3, packages 1, C++ toolchain and build 2, CUDA test and sanitizer 2, JDK and TornadoVM 2, Gradle build 5, golden export and check 1.5, anchors 1, `nn_bench` 3.5, `crossBench` 4, anchors 1, feed rows 1.5 |
| Expected cost | about 0.13 USD at 0.25 to 0.30 USD/h; about 0.02 USD if the gate stops the run |
| Hard caps | `--max-minutes 45` × `--max-price 0.35` = 0.26 USD worst case per launched attempt; 1.00 USD cumulative over all cpp-suite runs (retries and a WP17 rerun included), enforced by `spot-retry.py` from the `cost.tsv` files; the instance's own shutdown timer and the cleanup of `aws-spot.sh` as today |
| Fallback when no spot capacity | regions without spot quota are dropped by the pre-flight; retry every 5 minutes, alternating the remaining regions, at most 6 attempts (failed launches cost nothing); then stop and ask the owner. On-demand only if the owner allows it (about 0.53 USD/h, 0.40 USD worst case at 45 minutes, within the cumulative cap) |
| Drift control | anchor rows C, J, C, J at the start and at the end of the measurement block (about 1 minute each); an `nvidia-smi` line before each block. If the anchors of one program differ by more than 2 % between start and end, or between its two runs within a block, the document marks the run as unsettled |
| Statistics | n = 1 run on a shared, virtualised instance. The document says so, and reports cross-language ratios below 5 % as not resolved |
| Spent so far | about 0.3 USD of the 5 EUR AWS test budget (owner's figure) |
| Data on the instance | the committed repository only (`git archive HEAD`, without `data/` and `tablebase/tables`); weights and inputs are generated there by the Java exporter and checked against the Mac hashes; nothing of the owner's data leaves the Mac |
| Results | `cloud-results/<run id>/results/`: `summary.txt`, `nvcc-version.txt`, `toolchain.txt`, `nvidia-smi.csv`, `cpp-*.log`, `golden-sha256.txt`, `nn-bench.tsv`, `crossbench.tsv`, `anchors.tsv`, `gpufeed-*.log`, `compute-sanitizer.log`, `system.txt`, and `cost.tsv` one level up (git-ignored, as today; the doc copies the values after `scan-sensitive.pl`) |

## Process

| Step | Who | Input | Output |
|---|---|---|---|
| Plan | Opus | the request | this document |
| Plan review | Fable, short | this document | findings; the plan is updated by Opus ([Review log](#review-log)) |
| Implementation and measurement | Sonnet, one agent per track (classical, network), each in its own worktree and branch | this plan | commits per work package, result files |
| Implementation review | Opus, then `/pr-review-toolkit:review-pr` | branch diff `main...exp/cpp` | findings, fixes (WP17) |
| Documentation and evaluation | skill `technical-writing`, English | result files | `docs/cpp-comparison.md` (WP18) |

### Time box and stop rules

- **Whole assignment:** 65 agent-hours. At 50 agent-hours the lead checks progress against the plan. If the remaining packages do not fit, they are cut in this order: WP16 (not started without the owner's yes anyway), WP6, the gen11 rows, the Mac kernel table, the `simd-nofma` and unchecked-binary extra rows. Anything else (equality packages, the main rows, the AWS run, reviews, documentation) is not cut; if even that does not fit, work stops and the owner is asked.
- **Equality packages** (WP2, WP4a, WP4b, WP5, WP6, WP9): if an acceptance criterion fails after two focused attempts, stop and report the first differing golden line instead of loosening the criterion.
- **No timing before equality:** the positions rows need WP5, the games row needs WP6, every network row needs WP9 and the implementation's own `nn_check`.
- **Measurement:** the abort rule of [Hygiene](#hygiene-both-parts).
- **AWS:** the gate on the instance, the cumulative 1 USD cap, at most 6 attempts per start, and the owner's go-ahead for each start.

## Risks

| Risk | Likelihood | Effect | Mitigation |
|---|---|---|---|
| FMA contraction or a reordered double sum changes `LinearEvaluator` scores by one unit, so node counts differ | high without the flag | WP4a/WP5 fail | `-ffp-contract=off` everywhere except `simd_net.cpp`, checked by `check-rules.sh`; bit-exact evaluator test before any search test |
| A subtle porting error makes trees diverge late in a game | medium | WP6 slow | golden lines per position and per move; `diff` against `Fingerprint.main` output shows the first divergence; WP6 is cuttable |
| CUDA code is only testable on AWS | certain | a failed run costs time and cents | gcc-12 stub build of all host code on the Mac, adapter test, PTX syntax check (best effort), the Metal port proves the algorithm first; on the instance the gate stops the run after about 6 minutes (0.02 USD) |
| gcc 12 from nixos-25.05 fails on the Mac | low (cached binary found) | the Linux-compatibility build is weaker | WP0 spike; fallback to a newer gcc plus a header ban in `check-rules.sh` |
| A C++ feature that gcc 12.4 on the Mac accepts but 12.3 on Ubuntu rejects | low | build gate stops the run | same major version and library; the gate costs 0.02 USD |
| The Ubuntu AMI changed and has no `g++-12` or a CUDA without `compute_75` | low | build gate stops the run | `build-cuda.sh` checks both and exits 3; `nvcc --list-gpu-arch` logged |
| `compute-sanitizer` missing on the AMI | medium | no device memory check on CUDA | the step is non-blocking and logs its absence |
| No spot capacity or quota | medium | delay | pre-flight per region, `spot-retry.py` with retries; on-demand only with consent |
| The harness kills a long-running retry loop | high without detaching | results lost while the instance still runs (cost bounded by the timer) | `--detach`, lock, status, signal forwarding |
| clang-tidy from nixpkgs cannot parse the Apple SDK | medium | no static analysis on the Mac | `-isysroot` argument; otherwise documented as skipped, and the compiler warnings and sanitizers still run |
| `-Wunsafe-buffer-usage` flags more than expected (libc calls, `argv`) | high | build fails | WP0 spike; the unsafe-zone list grows with named files |
| Metal shader validation variables do nothing on this macOS | low | weaker GPU bounds checking | recorded; NaN-filled buffers in the tests remain |
| The u5 tolerance does not hold for `TornadoNetwork` or the C++ GPU paths | low | `nn_check` fails | WP8 measures it on the Mac and records an extra term in `MANIFEST.tsv` before any GPU port |
| T4 clock or neighbour load drifts during the single run | medium | ratio distorted | anchors at start and end, `nvidia-smi` lines, "unsettled" marking, 5 % threshold |
| The machine is loaded by other agents | medium | numbers unusable | the self-ratio gate, the `pgrep` check, the abort rule; the owner is asked to pause other jobs |
| The generated-kernel replay (WP16) cannot reproduce TornadoVM's argument layout | medium | no code-quality split | time box, optional package, owner's yes required |

## Open questions

1. Should `exp/cpp` be merged into `main` after the reviews, or stay an experiment branch?
2. May the real LARGE weights (`data/loop4/gen-0011/candidate.safetensors`, 1.3 MB) be uploaded to the AWS instance? The plan assumes no and uses generated weights there; the real weights are tested on the Mac only.
3. May `spot-retry.py` fall back to on-demand (`--on-demand-after`) when no spot capacity is found, at about 0.53 USD/h within the same cumulative 1 USD cap?
4. Should the optional WP16 (replay of the TornadoVM-generated kernels, Metal only) be done? Without an explicit yes it is not started.
5. Is adding `cmake`, `ninja` and `clang-tools` from nixpkgs to `devenv.nix` (on the branch) acceptable? gcc 12 is used ad hoc from the `nixos-25.05` branch and not added there.
6. Is the race play part (WP6) wanted? It now comes after the positions measurement and is the first package cut by the time box. Without it, 4 of the 9 fingerprint constants plus the BEST positions checksum prove equality, and the games timing row is missing.
7. Is the time box of 65 agent-hours for the whole assignment, with the cut order of [Time box and stop rules](#time-box-and-stop-rules), acceptable?

## Review log

The plan review returned the verdict "go with changes": 1 blocker, 11 majors, 1 minor and 8 missing items. Every finding is taken over; two are taken over with a change of means, stated below. None is rejected.

| Finding (short) | Severity | Decision | Where and why |
|---|---|---|---|
| The CUDA build mixes C++20 `.cu` files with C++23 project headers; `g++` on Ubuntu 22.04 is 11 and nvcc uses it without `-ccbin`; the library rule is checked only on paid time | blocker | accepted, with a change of means | [Toolchain](#toolchain), WP12: `.cu` holds only kernels and a thin host layer behind a C++20 `cuda_net.hpp`; tests and apps are `.cpp` built with `g++-12 -std=c++23`; `nvcc -ccbin g++-12`; Mac acceptance with gcc 12 against a stub backend; `test_build_cuda.sh` with a stub `nvcc`. Change: `gcc12` is removed from current nixpkgs, so the plan uses the `nixos-25.05` branch (12.4.0, measured as cached for `aarch64-darwin`), with a fallback in the WP0 spike |
| The riskiest AWS step runs after Gradle; a second region often has no quota; WP17's rerun had its own 1 USD cap | major | accepted | WP14 item 1 (new order and gate), item 3 (cost file), item 6 (pre-flight per region, cumulative cap from `cost.tsv`); [AWS run](#aws-run) |
| The retry loop outlives the harness's background-job limit; no lock, status or abort behaviour | major | accepted, with a change of means | WP14 item 6: detached start, `flock` lock with exit 75, `--status`, signal forwarding, summary, stub tests for double start and abort. Change: the runner is a Python script (`spot-retry.py`), because the project's script rules ask for Python once argument parsing, locking and signal handling are involved and because `flock(1)` does not exist on macOS |
| The new worktree lacks `data/`, `.env`, TornadoVM and possibly the LFS tables | major | accepted | [Local data that a worktree lacks](#local-data-that-a-worktree-lacks), WP0 steps 1 and 11 and its acceptance (`BestSpecFingerprintTest` green in the worktree) |
| Equality is proven only in `debug-asan`; golden tests can skip; `nn_bench` checks nothing | major | accepted | `STERNHALMA_GOLDEN_REQUIRED=1` in every preset from WP1/WP8 and the manifest check (WP0 step 7); `fingerprint` and `nn_check` with the `release` and `release-hardened` binaries (WP5, WP6, WP9 to WP11b); `search_bench`, `nn_bench` and `CrossBench` verify every run and print no line on a mismatch |
| `VectorNetwork` always uses FMA, the C++ SIMD path did not; lane counts on AVX-512 not matched | major | accepted | WP10: FMA is the default `simd` variant via `-ffp-contract=fast` for `simd_net.cpp` only (works with g++ 12), `simd-nofma` is the extra row, object-code proof; [Network measurement](#network-measurement): L = 8 and L = 16 builds on the T4 host, compared with Java's printed lane count |
| The unchecked C++ build as the main classical number compares unsafe C++ with safe Java; JVM flags, LTO/PGO and start-up through Gradle unspecified | major | accepted | [Classical measurement](#classical-measurement): `release-hardened` is the main column; [Hygiene](#hygiene-both-parts): fixed JVM flags, `java` started directly from `printRunCommand`, no LTO or PGO, virtual calls, the trend check |
| AWS benchmarks run as two blocks without anchors; drift unchecked; n = 1 | major | accepted | WP14 item 1 steps 10 to 13; [AWS run](#aws-run) rows "Drift control" and "Statistics"; [Decision rule](#decision-rule) |
| The unsafe-zone list was called final but misses libc calls, `argv`, `span(ptr, n)` and CommonCrypto; lifetime is not enforced statically; the Linux build lacks the warning | major | accepted | WP0 spike (step 9); [Unsafe zones](#unsafe-zones) extended (`le_bytes.hpp`, `args.hpp`, `sha256.cpp`, CUDA host layer); byte reading through `std::copy_n` and `std::bit_cast`; the plain statement on lifetime, leaks and the Linux build; the rule that the Linux sources also build on the Mac under all rules |
| Two agents on one worktree and branch collide; a building track breaks the other track's measurement | major | accepted | [Branches and worktrees](#branches-and-worktrees): one worktree and branch per track, all shared files created in WP0; measurement packages require the other track to be paused, checked by `pgrep` |
| About 58 agent-hours for a short comparison; scope only for single constants; WP4 and WP11 too large; WP16 may fail for outside reasons | major | accepted | Scope: three-player rules, leave-goal, `distance` and `linear-r2` removed; WP7 now directly after WP5, WP6 after it and cuttable; WP4 split into WP4a/WP4b, WP11 into WP11a/WP11b; WP16 only after the owner's yes; total time box with cut order. The planned total stays about 58 hours without WP6, because the review's other findings add work (gcc-12 stub build, retry runner, checks); the time box caps it |
| The u5 tolerance is not checked for the feed weights before the AWS run; result files would record "machine" | minor | accepted | WP8 step 2 (measured figures and any extra term in `MANIFEST.tsv`, read by `nn_check`); golden hash check on the instance (WP14 item 1 step 8); [Disclosure and sensitive data](#disclosure-and-sensitive-data) |
| Missing: decision rule and abort criterion for repeated self-ratio failures | missing | accepted | [Decision rule](#decision-rule), abort rule in [Hygiene](#hygiene-both-parts) |
| Missing: time box and stop rule for the whole assignment | missing | accepted | [Time box and stop rules](#time-box-and-stop-rules) |
| Missing: JVM flags and TornadoVM math flags as checked facts | missing | accepted | JVM flags fixed in [Hygiene](#hygiene-both-parts); TornadoVM flags determined in WP8 step 6, with "not determined" as an honest outcome |
| Missing: kernel table on the T4 | missing | accepted in part | C++ kernel rows on the T4 are added (cheap, `cudaEvent`); the Java kernel benchmark on the T4 is a deliberate gap, reasoned in [Scope](#what-is-left-out-and-why-the-comparison-survives-it) (about 4 minutes of paid time for rows the Mac table covers) |
| Missing: build time and binary size in the effort comparison | missing | accepted | [TornadoVM breakdown](#tornadovm-breakdown), row "Development effort: build" |
| Missing: proof that CUDA 13.0 still compiles `sm_75` offline | missing | accepted | [Facts](#facts-this-plan-relies-on): NVIDIA's cut-off is below 7.5, Turing stays supported; the earlier runs compiled for the T4 with the CUDA 13.0 NVRTC; `build-cuda.sh` checks `nvcc --list-gpu-arch` before compiling |
| Missing: scan for third-party data and internal infrastructure before each commit, with proof that the scan sees lines | missing | accepted | `scan-sensitive.pl` with line counts and `--self-test` (WP0 step 5, [Disclosure and sensitive data](#disclosure-and-sensitive-data)), part of every package's exit criteria and of WP15 |
| Missing: `compute-sanitizer` availability on the AMI is unchecked | missing | accepted | WP14 item 1 step 6: non-blocking, absence logged as `SKIP`; stub test in `test_suite_order.sh` |
