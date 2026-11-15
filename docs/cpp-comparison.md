# C++ comparison: classical search and network path against Java and TornadoVM

**TL;DR:** A memory-safe C++23 port of the classical player `BEST_SPEC` and of the network forward pass computes the same results as the Java code: all 9 search fingerprint constants (checksums over the searches of fixed positions and games, see [search-and-evaluation](search-and-evaluation.md#search-tree-fingerprint)), all 332 race moves and bit-identical reference logits.
In the classical search, the bounds-checked C++ build searches 1.60 times as many nodes per CPU-second as Java on the Mac (1.60 on the 64 test positions, 1.54 on the positions plus two full games); these ratios are **valid** by the measurement rules, and the checks cost the C++ build 11 to 13 %.
On the CPU network path, C++ SIMD is 1.12 times as fast as the Java Vector API on the Mac (LARGE, **valid**) and 1.33 times on an AVX-512 Xeon (LARGE, **indicative** only, because the Java anchors drift); the first AWS run had shown C++ about 5 times *slower* (pairs: 4.6 times on SMALL, 5.4 times on LARGE), which was a defect of the port, fixed and confirmed by a second run.
On the GPU, hand-written CUDA and TornadoVM on a Tesla T4 are within 3 % of each other at batch 256 (rows **valid**; the ratio is **not resolved**, because it is below the 5 % threshold); TornadoVM is about twice as fast at batch 1, and a cold TornadoVM process needs 13 to 21 times as long as a cold C++ process to its first result.
The Mac GPU rows (Metal against TornadoVM on Metal) are all **unsettled**, because the Apple GPU clock drifted by up to 29 % within the session.

## How to read this document

| You want | Read |
|---|---|
| What was compared and what was left out | [Scope](#scope) |
| Why the timings compare the same computation | [Equality before time](#equality-before-time) |
| How a number gets the label valid, unsettled or indicative | [Method and hygiene](#method-and-hygiene) |
| The numbers | [Classical search](#results-classical-search), [Network on the CPU](#results-network-on-the-cpu), [Network on the GPU](#results-network-on-the-gpu), [TornadoVM breakdown](#the-tornadovm-share-in-detail) |
| Lines of code, build time, binary size | [Effort](#effort) |
| The C++ safety rules and what they caught | [Memory safety](#memory-safety) |
| The implementation review | [Review](#review-process-and-result) |
| AWS cost and what went wrong there | [Cost and cloud experience](#cost-and-cloud-experience) |
| What is still open | [Limits and open points](#limits-and-open-points) |
| Commands | [How to reproduce](#how-to-reproduce) |

The plan with the full rules is [cpp-comparison-plan](cpp-comparison-plan.md). The C++ code, its build and the work-package notes are in `cpp/` and [cpp/README.md](../cpp/README.md). Every figure below names the result file it comes from; all result files are in `cpp/results/`.

Terms used throughout:

- **`BEST_SPEC`:** the strongest classical player of the project, an alpha-beta search with the evaluator `racelin1-r3` (linear evaluation plus a race table) and race play from a precomputed table. **R2** is an older spec with the plain linear evaluator `linear-r1` and no race table. The full spec strings are in `cpp/results/mac-classical-2026-11-15.txt`.
- **SMALL, LARGE, gen11:** network sizes of the `classic1` network ([network](network.md)): SMALL has 32 channels, LARGE 64. SMALL and LARGE use generated weights (the **u5 weights**: `Parameters.uniform(config, 5, sqrt(6 / 7C))` with C the channel count); gen11 is the real trained LARGE network of loop 4, generation 11 (see [training](training.md)).
- **Batch:** the number of positions per forward call. **Evaluations per second (evals/s)** are positions per second.
- **Golden files:** files written by Java exporters and read by the C++ tests and benchmarks (positions, move lists, evaluation values, fingerprint lines, weights, encoded inputs, reference outputs), each with a SHA-256 in `MANIFEST.tsv`. They are generated and git-ignored (`cpp/golden/`).
- **Hardened and unchecked builds:** the C++ build with standard-library bounds checks (`release-hardened`: libc++ hardening `FAST` on the Mac, `-D_GLIBCXX_ASSERTIONS` with g++) and without them (`release`). The hardened build is the main C++ row, because the assignment asked for memory-safe C++ ([plan, TL;DR](cpp-comparison-plan.md)).
- **Anchor rows:** SMALL and LARGE at batch 256, measured at the start and the end of a session (on the Mac also in the middle) to detect drift of the machine.
- **Pair:** a Java process and a C++ process run back to back. A Java:C++ ratio is formed only from pairs.
- **Lanes:** float values per SIMD register (4 on the Mac's NEON, 8 with AVX2, 16 with AVX-512).
- **NCLOC:** non-comment, non-blank lines of code, counted by `cpp/scripts/ncloc.pl`.
- **R3:** a frozen reference player (`ReferencePositions.R3_SPEC`) whose spec string equals that of `BEST_SPEC`, so their fingerprint constants are equal; `linear-r3` is the 13-feature linear evaluator with the `bestGain` feature. Both are defined in [search-and-evaluation](search-and-evaluation.md).
- **Kernel-choice rule:** the GPU convolution uses the `naive` kernel (one thread per output) while bucket × channels ≤ 128, otherwise a tiled kernel: `simdgroup` (Metal matrix units) on Metal, `tiled` (local-memory tiles) on CUDA. It is the rule of `TornadoNetwork` ([tornado](tornado.md#convolution-and-dense-kernels)).
- **Bucket:** the batch rounded up to a power of two (at most 1,024). Each bucket has its own GPU plan and buffers, built at its first call.
- **Block:** the benchmark proper on AWS, run as one C++ process and then one Java process. A quotient of two blocks is not a pair.
- **`metal-copy`, `metal-shared`:** the two memory modes of the C++ Metal backend. `copy` keeps private device buffers and copies input and output with blit commands, as TornadoVM does; `shared` lets the CPU write the input and read the output directly, without a copy. **`cuda-pageable`, `cuda-pinned`:** the C++ CUDA backend with ordinary or page-locked (`cudaHostAlloc`) host buffers.
- **Programs:** `nn_bench` (C++ network benchmark), `nn_check` (C++ check of one implementation against the golden reference outputs), `cuda_test` (C++ test of the CUDA backend on the device), `search_bench` (C++ search benchmark), `CrossBench` (Java network benchmark with the same schedule as `nn_bench`), `KernelsBenchmark` (Java benchmark of the convolution kernels alone), `Bench` (Java search benchmark), `metal_test` (C++ test of the Metal backend on the device, `cpp/tests/metal_test.mm`).
- **Work package (WP):** a numbered step of the plan, for example WP6 (race play) or WP16 (kernel replay); see [plan, Work packages](cpp-comparison-plan.md#work-packages).
- **Owner, lead:** the owner is the person who owns the repository and the AWS account, sets the budget and approves each paid run; the lead is the coordinating agent that wrote the plan and its amendments.

## Scope

### What was compared

| Part | Java side | C++ side | Where measured |
|---|---|---|---|
| Classical search, `BEST_SPEC` and R2, including the race play of `BEST_SPEC` | engine, race table reader, evaluators, `AlphaBeta`, `Solitaire`, `RaceMove` | faithful port of the same classes (`cpp/src/engine`, `tablebase`, `search`) | Mac |
| Network forward pass on the CPU, scalar | `Network` | `ReferenceNet` | Mac |
| Network forward pass on the CPU, SIMD | `VectorNetwork` (Java Vector API) | `SimdNet` (compiler vector extensions), with FMA (`simd`) and without (`simd-nofma`) | Mac, AWS Xeon |
| Network forward pass on the GPU | `TornadoNetwork` (TornadoVM 7.3.1) on Metal and on CUDA | hand-written Metal backend (`metal-copy`, `metal-shared`) and CUDA backend (`cuda-pageable`, `cuda-pinned`), same kernels and same kernel-choice rule | Mac (Metal), AWS Tesla T4 (CUDA) |

"Faithful port" means the same algorithms, data structures, move order and tie-breaking; no optimisation beyond the Java code ([plan, Terms](cpp-comparison-plan.md#how-to-read-this-plan)). The GPU kernels are the TornadoVM kernels of [tornado](tornado.md#convolution-and-dense-kernels) rewritten in the Metal Shading Language and CUDA C, with the same summation order.

Machines: an Apple M3 Max (16 cores, macOS 26.6.2, Apple clang 21.0.0, JDK 25.0.3), and AWS `g4dn.xlarge` spot instances (4 vCPUs of an Intel Xeon Platinum 8259CL with AVX-512, one Tesla T4, CUDA 13.2, g++ 12.3, nvcc 13.2) (header of `cpp/results/mac-classical-2026-11-15.txt`; `cpp/results/aws-t4-2026-11-15.txt`, section Toolchain).

### What was left out on purpose

| Left out | Why |
|---|---|
| Training, MCTS, self-play, input encoding | Not part of the assignment. Both benchmarks read pre-encoded inputs from a golden file ([plan, Scope](cpp-comparison-plan.md#what-is-left-out-and-why-the-comparison-survives-it)). |
| Three-player rules | Alpha-beta supports two players only, in Java too; the C++ rule set refuses other values. |
| WP16, a replay of the kernels that TornadoVM generates inside the C++ harness | Optional work package; it needed the owner's explicit yes and was not started. It would have separated TornadoVM's code generation from its runtime cost. |
| The Java kernel benchmark on the T4 | About 4 minutes of paid time for rows that the Mac kernel table covers for the same kernel code; C++ per-kernel times on the T4 were recorded at no extra cost ([plan, Scope](cpp-comparison-plan.md#what-is-left-out-and-why-the-comparison-survives-it)). |
| Tensor cores, FP16 | The Java side computes in FP32; lower precision would break the tolerance and the like-for-like comparison. |

## Equality before time

No time was measured before the C++ code produced the Java results, and every timing run checks its own output again.

| Check | Result | Source |
|---|---|---|
| Engine: Zobrist keys, geometry, 2,000 random positions (hash, side, outcome, legal moves in generator order) | equal | commit `15774c3`; acceptance in [plan, WP2](cpp-comparison-plan.md#wp2-engine-in-c) |
| Linear evaluator `linear-r1` and `linear-r3` on the 2,000 positions | bit-exact (`-ffp-contract=off` everywhere; Apple clang would otherwise contract `a * b + c` into an FMA) | commit `8764c5b` |
| Fingerprint of `BestSpecFingerprintTest`: all 9 constants (positions checksum, game checksum at 1,000 and at 5,000 nodes, for R2, R3 and BEST) | reproduced with the `debug-asan`, `release` and `release-hardened` binaries; each output equals the Java golden file byte for byte | `cpp/README.md`, section "Race play and the games row (WP6)" |
| Race moves: the move from the precomputed race table for positions in which every piece has at least one row left to go ("rows>=1"), and otherwise from the beam-search planner `Solitaire.plan` | 332 of 332 rows equal in move and origin (261 table moves, 71 plan moves) | same |
| Every timed search | compared with the golden fingerprint line (move, score, depth, nodes); games: equal node totals and move counts in Java and C++ (9,000,000 nodes, 540 moves per run) | `cpp/results/mac-classical-games-2026-11-15.txt` |
| C++ scalar network against Java `Network` | logits bit-identical (0 ulp difference) on SMALL and LARGE; values within 1 ulp (the acceptance of [plan, WP9](cpp-comparison-plan.md#wp9-network-core), also stated in `cpp/src/nn/reference_net.hpp`) | `cpp/README.md`, sections "What was checked here" and "Ubuntu 22.04 container build"; commit `8b40a63` |
| C++ SIMD and Metal against the Java reference | within the bound 1e-4 + 1e-3 · \|ref\| of [network](network.md#numeric-conventions); largest error as a fraction of the bound: `simd` 0.0131 (SMALL), 0.0433 (LARGE); Metal 0.0196 (SMALL), 0.0499 (LARGE), 0.0068 (gen11), matching TornadoVM's 0.0196, 0.0499, 0.0069 | `cpp/README.md`, sections "Ubuntu 22.04 container build" and "Metal backend, first half (WP11a)" |
| CUDA on the T4: `cuda_test` | 6 passed, 0 failed, 244 checks, 120 comparisons with the C++ reference; largest error 0.0254 of the bound | `cpp/results/aws-t4-2026-11-15.txt`, section Correctness |
| CUDA on the T4: `nn_check` on all 1,024 golden positions | logits 0.0196 (SMALL), 0.0499 (LARGE), 0.0011 (gen11) of the bound | same |
| CUDA memory check: `compute-sanitizer --tool memcheck` on `cuda_test`, batches 1 and 67 | 0 errors, 184 checks | same |
| Linux build with the instance's compiler, before the paid run | every Linux source builds with g++ 12.3 (`ubuntu:22.04` container) under `-Werror`; tests pass | `cpp/README.md`, section "Ubuntu 22.04 container build" |
| Every network timing row | the last call of each case is checked against the reference; a failing case prints no row and exits 2. Mac: 450 rows, all `verified = ok`; T4: 167 and 228 rows | `cpp/results/mac-nn-2026-11-15.txt`, "Output checks"; the two AWS result files, "Status" |

One caveat from the T4 runs: the Java reference *outputs* computed on the instance differ in the last bits from the Mac's (weights, inputs and test cases are bit-equal). `nn_check` on the instance therefore compared against the instance's own reference files (`cpp/results/aws-t4-2026-11-15.txt`, section Correctness).

## Method and hygiene

The full method is in [plan, Measurement method](cpp-comparison-plan.md#measurement-method). In short:

| Rule | What it means |
|---|---|
| Self-ratio (classical) | Each process first searches the spec against itself. A ratio outside 0.98 to 1.02 means the machine is not quiet, and the session is invalid. |
| Interleaving | Java and C++ cannot share a process, so processes alternate: J, C, J, C, ... A cross-language ratio is the median of the adjacent pairs. |
| Thread CPU time | The classical search counts nodes per **CPU-second** of the searching thread, not per wall-clock second. |
| Anchors (network) | Each program's batch-256 rows at the start and the end (Mac: also the middle) must agree within 2 %. |
| Warm-up | Java JIT and TornadoVM compilation run in an untimed warm-up; C++ runs the same warm-up. Median of 3 rounds or repetitions, range reported. |
| n = 1 per instance | Each AWS run is one sample on a shared, virtualised machine. The two AWS runs are two samples, not an error bar. |

**Decision rule.** A cross-language difference counts as real only above the resolution of the measurement: 2 % for the classical search on the Mac, 5 % for the network on the Mac and on the T4 ([plan, Decision rule](cpp-comparison-plan.md#decision-rule)).

**Status labels** used for every number below:

| Label | Meaning |
|---|---|
| valid | The anchors of the row's program lie within 2 % (classical: all self-ratios within 0.98 to 1.02). |
| unsettled | The anchors moved by more than 2 %, or the row is a Mac GPU row. The number is reported, never smoothed or dropped. |
| indicative | A ratio built from at least one unsettled row; it shows a direction, not a size. |
| not resolved | A ratio within the threshold (2 % or 5 %), or pairs that disagree on the direction: no statement. |
| unpaired | A quotient of two blocks that did not run as adjacent pairs; orientation only. |
| unanchored | A cold-start figure; no anchor applies to it. |

**Two changes of the rules during the work**, both by the lead and recorded in the plan:

1. **GPU rows on the Mac** (Amendment of 2026-11-15). Five full Mac sessions failed the anchor rule. The CPU anchors were stable, but both GPU programs fell together by 20 to 30 % in the GPU-heavy middle of a session (TornadoVM LARGE batch 256: 33,487 / 23,891 / 30,798 evals/s at start / middle / end; C++ Metal: 33,034 / 25,737 / 29,242), while six isolated Metal runs from idle agreed within 1.5 %. This points to the power and clock management of the Apple GPU; the cause is not proven. The fifth session was booked with CPU rows valid or unsettled by their anchors and **every GPU row unsettled**; a GPU ratio comes only from pairs and says nothing below 5 % (`cpp/README.md`, section "GPU anchor drift on the Mac"; [plan, Amendment](cpp-comparison-plan.md#amendment-2026-11-15-lead)).
2. **Load limit on the Mac 4.0 instead of 2.0** (Amendment 2). The plan refused a session at a 1-minute load average above 2.0. The owner's desktop Mac has an idle base load of 3.0 to 3.7 (window server about 50 % of a core, audio routing about 30 %, terminal about 10 %), so 2.0 could never be met. The scripts use 4.0; what stays strict is: no foreign build or JVM process, at most 350 % CPU for all other processes, power adapter, thermal state, the self-ratio and the anchors. A row booked "valid" on the Mac is valid under this relaxed limit (`cpp/README.md`, section "Load limit of the measurement sessions"; [plan, Amendment 2](cpp-comparison-plan.md#amendment-2-2026-11-15-lead-mac-load-limit)). The network session started its cases at loads of 2.08 to 4.59; the highest, 4.59, is above even 4.0 (`cpp/results/mac-nn-2026-11-15.txt`, last section).

## Results: classical search

Mac, one thread, 64 reference positions with node-limited searches (20,000 nodes), and two full games with race play. All self-ratios lay within 0.98 to 1.02, so every row is **valid** under the relaxed load limit. Ratios above 1 mean C++ is faster.

| Row | Java | C++ hardened | C++ unchecked | Hardened / Java | Unchecked / Java | Source |
|---|---|---|---|---|---|---|
| `BEST_SPEC`, 64 positions, nodes per CPU-second | 1,643,900 | 2,613,882 | 2,938,248 | 1.60 (real) | 1.78 (real) | `cpp/results/mac-classical-2026-11-15.txt` |
| R2, 64 positions, nodes per CPU-second | 2,972,178 | 4,405,314 | 5,057,276 | 1.48 (real) | 1.70 (real) | same |
| `BEST_SPEC`, 64 positions + 2 games | 1,520,511 | 2,341,781 | 2,683,622 | 1.54 (real) | 1.77 (real) | `cpp/results/mac-classical-games-2026-11-15.txt` |
| `BEST_SPEC`, the 2 games alone | 1,470,129 | 2,256,910 | 2,586,917 | 1.53 (real) | 1.76 (real) | same |
| `BEST_SPEC`, CPU ms per move, 2 games | 11.337 | 7.385 | 6.443 | | | same |

**Cost of the checks.** The unchecked build is 1.124 (`BEST_SPEC`), 1.148 (R2) and 1.146 (games) times as fast as the hardened build, so the hardened build gives up 11 to 13 % of its throughput to the libc++ bounds checks (same files, "release/hardened").

**Java varies between processes.** An earlier valid session of the same day gave Java 1,564,435 for `BEST_SPEC` and a hardened/Java ratio of 1.67; Java's `BEST_SPEC` figure differs between its processes by up to 5 % (`cpp/results/mac-classical-2026-11-15.txt`, last paragraph). The ratio is therefore 1.5 to 1.7, not a sharper number.

**Program start.** From process start to the end of a one-position search, race table load included: Java 0.847 s, C++ hardened 0.260 s (C++/Java 0.307) (`cpp/results/mac-classical-2026-11-15.txt`, "Start-up").

## Results: network on the CPU

### Mac: C++ SIMD against the Java Vector API

Both sides use 4 lanes (NEON). Batch 256, evals/s, median over 3 pairs (`cpp/results/mac-nn-2026-11-15.txt`).

| Preset | Java Vector API | C++ `simd` (FMA) | C++ `simd-nofma` | `simd` / Java | Status of the ratio |
|---|---|---|---|---|---|
| LARGE u5 | 636.1 | 711.8 | 649.0 | 1.118 | valid, real (C++ faster by 11.8 %) |
| LARGE gen11 | 638.6 | 712.8 | not run | 1.115 | valid, real |
| SMALL u5 | 3,437.0 | 4,189.6 | 3,633.3 | 1.218 | indicative: the Java SMALL anchor moved by 2.85 % |

The same picture holds at batch 1 and 1,024 (LARGE 1.116 and 1.118, gen11 1.198 and 1.116). Without FMA, C++ is within 2 % of Java on LARGE (1.014 to 1.016, not resolved); the Java Vector API always uses FMA. The scalar C++ reference is 1.079 times as fast as Java's scalar `Network` at LARGE batch 1 (valid, real). On the Mac the hardened `simd` build costs nothing measurable: unchecked over hardened 1.007 (SMALL) and 1.010 (LARGE), not resolved. For `simd-nofma` the unchecked build is the slower one, by 16.6 % (SMALL) and 19.2 % (LARGE); this is not explained and is not a cost of the checks (same file, "Cost of the checks").

### AWS Xeon (AVX-512): before and after the SIMD fix

The comparison row is C++ `simd` with 16 lanes against Java's 16-lane species, both 512 bit wide. Hardened build, batch 256, evals/s.

| Preset | Run 1 C++ | Run 2 C++ | Run 2 / run 1 | Java run 1 | Java run 2 | C++ / Java, pairs, run 1 | C++ / Java, pairs, run 2 |
|---|---|---|---|---|---|---|---|
| SMALL | 1,153.7 | 8,387.7 | 7.27 | 5,866.2 | 5,683.8 | 0.216 (indicative) | 2.223 (indicative) |
| LARGE | 203.8 | 1,197.4 | 5.88 | 897.8 | 919.3 | 0.184 (indicative) | 1.329 (indicative) |

Sources: `cpp/results/aws-t4-run2-2026-11-15.txt`, sections "SIMD fix, before and after" and "Java:C++ ratios from adjacent pairs"; `cpp/results/aws-t4-2026-11-15.txt`, section "Ratios C++ over Java".

**What happened.** Run 1 showed C++ 3 to 7 times *slower* than Java on the same CPU. An offline reading of the g++ 12 assembler (in an `ubuntu:22.04` container) found two defects of this port, not of C++: libstdc++ bounds checks inside the innermost loop of the hardened build, and at 16 lanes a copy of every vector load through a stack buffer. The fix cut each range once per call with a checked `std::span::first` and then indexed with raw pointers inside the loop (an [unsafe zone](#unsafe-zones-as-built)); the order of the floating-point operations did not change, and old and new code give bit-identical outputs (12 equal digests) (`cpp/README.md`, section "x86 code generation of the SIMD path").

**What is established and what is only indicative.**

- Established: the diagnosis. After the fix the hardened build is as fast as the unchecked one (unchecked over hardened 0.971 to 1.006 for `simd` and `simd-nofma` at 8 and 16 lanes, SMALL and LARGE), and 16 lanes beat 8 lanes (1.63 SMALL, 1.46 LARGE; run 1: 0.59 and 0.58) (`cpp/results/aws-t4-run2-2026-11-15.txt`, "Is the diagnosis confirmed?"). The two fixes went in together, so their shares cannot be separated, and the store-forwarding stall that explains the stack copy was never measured.
- Indicative only: the size of the C++ lead over Java. All 8 pairs and both block quotients put C++ ahead, but only the C++ LARGE anchor is valid (1.8 % drift); the C++ SMALL anchor drifted 2.7 %, the Java anchors 2.2 % (LARGE) and 25.7 % (SMALL). LARGE is tight: pairs 1.30 to 1.35, block quotient 1.30. SMALL is 1.48 against Java's block level and 1.83 to 2.25 against its anchor levels.
- **Java Vector SMALL switches level** on this instance type: about 5,000 to 5,900 evals/s in one state and about 3,600 to 3,800 in another, between runs of the same program about a minute apart, in both AWS runs. The cause (a neighbour on the shared host, the CPU clock under AVX-512 load, or the JVM) is not known (`cpp/results/aws-t4-run2-2026-11-15.txt`, "Pattern of the CPU anchors").
- In run 2, at half Java's width (8 lanes), C++ reaches 0.90 (SMALL) and 0.89 (LARGE) of Java (block quotients, unpaired).

## Results: network on the GPU

### Mac: Metal against TornadoVM on Metal (all rows unsettled)

The GPU clock drift makes every Mac GPU number depend on when it was taken (anchors of one program differ by up to 29 % within the session). Batch 256 anchors, evals/s (`cpp/results/mac-nn-2026-11-15.txt`, "Anchors"):

| Program | Preset | Start | Middle | End | Lowest / start |
|---|---|---|---|---|---|
| C++ Metal copy | LARGE | 33,034.2 | 25,737.3 | 29,242.0 | 0.78 |
| TornadoVM | LARGE | 33,486.6 | 23,890.9 | 30,797.8 | 0.71 |
| C++ Metal copy | SMALL | 147,111.6 | 126,392.4 | 138,095.0 | 0.86 |
| TornadoVM | SMALL | 141,987.3 | 123,505.6 | 141,593.4 | 0.87 |

What can still be said, from pairs (C++ over Java, same file, "Ratio C++ over Java"):

| Case | Ratio, median [lowest, highest pair] | Verdict |
|---|---|---|
| LARGE u5, batch 1, Metal copy | 1.109 [1.109, 1.111] | indicative, C++ faster by 10.9 % |
| LARGE u5, batch 256, Metal copy | 1.165 [0.983, 1.283] | no statement (pairs disagree on the direction) |
| LARGE gen11, batch 256, Metal copy | 0.997 [0.968, 0.999] | no statement (below 5 %) |
| SMALL u5, batch 256, Metal copy | 1.134 [1.036, 1.276] | indicative, C++ faster by 13.4 % |
| SMALL u5, batch 1, Metal copy | 1.024 [1.010, 1.024] | no statement (below 5 %) |

Latency at batch 1 (p50, LARGE u5): TornadoVM 0.847 ms, C++ Metal copy 0.786 ms, Metal shared 0.769 ms, all unsettled. At batch 1 on SMALL the GPU paths (0.41 to 0.43 ms) are slower than C++ `simd` on one CPU thread (0.235 ms, valid) (same file, "Latency at batch 1"). The kernel times and cold start are in [the TornadoVM breakdown](#the-tornadovm-share-in-detail).

### Tesla T4: CUDA against TornadoVM on CUDA (valid, two runs)

On the T4 the GPU anchors held within 1.3 % in both runs, so all GPU rows are **valid**. Evals/s; C++ CUDA is the median of its two hardened benchmark binaries, built for 8 and 16 CPU SIMD lanes (the CUDA code is the same in both) (`cpp/results/aws-t4-2026-11-15.txt` and `cpp/results/aws-t4-run2-2026-11-15.txt`, "Throughput"):

| Preset | Batch | C++ CUDA pinned, run 1 / run 2 | TornadoVM, run 1 / run 2 | Status |
|---|---|---|---|---|
| SMALL | 1 | 2,677.1 / 2,683.2 | 4,701.0 / 4,667.6 | valid |
| SMALL | 256 | 85,582.5 / 86,485.9 | 85,222.0 / 85,150.5 | valid |
| SMALL | 1,024 | 89,410.4 / 89,749.3 | 86,665.2 / 87,714.3 | valid |
| LARGE | 1 | 538.5 / 539.3 | 1,115.8 / 1,126.7 | valid |
| LARGE | 256 | 18,541.2 / 18,906.4 | 19,023.2 / 19,395.1 | valid |
| LARGE | 1,024 | 18,887.4 / 19,186.4 | 19,411.0 / 19,752.2 | valid |

**Batch 256, from pairs:** C++ over TornadoVM 0.979 (LARGE) and 1.021 (SMALL) in run 1, 0.979 and 1.029 in run 2. Both are below 5 %: **no statement**. Other batches have no adjacent pairs.

**Batch 1:** TornadoVM is faster. Latency p50, run 1: LARGE 0.895 ms against 1.857 ms for C++ pinned, SMALL 0.211 against 0.373 ms; run 2 repeats it (0.887 against 1.853, 0.213 against 0.372) (both files, "Latency at batch 1"). The rows are valid, the quotient (about 2.1 on LARGE and 1.8 on SMALL) is unpaired, but far outside 5 % and repeated on a second instance. Part of the cause is the combination of the kernel-choice rule and this port's `naive` kernel: at LARGE batch 1 the rule (`naive` while bucket · channels ≤ 128, the same rule as in `TornadoNetwork`) picks the `naive` convolution, whose 15 launches sum to 1.88 ms, against 1.21 ms with the `tiled` kernel. Even the tiled sum is above TornadoVM's whole call (0.894 ms, transfers included). The C++ `naive` launch geometry (32 channels × 8 rows per block) is a choice of this port, while TornadoVM lets its runtime choose; whether that explains the rest was not tested (`cpp/results/aws-t4-2026-11-15.txt`, "C++ CUDA kernel table").

**Repeatability.** Every GPU row of run 2 lies within 2.0 % of run 1, on a different instance (`cpp/results/aws-t4-run2-2026-11-15.txt`, "GPU rows against run 1"). The Java GPU feed benchmark (`gpuFeed`, CPU threads feeding the T4) gave 80,748 and 82,117 SMALL and 18,867 and 19,106 LARGE evals/s, run 2 within 1.7 % of run 1. Run 1 lies inside the range of the earlier runs of [cloud](cloud.md#gpu-feed) (80,736 to 81,633 SMALL, 18,402 to 19,029 LARGE); run 2 lies 0.4 to 0.6 % above it.

**Pageable against pinned host memory** (C++ only): at batch 256 pinned memory is faster by 5.6 % on SMALL (85,582.5 against 81,062.7, run 1) and equal on LARGE (18,541.2 against 18,533.8).

## The TornadoVM share in detail

The table puts TornadoVM next to the hand-written kernels, for LARGE u5. "Execute" is TornadoVM's one figure for host-to-device copy, kernels and device-to-host copy, which cannot be split. Mac figures are unsettled; T4 figures are valid unless marked.

| Item | Mac TornadoVM | Mac C++ Metal copy | T4 TornadoVM | T4 C++ CUDA pinned | Source |
|---|---|---|---|---|---|
| Kernel time per call, batch 256 | 7.069 ms (profiler, separate pass) | 7.245 ms (instrumented pass); 7.406 ms GPU time in the normal pass | 10.633 ms (profiler, separate pass) | 13.232 ms (`cudaEvent`) | `mac-nn-2026-11-15.txt`, "Kernel, device write and device read"; `aws-t4-2026-11-15.txt`, "TornadoVM breakdown" |
| Device path per call, batch 256 (copies and kernels) | 9.189 ms (execute) | 7.946 ms (commit and wait) | 13.075 ms (execute) | 13.545 ms (h2d 0.270 + kernel 13.232 + d2h 0.044) | same |
| Host copy in / out, batch 256 | 0.031 / 0.009 ms | 0.026 / 0.009 ms | 0.207 / 0.031 ms | 0.135 / 0.018 ms | same |
| Host priors, batch 256 (same code on both sides) | 0.042 ms | 0.034 ms | 0.143 ms | 0.086 ms | same |
| First call per bucket (plan build and compilation for TornadoVM) | 62 to 76 ms | 1.5 to 33.4 ms, of which pipelines and buffers 0.28 to 1.54 ms; library compile once per process 0.521 ms | 1,119 to 1,410 ms (one outlier 298 ms) | 3.6 ms (batch 1) to 56.5 ms (batch 1,024), mostly the call itself | `mac-nn-2026-11-15.txt`, "Plan build"; `aws-t4-2026-11-15.txt`, "TornadoVM breakdown" |
| Latency, batch 1, p50 | 0.847 ms | 0.786 ms | 0.895 ms | 1.857 ms | "Latency at batch 1" of `mac-nn-2026-11-15.txt` and `aws-t4-2026-11-15.txt` |
| Cold process to first result, batch 1 | 886.0 ms (JVM uptime) | 95.3 ms | 2,979.0 ms (unanchored, 3 rounds) | 222.2 ms (unanchored) | `mac-nn-2026-11-15.txt`, "Cold start"; `aws-t4-run2-2026-11-15.txt`, "Cold start" |
| Throughput, batch 256 / 1,024 (evals/s) | 27,469 / 28,900 | 31,994 / 32,987 | 19,023 / 19,411 | 18,541 / 18,887 | "Throughput" of `mac-nn-2026-11-15.txt` and `aws-t4-2026-11-15.txt` |

The two kernel figures in the first row come from different clocks: TornadoVM's profiler slows the call and has a floor of about 0.5 ms per task ([tornado](tornado.md#training-kernels)), while C++ reads GPU timestamps. They are not a kernel-quality comparison. On the T4 the profiler gives 10.633 ms against 13.232 ms for C++ (20 % less), but the whole device path differs much less: 13.075 against 13.545 ms (3.5 % less). The profiler figure is therefore not a kernel time comparable with `cudaEvent`.

**Cold start.** On the T4 a fresh TornadoVM process needs 13 to 21 times as long as a fresh C++ CUDA process to its first result: 2,979 to 4,404 ms against 209.5 to 231.6 ms over SMALL and LARGE, batch 1 and 256 (`cpp/results/aws-t4-run2-2026-11-15.txt`, "Cold start"; median of 3 processes each, spread at most 5 %). The TornadoVM time is the JVM start, the TornadoVM runtime start, and the Graal and NVRTC compilation of the bucket. On the Mac the factor is 8.3 to 9.3 (886.0 and 896.0 ms against 95.3 and 108.5 ms).

**What TornadoVM costs:**

- Start-up and compilation: seconds per process on the T4 and 62 to 76 ms per batch bucket on the Mac, against milliseconds for C++, whose kernels are compiled ahead of time (CUDA) or in 0.5 ms (Metal).
- Steady state: on the T4, nothing that the measurement can resolve at batch 256 (pairs within 3 %); at 512 and 1,024 the unpaired block quotients are 0.97 to 1.03. On the Mac, seven indicative cases put C++ ahead by 6 to 17 % (medians of the pairs) and one (gen11, Metal shared, batch 1,024) puts it 7.9 % behind, but the GPU clock drift leaves every Mac GPU ratio indicative or without a statement.
- The phases inside "execute" cannot be seen without the profiler, and the profiler changes the timing.

**What TornadoVM saves:**

- One Java source serves both GPU backends: 1,372 NCLOC against 1,714 NCLOC for the two C++ backends ([Effort](#effort)).
- TornadoVM chooses the launch geometry. On the T4 its batch-1 call is about twice as fast as the C++ port with its own choice.
- The same Java code ran on CUDA after five test fixes ([cloud](cloud.md#device-tests)). The C++ CUDA backend could not be compiled or run on the Mac at all: it needed a CPU emulation of the kernels, a GCC build of the Linux sources with a device-less CUDA stand-in (`cpp/scripts/build-gcc12-stub.sh`), a container build and a paid run to find out whether it compiles (`cpp/README.md`, sections "CUDA backend (WP12)" and "Ubuntu 22.04 container build").

## Effort

| Item | Java | C++ | Source |
|---|---|---|---|
| NCLOC, GPU inference path | TornadoVM path 1,372 (one source for Metal and CUDA) | Metal backend 868 (`metal_net.mm` + `kernels.metal`), CUDA backend 846 (`cuda_net.cu` + `kernels.cuh` + `cuda_backend.cpp`), together 1,714 | `cpp/results/mac-nn-2026-11-15.txt`, "Development effort" |
| NCLOC, ported Java code (classical part; `nn` inference) | 4,546; 1,506 | not found in the result files | [plan, Facts](cpp-comparison-plan.md#facts-this-plan-relies-on) |
| Clean build on the Mac | `nn` and `nn-tornado` compile: 10 s (warm Gradle daemon, includes build logic and `engine`) | `nn_bench` target: 2.0 s wall with all cores (9.8 s with one job); whole tree, 73 steps: 5.1 s wall | `cpp/results/mac-nn-2026-11-15.txt`, "Development effort" (single runs, context only) |
| Clean build on the T4 instance | not found | not found | |
| Size | `nn.jar` 139,087 bytes, `engine.jar` 25,802 bytes; `nn-tornado` 784 KiB of class files; TornadoVM SDK 26 MB | stripped `nn_bench` 414,448 bytes (hardened), with the CPU paths, the Metal backend and the device-less CUDA stand-in `cuda_net_stub.cpp` | same |
| Agent-hours | not recorded | not recorded | `cpp/results/mac-nn-2026-11-15.txt`: "Agent-hours per backend: not recorded, not stated." The plan's time box was 65 agent-hours for the whole assignment ([plan, Time box](cpp-comparison-plan.md#time-box-and-stop-rules)); how much was used is not recorded. |

## Memory safety

### The rules

The C++ code follows the rules of [plan, Memory-safety rules](cpp-comparison-plan.md#memory-safety-rules), each enforced by a tool:

| Rule | Enforced by |
|---|---|
| C++23, RAII for every resource; no `new`, `delete`, `malloc`, `free` outside the GPU wrappers | clang-tidy, `cpp/scripts/check-rules.sh` |
| No raw-pointer arithmetic or indexing outside named unsafe zones; buffers are `std::span`, `std::array` or `std::vector` | `-Wunsafe-buffer-usage -Werror` (clang); `check-rules.sh` rejects the exemption pragma and `memcpy` in any other file |
| No C arrays, C-style casts or `reinterpret_cast`; bytes are read with `std::copy_n` plus `std::bit_cast` | warnings and clang-tidy |
| File readers return `std::expected`; Java's wrapping arithmetic uses unsigned types | review, UndefinedBehaviorSanitizer |
| Bounds checks in the hardened build | libc++ hardening `FAST` (Mac), `-D_GLIBCXX_ASSERTIONS` (g++) |
| Every test runs under AddressSanitizer and UndefinedBehaviorSanitizer | `ctest --preset debug-asan`, the gate of every work package |
| GPU memory checks | `compute-sanitizer memcheck` (CUDA), Metal API and shader validation |

### Unsafe zones, as built

These files may use raw pointers; everything else must pass `-Wunsafe-buffer-usage` (`cpp/README.md`, section "Spike 1" and "x86 code generation of the SIMD path"):

| File | Why |
|---|---|
| `cpp/apps/args.hpp` | `argv[i]` is indexing of a raw pointer; converted once into `std::string_view`s |
| `cpp/src/nn/simd_net.cpp` | since the x86 fix: the hot loops index with raw pointers into ranges checked once per call; the gather indices from data are proven below the row count once in the constructor |
| `cpp/src/gpu/metal/metal_net.mm` | `[buffer contents]` returns a raw pointer; Objective-C++ |
| `cpp/src/gpu/metal/kernels.metal` | device code has no bounds checks |
| `cpp/src/gpu/cuda/cuda_net.cu` | `cudaMalloc`, `cudaHostAlloc`, device pointers; built by nvcc and g++ only, so `-Wunsafe-buffer-usage` never sees it |
| `cpp/src/gpu/cuda/kernels.cuh` | raw pointer indexing is the only way to write a kernel |

What no rule enforces: the lifetime of a `std::span` or `std::string_view` (only AddressSanitizer catches a dangling one, and only on tested paths); leaks on the Mac (LeakSanitizer does not run with AddressSanitizer on macOS arm64); the Linux build has only `-D_GLIBCXX_ASSERTIONS`, no sanitizers and no `-Wunsafe-buffer-usage`; GPU device code has no bounds checks at all ([plan, Unsafe zones](cpp-comparison-plan.md#unsafe-zones)).

### What the tools found, and what they did not

| Tool | Found | Source |
|---|---|---|
| `-Wunsafe-buffer-usage` | a planted `p[1]` on a raw pointer failed the build; `std::memcpy`, `fread` into `vector.data()`, `argv[i]` and two-argument `std::span` construction are flagged, which shaped the unsafe-zone list | `cpp/README.md`, "Spike 1" |
| AddressSanitizer in the CUDA kernel emulation | the mutants "row guard of the tiled store removed" and "wrong output offset" | `cpp/README.md`, "What was checked here" |
| Metal API and shader validation | no finding | `cpp/README.md`, WP11a and WP11b |
| `compute-sanitizer memcheck` on the T4 | 0 errors | `cpp/results/aws-t4-2026-11-15.txt`, Correctness |
| g++ 12.3 under `-Werror` | four false positives of GCC 12 (`-Wnull-dereference`, `-Wstringop-overflow`, `-Wrestrict`), worked around | `cpp/README.md`, "Ubuntu 22.04 container build" |
| Not found by any tool, found by the review | `nn_bench` read past the end of the reference arrays in the unchecked build (undefined behaviour) when a batch does not divide 1,024; a test called `.error()` on a `std::expected` that held a value (undefined behaviour). No test exercised either path, so the sanitizers could not see them | review result, [Review](#review-process-and-result) |

### Bounds checks in the inner loop cost about 2.4 times

On the Mac the hardened build costs 11 to 13 % in the search and about 1 % in the SIMD network path. On the Xeon with g++ 12, the first AWS run showed the 8-lane unchecked build 2.38 (SMALL) and 2.33 (LARGE) times as fast as the hardened one (`cpp/results/aws-t4-2026-11-15.txt`, "Cost of the checks"; unsettled rows). The assembler showed why: libstdc++ checked `x0[ch]` and two `subspan` calls on every iteration of the innermost loop, with compare-and-jump pairs between the fused multiply-adds and the loop pointers on the stack. After the kernel was rebuilt so that the checks run once per call (each range cut with a checked `std::span::first`, then raw pointers inside), the loop has 18 instructions with 8 FMAs and no jump to an assertion block in both builds, and the hardened build is as fast as the unchecked one (0.971 to 1.006, run 2) (`cpp/README.md`, "x86 code generation of the SIMD path").

The lesson: a bounds check per element is cheap only when the compiler hoists it. With g++ 12 it did not; the safe form that kept its speed was a checked cut once per call plus an unchecked inner loop, which moves that loop into an unsafe zone.

## Review process and result

After the implementation, a review workflow of AI agents ran: reviewers on the Claude model Opus per area, the specialist agents of the Claude Code review plugin `pr-review-toolkit` (silent failures, type design, tests), an adversarial verification of every blocker and major finding, fixes by agents on the Claude model Sonnet, and a final gate (all `ctest` presets, `check-rules.sh`, the tests of the AWS scripts against a fake AWS command (`scripts/cloud/test/run-all.sh`), the container build where the network code changed, and `scan-sensitive.pl`, which searches files for account ids, resource ids, IP addresses, host names, e-mail addresses and home paths).

| Count | Number |
|---|---|
| Findings reported | 74 |
| After merging duplicates | 71 |
| Reported as blocker or major, sent to verification | 31 |
| Confirmed | 30: 18 as major, 12 downgraded to minor; none as blocker |
| Refuted | 1 (a lifetime hazard of `ReferenceNet`: real in the type, but no call site triggers it) |
| Minor, not verified | 40 |

Source: the result of the review workflow (fields `counts`, `confirmed`, `refuted`, `fixes`). That output is not kept in the repository, so a reader cannot verify these counts there; the counts and findings are copied here, and the fix commits are on branch `exp/cpp`.

### The most important confirmed problems

| Problem | Severity | Fix | Commit |
|---|---|---|---|
| `nn_bench` checked the last call against the wrong reference rows when the batch does not divide 1,024: abort in the hardened build, read past the end in the unchecked one | major | the reference position wraps modulo 1,024, in C++ and in the Java `CrossBench` | `d75ed89` |
| The per-kernel timing passes discarded the result of `forward()`, so a failed call still produced timing rows | major | the passes stop with exit 2 and check their outputs; Metal buffer allocations are checked for nil | `766c888`, `782ba69` |
| The AWS cleanup check reported "nothing left" when an AWS describe call itself failed, so a paid instance could run on unnoticed | major | each call is checked; verification and cleanup end non-zero | `2711ad8` |
| `scripts/cloud/aws-spot.sh` (launches one spot instance, runs the suite, cleans up) exited 0 when the instance never finished or its bootstrap failed, and the retry runner `scripts/cloud/spot-retry.py` counted that as success | major | exit 6 (no completion marker) and exit 1 (failed bootstrap), read from the instance | `594fa64` |
| `cost.tsv` (the one-line cost file that `aws-spot.sh` writes per run: suite, launch time, seconds, price, estimate, worst case) was split over two lines on real AWS output; the fake AWS command of the script tests (`scripts/cloud/test/`) printed one line, so the test passed on output AWS never produces | major | first line only, validated as a number; the stub prints the real two lines | `594fa64` |
| On-demand cost assumed one region's price for every region | major | a required price per region, booked as "assumed" | `594fa64` |
| `book-aws.py` could not see failed or skipped steps (its pattern never matched the real format) | major | strict parsing; anything not OK fails, except the step `cpp-sanitizer`, which the plan allows to be skipped | `594fa64` |
| `scan-sensitive.pl` skipped a directory argument silently and reported clean | major | directories are scanned; a scan that read no line exits 2 | `71b0fc6` |
| `bench-mac.sh` wrote and announced a result file even when its result block failed | major | write to a temporary name, publish only on success | `7f15eee` |
| `check-rules.sh` rule R4 (`-ffp-contract=off` present) matched a comment and could never fail | major | reads the real flag values | `e2f6375` |
| Without a Metal device, `metal_test` skipped and passed | major | exit 1 unless `--allow-no-device` | `537ec4c` |
| 17 unsettled ratio rows of the Mac network results carried "real" | major | an unsettled ratio is never labelled real; results re-labelled | `2751c5c` |
| `LinearEvaluator` could be copied or moved while its incremental state held a raw pointer to it | minor | copy and move deleted, factories return `unique_ptr` | `cdda0a7` |

### What was not fixed, and why

- **The Metal kernel table** (`cpp/results/mac-nn-2026-11-15.txt`, section "Convolution kernel table") times the C++ side with fresh buffers and the GPU span of a whole command buffer, while Java reports the TornadoVM profiler's kernel time. The verification showed that the allocation lies outside the timed span and that the large factors come from the profiler's floor; only the labels were changed ("indicative only", both clocks named). The table was not re-measured.
- **The load limit.** The finding "every Mac result violates the plan's load rule of 2.0" was resolved by changing the rule (Amendment 2, 4.0 with reasons), not by measuring again. One network case started at 4.59, above even the new limit; this was stated, not explained.
- **The Mac network result files** were corrected with a text substitution, not regenerated, because the work directory of the session was no longer on the machine.
- **Tests that skip.** The gen11 tests skip without the real weights even with `STERNHALMA_GOLDEN_REQUIRED=1`; this is intended (real weights are local only).
- **A test that fails before the fix** was not shown for the Metal nil-allocation fix: a real failed allocation cannot be produced on the Mac, so the test simulates it through a test hook.

## Cost and cloud experience

| Item | Value | Source |
|---|---|---|
| Three runs of the C++ suite on `g4dn.xlarge` spot (one cut off, two complete) | 0.396 USD (estimate from the run times and spot prices, not the bill) | `cpp/results/aws-t4-run2-2026-11-15.txt`, "Cost of the cpp suite" |
| Complete runs | 1,724 and 1,684 counted seconds, 0.1452 and 0.1419 USD | same |
| Total AWS use of the project so far | about 0.70 USD (earlier tests about 0.3 USD by the owner's figure, plus 0.40 USD), about 0.63 EUR of the 5 EUR budget (13 %) | same |
| Cap of the C++ suite | 1.00 USD cumulative, enforced by `spot-retry.py`; not reached | same |
| Plan's expectation | about 27 minutes and 0.13 USD per run | [plan, AWS run](cpp-comparison-plan.md#aws-run) |

**The spot interruption.** The first launch was cut off by a spot interruption about 5 minutes after the start. The script noticed the loss only at its cleanup, 27 minutes later, so `cost.tsv` counts 1,828 s (0.1089 USD, an upper bound; about 0.02 USD if only the 5 minutes were billed, not checked against a bill). Commit `ec0ecac` changed `aws-spot.sh`: while waiting it checks the instance state, copies partial results every 5 minutes, and exits 4, the code on which the retry runner tries again (`cpp/results/aws-t4-2026-11-15.txt`, "Cost of the cpp suite").

**Quota.** The account's GPU vCPU quota was 0 in every region at first; the earlier CUDA tests waited until the account owner's quota requests were approved ([cloud](cloud.md#test-results-2026-11-14)). `spot-retry.py` runs a pre-flight per region and drops a region without quota, retries only on exit 4 (no capacity, no quota, or a spot interruption), and may fall back to on-demand after K spot attempts (`--on-demand-after K`) with a stated price per region.

## Reuse in the browser (planned, not done)

The owner plans a Vaadin Hilla UI (React) with an offline mode in which the classical search or the network inference runs entirely in the browser (WebAssembly or WebGPU), and wants to reuse parts of this C++ code there; this is why the code was kept portable. Nothing of it has been built or run yet.

| Part | State |
|---|---|
| `cpp/src/{core,engine,tablebase,search,nn}` | A grep (2026-11-15) found file reading (`<fstream>`, `<filesystem>`), `std::getenv` for the data paths, `<chrono>` time limits, and CommonCrypto for SHA-256 on macOS (a portable implementation is always compiled too, `cpp/README.md`, "Deviations from the plan, made in WP0"). The search uses `<format>` (`search/fingerprint.cpp`), which the Linux build rules exclude for libstdc++ 12; whether Emscripten's libc++ has it is **not yet checked**. Whether Emscripten builds them: **not yet checked**. |
| `cpp/src/gpu` (Metal, CUDA) | not portable; the Metal MSL kernels are the closest starting point for WGSL (WebGPU) |
| SIMD path | GCC/Clang vector extensions with a 4-lane fallback (`cpp/src/nn/simd_net.hpp`); `-march`/`-mcpu=native` only in the timing builds (CMake option `SH_NATIVE`); WebAssembly SIMD has 128 bit (4 lanes, like the Mac's NEON), FMA only with relaxed SIMD. A WebAssembly build: **not yet checked** |
| Hardened libc++ under Emscripten, Emscripten itself, threads through `SharedArrayBuffer` | **not yet checked** |
| Memory | the 32-bit address space (4 GB) holds the 1.3 MB LARGE weights; the race table `rows1.bin` (242 MB) is a large download and memory share per page; whether a browser accepts it is **not yet checked**. `BEST_SPEC`'s evaluator `racelin1-r3` loads rows1 (`cpp/src/search/player_spec.cpp`), so a build with `rows2.bin` (12 MB) or no table needs another evaluator or new code, with a strength loss that was **not measured** (sizes: [plan, Facts](cpp-comparison-plan.md#facts-this-plan-relies-on) and [Open questions](cpp-comparison-plan.md#open-questions)) |

No browser speed was measured. The nearest measured case is native C++ `simd` with 4 lanes on the Mac (LARGE 711.8 evals/s, SMALL 4,189.6 at batch 256, `cpp/results/mac-nn-2026-11-15.txt`); a WebAssembly figure needs its own measurement.

## Limits and open points

- **Mac GPU:** every row is unsettled. No absolute Metal or TornadoVM-on-Metal number holds without its time in the session, and no Java:C++ difference below 5 % is stated. A session that keeps the GPU clock stable (or records it) would settle this; none was run, because the plan's abort rule (stop after three invalid sessions in a row; five were run) was reached ([plan, Hygiene](cpp-comparison-plan.md#hygiene-both-parts)).
- **AWS CPU rows:** n = 1 per instance; two things are unexplained: Java Vector SMALL switches between two levels, and in run 1 the second C++ anchor run was about 34 % slower than the first, both at the start and at the end (`cpp/results/aws-t4-run2-2026-11-15.txt`, "Pattern of the CPU anchors"); all CPU ratios there are indicative.
- **C++ CUDA at batch 1:** the kernel-choice rule explains part of the gap; whether a different `naive` launch geometry or the `tiled` kernel at batch 1 closes it was not tested. A third AWS run with the kernel table at batch 1 to 16 and a forced `tiled` variant would answer it.
- **Kernel quality:** without WP16 the code generated by TornadoVM was not timed in the C++ harness, so "runtime cost" and "kernel quality" cannot be separated.
- **Not measured:** Java kernel table on the T4; gen11 throughput on the T4; a cold C++ CPU process with 16 lanes (the cold-start step of the AWS suite ran `nn_bench_l8`, the 8-lane build); build time and binary size on the instance; agent-hours.
- **Bit-identity on the instance:** `nn_check --impl reference` did not run in the AWS suite; the instance's reference outputs differ from the Mac's in the last bits.
- **The store-forwarding stall** that explains the 16-lane slowdown of run 1 is a reading of the assembler; it was not measured with performance counters.
- **A third AWS run** could settle: the C++ CPU lead over Java with valid anchors on both sides, the batch-1 CUDA gap with a forced `tiled` kernel, and the cold start of a 16-lane C++ process.

## How to reproduce

All commands run from the repository root of a checkout of branch `exp/cpp`, inside the devenv shell (`devenv shell`), which provides CMake, Ninja and clang-tidy; the compiler stays Apple clang. The race tables come from Git LFS (`git lfs pull`).

```sh
# local data of a checkout (never written into the repository)
export STERNHALMA_DATA_DIR=<data directory with loop4/gen-0011/candidate.safetensors>   # only for the gen11 rows
export STERNHALMA_TABLE_DIR=<directory with rows1.bin and rows2.bin>                   # default: tablebase/tables/
export STERNHALMA_GOLDEN_REQUIRED=1                                                    # a missing golden file fails the test

# golden files and the Java command lines
./gradlew --no-build-cache :search:goldenExport                          # classical golden files into cpp/golden
./gradlew --no-build-cache :nn:goldenExport -Psternhalma.golden.real=true # network golden files (drop the property without gen11)
./gradlew --no-build-cache :nn-tornado:goldenTolerance                   # TornadoVM tolerance figures in MANIFEST.tsv
./gradlew --no-build-cache :search:printRunCommand :nn-tornado:printRunCommand

# C++ build and tests
cd cpp
cmake --preset debug-asan && cmake --build --preset debug-asan && ctest --preset debug-asan
cmake --preset release-hardened && cmake --build --preset release-hardened
cmake --preset release && cmake --build --preset release
scripts/check-rules.sh
scripts/build-ubuntu-container.sh --platform linux/amd64                  # g++ 12.3 in an ubuntu:22.04 container
cd ..

# measurements on the Mac (no other build, JVM or agent may run; see Method and hygiene)
cpp/scripts/bench-mac.sh                                  # classical positions rows
cpp/scripts/bench-mac.sh --games 2 --specs best           # games row
python3 -I cpp/scripts/bench-nn-mac.py --detach           # network session, about 46 minutes; prints the log path
python3 -I cpp/scripts/bench-nn-mac.py --status
```

The AWS run uses the project scripts only; the profile and the region come from the environment and are never written into the repository:

```sh
export AWS_PROFILE=<profile> AWS_REGION=<region>
scripts/cloud/spot-retry.py --detach --regions <region>,<region> --attempts 6 --wait 300 --budget-usd 1.00 --suite cpp \
  -- --instance-type g4dn.xlarge --max-price 0.35 --max-minutes 45 --backend cuda --feed-seconds 30 --yes
scripts/cloud/spot-retry.py --status
```

- **Cost:** one complete run cost about 0.14 to 0.15 USD; the worst case per attempt is `--max-price` × `--max-minutes` / 60 = 0.26 USD; `--budget-usd` caps all runs of the suite together, counted from the `cost.tsv` files in `cloud-results/`.
- **Quota:** a new account needs a GPU spot or on-demand vCPU quota (4 vCPUs for `g4dn.xlarge`) in at least one region; a quota request can take days. On-demand needs `--on-demand-after K --on-demand-price <region>=<USD per hour>,...`.
- **Booking:** `cpp/scripts/book-nn.py` and `cpp/scripts/book-aws.py` turn a session's raw output into the result files (usage in [cpp/README.md](../cpp/README.md)). Run `cpp/scripts/scan-sensitive.pl` over every file before it is copied into the repository.
