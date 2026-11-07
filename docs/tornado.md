# TornadoVM in this project

**TL;DR:** TornadoVM 7.3.1 kernels run on the Metal GPU of the development Mac from an ordinary Gradle `Test` task, on the class path; no separate launcher is needed.
The build reads the JVM flags from the SDK's `tornado-argfile.template`, and it switches off JVM assertions for TornadoVM, because with `-ea` the TornadoVM backend fails to start.
`TornadoNetwork` runs the whole forward pass on the device as an nn `ForwardPass`, so `new Evaluator(new TornadoNetwork(parameters))` works; it agrees with the reference far inside the tolerance (largest error 0.13 of the bound).
A warm call of the whole network costs 0.3 ms (TINY) to 1 ms (LARGE) at batch 1. The GPU overtakes one CPU thread from batch 16 (SMALL) or already at batch 1 (LARGE), and reaches about 120,000 SMALL or 21,000 LARGE evaluations per second from batch 256 on, 38 to 39 times one Vector API thread (see [Measured speed](#measured-speed)).
TornadoVM's profiler is not free: the per-call costs of [Measured cost](#measured-cost) (about 1 ms) were measured with it, and a LARGE call at batch 1 takes 4.5 to 8.4 ms with it and 1.0 to 1.6 ms without.

## How to read this document

- To build and test: [Build and test](#build-and-test).
- To write a kernel: [Writing kernels](#writing-kernels) and [Pitfalls](#pitfalls).
- For speed: [Measured cost](#measured-cost).
- For the heads, the residual add and the device-resident parameters: [Heads, residual add and parameters](#heads-residual-add-and-parameters).
- For the hex convolution and dense kernels, and which kernel to use at which batch: [Convolution and dense kernels](#convolution-and-dense-kernels).
- For the whole network on the device (`TornadoNetwork`): [Forward pass on the device](#forward-pass-on-the-device).
- For evaluations per second against the CPU, and what they mean for MCTS leaf batching: [Measured speed](#measured-speed).
- The SDK installation itself is in [architecture](architecture.md#install-tornadovm).

## Build and test

Inside the devenv shell, `TORNADOVM_HOME` points at `tornado-sdk/current`.

```sh
devenv shell -- ./gradlew --no-build-cache :nn-tornado:test    # kernels on the GPU, checked against Java
devenv shell -- ./gradlew --no-build-cache :nn-tornado:probe   # prints device and timings (a main, not a test)
devenv shell -- ./gradlew --no-build-cache :nn-tornado:benchmark   # whole network: CPU paths against the GPU (about 7 minutes)
```

If Gradle reports a lock timeout, another build is running: wait and run the command again.

### What `nn-tornado/build.gradle.kts` does

| Concern | Solution |
|---|---|
| Compile classpath | `compileOnly` and `testCompileOnly` on `$TORNADOVM_HOME/share/java/tornado/tornado-api-7.3.1.jar`, the same build as the runtime. Only `tornado-api` is needed to compile. |
| Compile without the SDK | Fallback to `io.github.beehive-lab:tornado-api:7.3.1-jdk22plus` from Maven Central, non-transitive (its POM pulls in Graal, log4j and OSGi jars). The checksums are in `gradle/verification-metadata.xml`. The fallback jar differs in its SHA-256 from the SDK jar but has the same API. |
| Run time | The TornadoVM jars are **not** on the test class path. The JVM flags put them on the module path, and the project's classes stay on the class path (unnamed module). |
| JVM flags | The argument provider `TornadoJvmArgs` reads `$TORNADOVM_HOME/tornado-argfile.template`, replaces `${TORNADOVM_HOME}`, turns each `--flag value` line into two arguments, and drops `.` from `--module-path`. It declares the template as `@InputFile` and the SDK path as `@Input`, so it works with the configuration cache. |
| Extra flags | `--add-modules jdk.incubator.vector` (classes of `nn` use the Vector API), `--sun-misc-unsafe-memory-access=allow` (silences the JDK 25 warning about TornadoVM's `sun.misc.Unsafe` calls), `-Dtornado.recover.bailout=False`, `-Dsternhalma.tornado.flags=true`. |
| Assertions | `enableAssertions = false` plus `-ea:sternhalma...`: assertions stay on for project code only. See [Pitfalls](#pitfalls). |
| No SDK | `test` and `probe` are skipped with the warning `nn-tornado: skipping TornadoVM tests: TORNADOVM_HOME is not set (run inside 'devenv shell')`. The build succeeds. |

The template is a copy of what `tornado --printJavaFlags` prints for the installed backend and JDK.
It contains JDK-specific flags: `-XX:+EnableJVMCI` and a `--patch-module` with JVMCI 21 classes on JDK 22 to 26.
Reading the template, instead of copying the flags into the build script, keeps the build correct when the SDK or its backend changes.

### How tests skip

Every test calls `TornadoAssumptions.assumeTornado()` in `@BeforeEach`.
It asks `TornadoSupport.status()`, which probes once per JVM and never throws:

| `Availability` | Meaning | Test result |
|---|---|---|
| `USABLE` | Kernels can run on the default device. | runs |
| `NO_DEVICE` | The runtime started but found no device, or the backend reported no Metal or OpenCL platform. | skipped |
| `NO_FLAGS` | Module `tornado.runtime` is not in the boot layer: the JVM ran without the flags. | skipped, but **fails** if the build set `sternhalma.tornado.flags` |
| `START_FAILED` | The runtime is present but failed to start. | skipped, but **fails** if the build set `sternhalma.tornado.flags` |

The two failing cases exist because a skip would hide a broken build behind green tests.
That happened once while the build was written (see the first pitfall): all 12 tests were skipped and the build was green.
With the current rule, the same fault makes all 12 tests fail, which was checked by switching assertions on again.

### Running outside Gradle

The SDK's own `tornado-argfile` (expanded template) works with a class path:

```sh
java @$TORNADOVM_HOME/tornado-argfile -cp <classes> <MainClass>
```

The argfile has `--module-path .:…`, so run it from a directory that contains no jars or module directories.
`tornado --printJavaFlags` prints the same flags with the paths filled in.

## Writing kernels

The feasibility code is in `nn-tornado/src/main/java/sternhalma/tornado`:

- `TornadoSupport`: probe, default device, device by backend.
- `VectorAdd`: `sum[i] = a[i] + b[i]`, one execution per call.
- `BatchedMatMul`: `out[b][r] = Σc w[r][c] · in[b][c]`. Weights go to the device once, inputs and outputs move on every `apply`. The plan runs with the silent profiler, and `lastProfile()` returns an `ExecutionProfile` (compile time, kernel time, bytes moved).

The pattern:

```java
TaskGraph graph = new TaskGraph("matmul")
        .transferToDevice(DataTransferMode.FIRST_EXECUTION, weights)   // copied once, then resident
        .transferToDevice(DataTransferMode.EVERY_EXECUTION, inputs)    // copied before every execute()
        .task("multiply", BatchedMatMul::multiply, weights, inputs, outputs, rows, cols, batch)
        .transferToHost(DataTransferMode.EVERY_EXECUTION, outputs);
TornadoExecutionPlan plan = new TornadoExecutionPlan(graph.snapshot())
        .withDevice(device)
        .withProfiler(ProfilerMode.SILENT);
plan.execute();   // first call compiles; later calls reuse the kernel
```

- **Data types:** use `FloatArray` and the other off-heap types of `uk.ac.manchester.tornado.api.types`, not `float[]`.
- **Activations:** use `DeviceMath.relu` and `DeviceMath.tanh`, not `Math.max` and `TornadoMath.tanh`; on Metal those two drop NaN and fail for large arguments (items 9 and 10 of [Pitfalls](#pitfalls)).
- **Parallel loops:** `@Parallel` on a loop variable maps the loop onto work items. Two nested `@Parallel` loops give a 2D grid. A loop without `@Parallel` runs sequentially inside one work item.
- **Scalars:** `int` arguments are fixed when the task is defined. A different shape needs a new task graph and therefore a new compilation.
- **Keeping data on the device:** `FIRST_EXECUTION` copies once per plan. `BatchedMatMulTest.weightsStayOnTheDeviceAndTheKernelRunsThere` checks this with the profiler: the second call copies the input bytes only. To change resident weights, build a new plan or use `persistOnDevice` / `consumeFromDevice` (not tried yet).
- **Several executions:** keep the `TornadoExecutionPlan` and call `execute()` again. A new plan compiles the kernel again.
- **Releasing:** `TornadoExecutionPlan` is `AutoCloseable`; `close()` frees its device memory and throws the checked `TornadoExecutionPlanException`.
- **Checking that the GPU ran the kernel:** `ExecutionProfile.kernelNanos() > 0` only when the kernel ran on the device.

### Device selection

| Want | How |
|---|---|
| Default device | `TornadoSupport.defaultDevice()` (= `TornadoExecutionPlan.DEFAULT_DEVICE`). System properties `tornado.driver` and `tornado.device` choose it; both are 0 by default. |
| A backend | `TornadoSupport.device(TornadoVMBackendType.METAL)` or `OPENCL`. Empty if the SDK has no such backend. |
| For one plan | `plan.withDevice(device)`. |

One SDK archive contains one GPU backend (`etc/tornado.backend` is `metal-backend` here).
OpenCL on this Mac therefore needs the OpenCL archive: `scripts/setup-tornado.sh opencl` installs it and points `tornado-sdk/current` at it.
That switches the backend for every process that uses `tornado-sdk/current`.
Metal against OpenCL has **not** been compared yet.

## Pitfalls

1. **JVM assertions break the backend start.** Gradle runs tests with `-ea`. Then Graal's `InvocationPlugins$Checks.checkResolvable` runs while the Metal backend registers its plugins and fails with `NoSuchMethodError: jdk.internal.misc.Unsafe.getObject(Ljava/lang/Object;J)`: the vendored Graal 23.1 checks for a method that JDK 25 no longer has. The same code ran fine under `JavaExec`, which does not enable assertions. Fix: `enableAssertions = false` and `-ea:sternhalma...`.
2. **An unsupported kernel throws an `Error`.** A kernel that allocates `new float[i + 1]` fails at the first `execute()` with `TornadoInternalError: unimplemented: dynamically sized array declarations are not supported`. A kernel that throws an exception fails with `GraalError: should not reach here: Node implementing Lowerable not handled: …MonitorExit`. Both extend `java.lang.Error`, so `catch (RuntimeException e)` does not catch them. `BatchedMatMulTest.aKernelTheDeviceCannotRunFailsInsteadOfFallingBackToJava` pins the first case.
3. **No silent fallback to Java.** In 7.3.1, `tornado.recover.bailout` is `False` by default (read from `TornadoOptions`), and with `True` the allocating kernel failed in the same way. The build sets `False` anyway, so that a kernel that cannot compile can never pass a test by running on the CPU.
4. **No bounds checks on the device.** A kernel that reads `a.get(i + 1000)` past the end of a 16-element array returns 0 without any error.
5. **A missing `transferToHost` is silent.** Without it, the host array keeps its old contents after `execute()`.
6. **Missing flags.** Without the flags, `new TaskGraph(…)` throws `TornadoAPIException: [ERROR] Tornado API Implementation class not specified. Did you remember to add @tornado-argfile?`. `TornadoSupport` checks the boot layer first and reports `NO_FLAGS` instead.
7. **Start-up warnings.** `WARNING: module-info.class ignored in patch: …/jvmci-21.0.2.jar` and `WARNING: Using incubator modules: jdk.incubator.vector` are printed by every TornadoVM JVM. They are harmless.
8. **Fast math.** `tornado.metal.fastmath` is `False` by default. `VectorAddTest` relies on it: the device sum equals the Java sum bit for bit for 1,000,003 elements.
9. **Metal's `tanh` fails for large arguments.** `TornadoMath.tanh` compiles to the Metal `tanh` (generated source `f_13 = tanh(f_9);`). Measured on the M3 Max, 2026-11-07: on a grid of 1.5·10⁻⁶ from 43.5 it returned its first 0 at 43.668278 (and 0 at 44 and 44.3) and NaN from 44.36142 on, also for +∞ and `Float.MAX_VALUE`; the reference `(float) Math.tanh(x)` is exactly 1 from 9.010914 on. Between 8.521233 and 44 it often returns 1.0000001 or 0.99999994 where the reference gives 1, so it even leaves [−1, 1]. Negative arguments gave −1 correctly. No error is raised, and the flags `-Dtornado.metal.fastmath=False`, `-Dtornado.enable.fastMathOptimizations=False` and `-Dtornado.enable.mathOptimizations=False` do not change it. A saturated value head therefore gave 0 (a draw) or NaN. Fix: `DeviceMath.tanh` returns ±1 for \|x\| > 8.5 and calls Metal's `tanh` only inside. Clamping the argument instead (`x > 20 ? 20 : x < -20 ? -20 : x`) is **wrong**: on the device it turned NaN into 1.
10. **`Math.max` drops NaN.** It compiles to Metal's `fmax`, which returns the other operand for a NaN: `Math.max(NaN, 0f)` gave 0 on the device, where Java gives NaN. A ReLU written with it turned a NaN weight into plausible finite outputs of the whole network. `DeviceMath.relu` (`v < 0f ? 0f : v`) kept NaN on the device. It lets the negative denormal `-Float.MIN_VALUE` through (the device compares it as −0), and keeps −0 where `Math.max` gives +0; both lie within the tolerance. Every kernel of the module uses `DeviceMath` for ReLU and tanh; `DeviceMathTest` checks both helpers on the device, tanh over 1.2 million arguments from −1000 to 1000, ±∞ and NaN.

### Debugging

| Property | Effect |
|---|---|
| `-Dtornado.printKernel=True` | Prints the generated Metal source (also `plan.withPrintKernel()`). |
| `-Dtornado.threadInfo=True` | Prints the grid and work-group sizes of each launch. |
| `-Dtornado.debug=True`, `-Dtornado.fullDebug=True` | Runtime debug output. |
| `-Dtornado.metal.source.dump=True` | Writes the Metal sources to `tornado.metal.source.dir` (default `/var/metal-compiler`). |
| `withProfiler(ProfilerMode.CONSOLE)` | Prints the profiler JSON after each execution. |

These property names come from the 7.3.1 runtime jars; only `withProfiler(SILENT)` has been used so far.
For a Gradle test, add the property with `systemProperty(…)` in `nn-tornado/build.gradle.kts` or run the probe.

## Measured cost

Measured on 2026-11-07 with `:nn-tornado:probe` on an Apple M3 Max (Metal, JDK 25.0.3).
Other agents loaded the CPU during the run, so the numbers are rough.
The kernel is the naive `BatchedMatMul` (one work item per output, no tiling), with 1,440 inputs as in the SMALL network.

| rows × cols, batch | First call | of which compile | Warm call (median of 20) | Kernel time | GFLOP/s (warm wall time) |
|---|---|---|---|---|---|
| 64 × 1440, 1 | 271 to 310 ms | 117 to 186 ms | 1.1 to 1.4 ms | 0.12 ms | 0.1 to 0.2 |
| 64 × 1440, 64 | 49 ms | 38 ms | 2.8 ms | 0.74 ms | 4.3 |
| 256 × 1440, 256 | 57 ms | 35 ms | 3.1 ms | 1.1 ms | 61 |
| 256 × 1440, 1024 | 55 ms | 30 ms | 5.3 ms | 3.0 ms | 142 |

- **First call in a JVM:** about 0.3 s. It includes the runtime start and the first Graal compilation. Each further plan compiles its kernel again, in 30 to 40 ms.
- **Fixed cost per call:** about 1 ms with only 0.12 ms of kernel time, so most of a warm call is dispatch and transfer. At batch 1 this allows at most about 1,000 calls per second, fewer than the 1,760 SMALL evaluations per second of the CPU Vector API path ([network](network.md#measured-cpu-speed)). The GPU needs batches.
  **Later finding:** these plans ran with `ProfilerMode.SILENT`, and the profiler is a large part of this cost. The whole TINY network (4 tasks) takes 0.3 ms per call at batch 1 without the profiler and 1.5 to 3.9 ms with it ([Measured speed](#measured-speed)).
- **Data movement:** in the warm calls only the inputs (`batch · cols · 4` bytes plus a 16-byte header) go to the device; the weights stay resident.
- **Accuracy:** against a double-precision reference, the largest absolute error of `BatchedMatMul` was 4.2·10⁻⁶ (37 × 129, batch 17, values in [−1, 1)). This is far inside the project tolerance 1e-4 + 1e-3 · |ref|.

## Heads, residual add and parameters

**TL;DR:** `OpsKernels` has the kernels after the convolutions: residual add with ReLU, policy head and a fused value head (mean pooling as a work-group reduction, both dense layers, tanh). `OpsHeads` runs both heads for a fixed batch and returns logits and values in one transfer. `OpsParameters` holds all tensors of a network as `FloatArray`s that stay on the device, and `reload()` updates them in place after a training step. All of them agree with the reference far inside the tolerance (largest error 0.0137 of the bound).

### Classes

| Class | Content |
|---|---|
| `OpsKernels` | Static kernels. `residualAddRelu(conv, skip, out, batch, C)`: `out = max(conv + skip, 0)` on the cell rows, 0 on row 121, NaN kept; `out` may be `conv`. `policyHead`: one work item per (position, logit). `valueHead`: `KernelContext`, one work-group of `VALUE_GROUP` = 256 work items per position. |
| `OpsHeads` | A plan for both heads with the trunk activation [batch][122][C] streamed in. `addTasks` and `schedule` add the same two tasks to another graph; the full forward pass uses these, so the trunk activation never leaves the device. |
| `OpsParameters` | One `FloatArray` per tensor of `Parameters.shapes`, in the same layout. `declareOn(graph)` adds them all with `FIRST_EXECUTION`. `residency(plan)` gives a tracker; call `beforeExecute()` before each `execute()`. After `reload()`, that sends the new values once per plan. |

Output layout of the heads (**project decision**): one buffer of `batch · (242 + P)` floats, first the logits [batch][121][2], then the values [batch][P]. `OpsHeads.unpack` copies the two parts into the arrays of `ForwardPass.forward`. The legal-move softmax stays on the host.

### How the value head reduces

With C ≤ 256, `parts` = 256 / C work items share a channel. Work item t sums channel t mod C over the cells t / C, t / C + parts, …, so neighbouring work items read neighbouring floats. After a local barrier, one work item per channel adds the `parts` partial sums from local memory and divides by 121. With C > 256, each work item sums whole channels. Dense C → H and dense H → P follow in the same work-group, after local barriers, from local memory. The local arrays have fixed sizes (256, and 1024 = the `NetworkConfig` limits for C and H).

### Tests and what they catch

`OpsKernelsTest` and `OpsHeadsTest` compare against `ReferenceOps` and `Network` with the tolerance of [network](network.md#numeric-conventions), batch sizes 1, 3, 64 and 513:

- Each head against the reference operations, for C = 8, 32, 64 (which divide 256), 100 (does not), 300 (> 256), H = 300 (> 256), C = H = 1, two and three players. Padding rows of the trunk are NaN, so a head that reads row 121 fails. The test also checks that at least a quarter of the values are not saturated, so tanh is really tested.
- The heads on top of the reference trunk against `Network.forward`, for TINY, SMALL and LARGE, with `Parameters.init` and with `Parameters.uniform` at the init bound.
- Hand-computed cases: both heads with C = 2, H = 3, P = 3 and two positions; pooling that must include cell 120 and divide by 121, for C = 1 and for C = 300 (the path with one work item per channel, cell 120 set in channels 0 and 299); the residual add with NaN, negative sums and garbage in the padding row.
- Saturation: dense2 biases of 44.3, 44.5, 50, 1000, +∞ and −1000 must give values of exactly ±1, bit for bit ([Pitfalls](#pitfalls), item 9).
- NaN: a NaN in a cell row of one position (not the padding row) must give NaN values and NaN logits for that cell, and leave the other positions alone, for C = 32 and C = 300.
- Barriers under load: shapes whose writers and readers of local memory lie in different SIMD groups (C = 1 with H = 1024, C = 2 with H = 512, C = 8 with H = 1024, C = H = 1024), batches 256 to 1024, four calls per plan, positions of very different scale.
- `reload()`: before it, the old values; after it, the new values in two plans that share one `OpsParameters`.

Mutation check (2026-11-07): each of these bugs, put into the code one at a time, made at least one test fail: tanh left out; pooling over 120 cells; division by 122 (both pooling paths); dense1 weight transposed; ReLU of dense1 left out; dense2 reading the dense1 weights; one partial sum dropped; the batch index of the values lost; "from" and "to" swapped; residual add not zeroing row 121; residual ReLU written as `v > 0 ? v : 0` (loses NaN); `reload()` not counting the version.

Second round (2026-11-07, after a review), each killed: Metal's `tanh` in the value head instead of `DeviceMath.tanh`; dense1 ReLU written as `sum > 0 ? sum : 0` (NaN lost); pooling of the C > 256 path without cell 120 (now pinned by the hand-computed C = 300 case, not only by a random one); the barrier between pooling and dense1 removed (3 of 3 runs, by C = 1, H = 1024); the barrier between dense1 and dense2 removed (3 of 3 runs, by C = H = 1024). Before the barrier cases existed, both barrier mutants survived every test.

Largest error as a fraction of the bound: 0.0049 (logits) and 0.0045 (values) against the reference operations; 0.0068 (logits) and 0.0137 (values) against `Network` (SMALL, `Parameters.init`, batch 64). The residual add equals the reference bit for bit.

### Measured cost of the heads

`OpsProbe` (a main in the test sources; run it with the SDK argfile, see [Running outside Gradle](#running-outside-gradle)), 2026-11-07, M3 Max, CPU loaded by other agents. Kernel time is the device time of the warm call, median of 30:

| Preset | Batch | Both heads, kernel time | Residual add, kernel time |
|---|---|---|---|
| SMALL | 1 | 0.034 ms | 0.009 ms |
| SMALL | 256 | 0.070 ms | 0.058 ms |
| SMALL | 1024 | 0.169 ms | 0.195 ms |
| LARGE | 64 | 0.067 ms | 0.032 ms |
| LARGE | 1024 | 0.330 ms | 0.367 ms |

- The residual add of LARGE at batch 1024 moves 96 MB in 0.37 ms, about 260 GB/s: it is bound by memory bandwidth. A convolution that adds the residual itself saves this pass (the `Kernels...` convolution can do that).
- Both heads read the trunk activation once each; at LARGE batch 1024 that is 64 MB in 0.33 ms. Fusing them would save about half, but the heads are small next to the convolutions.
- The wall time of `OpsHeads.apply` (0.7 to 3.2 ms) is mostly the fixed cost per call and the transfer of the trunk activation; in a full forward pass the trunk stays on the device.

### Findings and pitfalls

1. **The value head needs its grid.** Without `withGridScheduler`, TornadoVM runs a `KernelContext` kernel on a default grid, and the values are wrong **without any error** (checked: the value tests fail). A `GridScheduler` that names no task of the plan fails with `TornadoRuntimeException: GridScheduler Name not registered in any task-graph`. Grid names are `graphName.taskName`.
2. **An unused local array breaks the build of the kernel.** A `KernelContext` kernel whose local array is never used fails at plan creation with `TornadoBailoutRuntimeException: Unable to build sketch for method: valueHead (-1|LocalArray)`. This is a `RuntimeException`, unlike the errors of [Pitfalls](#pitfalls) item 2.
3. **Only arrays that a task reads are copied.** `declareOn` adds all tensors of the network to a graph, but a graph with the head tasks only sent the head tensors (SMALL, batch 64: 1,066,768 bytes in the first call = trunk + output buffer + head tensors + 16-byte headers). Declaring the full set in every graph is therefore free.
4. **`plan.transferToDevice(arrays)` updates resident data.** `OpsParameters.reload` uses it for arrays that the graph declared with `FIRST_EXECUTION`. The copy is not counted in the profile of the next `execute()`. The test checks the effect on the results, not the bytes.
5. **The output buffer is copied to the device in the first call.** It is not declared with `transferToDevice`, but the byte count of the first call includes it (see item 3). Later calls do not send it.
6. **`junit-jupiter-params` does not work in this test task.** The SDK puts its own JUnit jars on the module path, so `@ParameterizedTest` fails with `IllegalAccessError: … module org.junit.platform.commons does not export org.junit.platform.commons.util`. Loop over cases inside a `@Test` instead.

## Convolution and dense kernels

**TL;DR:** `KernelsHexConv` has three kernels for the hex convolution of [network](network.md#gather-table): gather, matrix multiplication, bias, optional residual add, optional ReLU, padding row written as 0. `naive` uses one work item per output, `tiled` uses local-memory tiles and a 4 × 4 register tile per work item, and `simdgroup` uses the Metal SIMD-group 8 × 8 matrix units. All three agree with `ReferenceOps` far inside the tolerance (largest error 0.0127 of the bound). From batch 64 on, `simdgroup` and `tiled` are 1.5 to 3 times as fast as `naive` for SMALL and 4 to 11 times for LARGE. For the LARGE block convolution at batch 512 they reach 1.9 to 3 TFLOP/s of kernel time, 50 to 85 times the Vector API path on one CPU core. At batch 1, `naive` has the shortest kernel time, but each call is dominated by about 0.3 ms of fixed cost. `KernelsDense` covers the policy head and both value-head layers, one work item per output.

### Classes

| Class | Content |
|---|---|
| `KernelsHexConv` | Static kernels `naive`, `tiled(KernelContext, …)` and `simdgroup(KernelContext, …)`, all with the arguments `(in, gather, weight, bias, res, out, batch, inChannels, outChannels, residual, relu)`. `tiledGrid` and `simdgroupGrid` give the launch grids. `res` is read only if `residual = 1`. |
| `KernelsHexConvLayer` | One layer of fixed shape and batch with a `Variant` (`NAIVE`, `TILED`, `SIMDGROUP`). Weight, bias and gather table stay resident; input, residual and output move on every `apply`. The package-private `addTask(graph, grids, variant, …)` adds the task and its grid to any graph, so a forward pass can chain the convolutions in one graph. |
| `KernelsDense` | Static kernel `dense(in, weight, bias, out, groups, groupRows, inGroupRows, inSize, outSize, activation)` with activation `NONE`, `RELU` or `TANH` (`DeviceMath.relu` and `DeviceMath.tanh`). The policy head is `groupRows = 121, inGroupRows = 122`, which skips the padding row. |
| `KernelsDenseLayer` | One dense layer of fixed shape, the same transfer pattern as `KernelsHexConvLayer`. |

`OpsKernels` (previous section) also has a policy head and a fused value head. `KernelsDense` is the generic layer that was asked for. The forward pass can use either.

### How the optimised kernels work

The convolution is a matrix product `out[m][o] = Σj A[m][j] · W[o][j]`. Here m is a row of the batch (batch · 122 rows, padding rows included and written as 0), and A is the gathered input with 7 · C_in columns. Both optimised kernels load a slice of A through the gather table into local memory, together with the matching slice of W, then multiply from local memory. Rows, channels and columns beyond the shape are masked, so no size has to be a multiple of a tile.

| Kernel | Work group | Tile (rows × channels × columns per step) | Inner step |
|---|---|---|---|
| `tiled` | 8 × 16 = 128 items, 2D grid | 64 × 32 × 32 | each item: 4 rows × 4 channels in 16 registers, interleaved (row `ty + 16i`, channel `tx + 8j`), local tiles padded by one column against bank conflicts |
| `simdgroup` | 4 SIMD groups of 32 = 128 items, 1D grid | 32 × 32 × 32 | each SIMD group: 2 × 2 blocks of 8 × 8 with `simdgroupMatrixMultiplyAccumulate`. W is read with `simdgroupMatrixLoadTransposed`. The result goes through a local tile, then bias, residual and ReLU. |

### Tests and what they catch

`KernelsHexConvTest` runs 117 cases, `KernelsDenseTest` 6 tests.

- **Every layer shape of TINY, SMALL and LARGE:** `conv_in` (7 → C, ReLU), conv1 (ReLU), conv2 (residual, ReLU), and a layer without residual or ReLU. Each runs with all three kernels at batch 1, 3 and 67; 3 · 122 and 67 · 122 rows fill no tile evenly, and C = 8 masks three quarters of a channel tile. The weights and **biases** are random at the init bound, the inputs random in [−1, 1). The test checks that ReLU clips at least a fifth of the outputs, and that the layer without ReLU keeps at least a fifth negative, so a missing or extra ReLU fails.
- **The optimised kernels against `naive`** (C = 32 and 64, batch 9, with residual).
- **A hand-computed case:** one input channel and 7 one-hot output channels, cells 0 and 60 in two positions. The expected neighbour values are written down by hand, so the case pins the neighbour order, the bias, the padding entries and the batch offset.
- **Every element is written:** the device output buffer starts as NaN and the bias is 100. A skipped element stays NaN, and a padding row computed like a cell gets at least the bias.
- **The comparison itself can fail:** kernels fed with a gather table that has directions 0 and 1 swapped, with a zero bias, or without the residual, all miss the reference by more than 10 times the bound.
- **Residency:** the second call copies the input only, and later changes to the caller's weights are not seen. A layer with residual gets a new residual in each of three calls.
- **NaN:** a NaN weight must make its output channel NaN in every cell, for every kernel, with ReLU, with residual and ReLU, and without either ([Pitfalls](#pitfalls), item 10).
- **Barriers under load:** the LARGE block shape at batch 256 with residual and the LARGE `conv_in` (7 → 64) at batch 67, eight calls with new inputs on one layer each, every kernel.
- **Dense:** the policy head with NaN in the padding rows (a kernel that reads row 121, or steps through the batch by 121 rows, fails), dense1 with ReLU, dense2 with tanh for 2 and 3 players, a hand-computed case with unused rows set to 1e30, and tanh and ReLU of single values: ±44 to ±∞ must give exactly ±1, NaN must stay NaN.

Mutation check (2026-11-07): `tiled` with one register row read from the wrong place made 39 cases fail. `simdgroup` multiplying the wrong 8 × 8 weight block made 26 cases fail.

Second round (2026-11-07, after a review), each killed: `Math.max` as ReLU in `naive`, and in `storeOne` (`tiled` and `simdgroup`); the residual sent only once (`FIRST_EXECUTION`); `KernelsDense` with `Math.max` and with Metal's `tanh`; `DeviceMath.tanh` without the bound, with the bound at 10 (a result of 1.0000001 at 9.69) and as an argument clamp (NaN became 1); `DeviceMath.relu` as `Math.max`. The barrier at the end of a k step of `simdgroup` removed: with four calls of the block shape only, killed in 2 of 3 runs, then with eight calls in 3 of 6 runs; with the `conv_in` shape added, in 6 of 6 runs. The same barrier of `tiled` removed: 3 of 3 runs, also by the random cases. Races depend on timing, so a later run can still miss one.

Largest error against `ReferenceOps`, over all cases: 3.58·10⁻⁶ absolute, 0.0127 of the bound (convolution, the same for all three kernels); 9.5·10⁻⁷, 0.0034 of the bound (dense). On this device, the three convolution kernels gave **bit-identical** results to each other. `naive` and `tiled` add in the same order (ascending column, bias last); for `simdgroup` the agreement was observed, not derived.

### Measured kernel speed

Run `devenv shell -- ./gradlew --no-build-cache :nn-tornado:kernelBenchmark` (about 1 minute). The benchmark uses the block convolution C → C with ReLU. All arrays are copied to the device once, the output stays there (`UNDER_DEMAND`), and the run checks that warm calls copy and compile nothing. There are 30 warm-up calls, then at least 1 s or 20 calls; the table shows medians. FLOPs count the matrix work only (2 per multiply-add, 2 · batch · 121 · C · 7C), as [network](network.md#cost-per-evaluation) does. Measured on 2026-11-07, M3 Max, load average 12 (other agents running):

| Shape | Batch | `naive` kernel ms (GFLOP/s) | `tiled` kernel ms (GFLOP/s) | `simdgroup` kernel ms (GFLOP/s) | Wall time per call, best kernel |
|---|---|---|---|---|---|
| SMALL 32 → 32 | 1 | 0.032 (55) | 0.203 (8.5) | 0.120 (15) | 0.29 to 0.33 ms (`naive`) |
| SMALL 32 → 32 | 64 | 0.32 to 0.41 (270 to 340) | 0.221 (500) | 0.222 (500) | 0.45 to 0.46 ms |
| SMALL 32 → 32 | 512 | 0.85 to 1.25 (710 to 1,050) | 0.43 to 0.52 (1,700 to 2,080) | 0.39 to 0.40 (2,200 to 2,250) | 0.63 to 0.72 ms |
| LARGE 64 → 64 | 1 | 0.212 (33) | 0.27 to 0.38 (18 to 26) | 0.219 (32) | 0.42 to 0.43 ms |
| LARGE 64 → 64 | 64 | 1.34 to 1.48 (300 to 330) | 0.22 to 0.31 (1,430 to 1,980) | 0.32 to 0.39 (1,130 to 1,400) | 0.49 to 0.56 ms |
| LARGE 64 → 64 | 512 | 11.4 to 12.8 (280 to 310) | 1.27 to 1.91 (1,860 to 2,800) | 1.19 to 1.69 (2,100 to 2,990) | 1.77 to 2.06 ms |

The ranges cover the three runs with all three kernels. An earlier run at load average 19, before `simdgroup` existed, had `tiled` at 0.92 ms (SMALL, batch 512) and 0.47 ms (LARGE, batch 64), outside these ranges. The Vector API path (`VectorNetwork`, one thread, whole network, matrix-work GFLOP/s) reached 2,256 to 2,907 SMALL and 418 to 530 LARGE evaluations per second, that is 32 to 41 GFLOP/s (SMALL) and 35 to 45 GFLOP/s (LARGE), the same at batch 1, 64 and 512.

- **Batch 1:** the kernel time is tiny (0.03 to 0.2 ms), but a call costs about 0.3 to 0.4 ms of dispatch. Tiles are too large for 122 rows: `tiled` has 2 to 4 work groups and loops over all columns sequentially. Use `naive` for batches up to about 8 (not measured between 1 and 64).
- **Batch 64 and more:** use `simdgroup` or `tiled`. Neither wins clearly; the differences between the two are smaller than the run-to-run variation, except at batch 512, where `simdgroup` was ahead in every run.
- **`naive` collapses for C = 64** (about 300 GFLOP/s at batch 64 and 512). This is probably the work-group shape that TornadoVM picks for the 2D `@Parallel` loop; not analysed.
- **The ceiling of about 3 TFLOP/s is the same for both tiled kernels**, so the multiply is probably not the limit. More likely limits are the gather (an integer division and a scattered load per element of A) or the occupancy. A variant of `simdgroup` with tiles 64 channels wide, which gathers A only once for C = 64, was **not faster** (2.7 to 2.9 TFLOP/s at LARGE batch 512, slower at C = 32), so it was removed.
- In a forward pass with the trunk on the device, a LARGE batch of 512 costs 12 block convolutions of about 1.2 to 1.7 ms each, plus `conv_in` and the heads. That is about 15 to 22 ms, or 23,000 to 34,000 positions per second; this is an estimate, not a measurement of a whole forward pass.

### Findings

1. **TornadoVM 7.3.1 has the Metal SIMD-group matrix operations** in `KernelContext`: `simdgroupMatrixZero`, `simdgroupMatrixLoad` and `simdgroupMatrixLoadTransposed` (from a local `float[]` or a `FloatArray`, with offset and row stride), `simdgroupMatrixMultiplyAccumulate(a, b, c)` = a · b + c, and `simdgroupMatrixStore`. The SDK example `uk.ac.manchester.tornado.examples.compute.MatrixMultiplySimdgroup` (in `tornado-examples-7.3.1.jar`) shows the pattern. The SIMD group of a work item is `localIdx / 32`.
2. **Helper methods and local arrays work.** The kernels call static helpers (`store`, `storeOne`) and use local arrays of fixed size (`allocateFloatLocalArray(TILE_K * (TILE_M + 1))` with compile-time constants). They also use `var` for `Matrix8x8Float`.
3. **The grid needs the key `graphName.taskName`** (see item 1 of [Findings and pitfalls](#findings-and-pitfalls)). `KernelsHexConvLayer.addTask` builds it with `graph.getTaskGraphName()`. Each layer has its own graph name (`hexconv1`, `hexconv2`, …).
4. **`@ParameterizedTest` does not work** (item 6 of [Findings and pitfalls](#findings-and-pitfalls)). The convolution test uses `@TestFactory` with `DynamicTest` instead, which works. Note that `@BeforeEach` runs once for the whole factory, not once per dynamic test.

## Forward pass on the device

**TL;DR:** `TornadoNetwork` implements the nn `ForwardPass` with one TornadoVM task graph that holds every layer: `conv_in`, the two convolutions of each block, the policy head and the fused value head. Parameters, gather table and activations stay on the device; per call, the encoded input goes in and one buffer with logits and values comes back. One plan per power-of-two batch size ("bucket"), built on first use. It agrees with `Network` within 0.13 of the tolerance bound, and `new Evaluator(new TornadoNetwork(p))` gives the same moves, priors and values as `new Evaluator(new Network(p))` within 0.002 of the bound.

### How it is built

| Part | What `TornadoNetwork` uses |
|---|---|
| Parameters | `OpsParameters`, declared with `FIRST_EXECUTION` on every plan. `reload()` copies the `Parameters` again, and each plan re-sends them before its next execution (`OpsParameters.Residency`). A plan built after a `reload()` gets the new values with its first execution. |
| `conv_in`, block convolutions | `KernelsHexConvLayer.addTask` with the arrays of `OpsParameters`. conv2 fuses the residual add and ReLU, so `OpsKernels.residualAddRelu` is not used. The block input `x`, the conv1 output `t` and the block output `u` are three device buffers; after each block `x` and `u` swap. A convolution without residual gets a 1-element dummy array as `res`, never the input itself. |
| Heads | `OpsHeads.addTasks` and `OpsHeads.schedule` on the same graph, so the trunk never leaves the device. The host copies the first n · 242 logits and the n · P values at `OpsKernels.valuesOffset(bucket)` (not of n) into the caller's arrays. |
| Transfers | Input `EVERY_EXECUTION` to the device, the head buffer `EVERY_EXECUTION` to the host, everything else `FIRST_EXECUTION`. The test checks it with the profiler: a warm call at bucket 4 moves the input bytes in and the head buffer out, and nothing else. |
| Kernel choice | `variantFor(bucket, C)`: `NAIVE` while bucket · C ≤ 128 (SMALL up to batch 4, LARGE up to batch 2), else `SIMDGROUP`. Measured, see [Kernel choice](#kernel-choice). |
| Profiler | Off. `TornadoNetwork.profiled(…)` makes an instance with the silent profiler, for tests and measurements; `lastProfile()` works only there. |

**Batch sizes (project decision).** TornadoVM bakes scalar arguments, the batch size among them, into the compiled task. The choices were:

| Option | Cost |
|---|---|
| One plan per exact batch size | Every new leaf-batch size compiles again (tens of milliseconds per kernel, 15 kernels for LARGE). MCTS leaf batches vary in size. |
| One plan for the largest batch, with an "active count" | The count would have to live in a device array that every kernel reads, which no kernel does, and every call would launch the grid of the largest batch: up to 1,024 times the work at batch 1. |
| **One plan per power of two (chosen)** | At most log2(maxBatch) + 1 plans, and less than twice the work. A call with n positions uses the bucket min(next power of two ≥ n, maxBatch). The other slots of the bucket keep an earlier input (or zeros) and their outputs are not read; positions are independent, so they cannot change the real ones. A batch above `maxBatch` (default 1,024) runs in chunks. |

A caller who wants no wasted work uses power-of-two batches. Device memory per bucket is about 3 activations of bucket · 122 · C floats: 96 MB for LARGE at 1,024.

**Threads.** Not thread-safe, like `VectorNetwork`: an atomic busy flag makes a concurrent `forward`, `reload()` or `close()` throw `IllegalStateException`, and so does every call after `close()`. `close()` frees all plans. `Evaluator` over a `TornadoNetwork` is exactly as thread-safe as the network.

### Tests and what they catch

`TornadoNetworkTest`, 12 tests, all against the scalar `Network` with the tolerance of [network](network.md#numeric-conventions). Weights are `Parameters.init` and `Parameters.uniform` at the init bound √(6 / 7C), which also draws the biases. Inputs are encoded positions of seeded random games and random planes.

- **Every preset:** TINY, SMALL, LARGE, two and three players, both weights, batches 1, 3, 64 and 67 on one instance (buckets 1, 4, 64, 128). 3 and 67 leave slots of their bucket unused; LARGE has 6 blocks, so a wrong buffer rotation fails. The test checks that the reference logits of the positions differ (so a lost batch offset cannot pass), and that per config at least a quarter of the values are below 0.9 in magnitude for one of the two weights. With `Parameters.init`, LARGE (P = 2) saturates every value (0 of 134 below 0.9), because the zero-bias trunk grows through six residual blocks; with the uniform weights none is saturated.
- **Every kernel variant** in the whole pass (`NAIVE`, `TILED`, `SIMDGROUP`), SMALL, three players, batches 3 and 64. All three gave the same largest error, as the kernels are bit-identical on this device.
- **Chunks:** maxBatch 4 with batch 11 (4 + 4 + 3), maxBatch 6 with batch 13 (6 + 6 + 1, a bucket that is no power of two).
- **Unused slots do not leak:** batch 4 with all planes 1,000, then batch 3 in the same bucket; the outputs are longer than the batch and pre-filled with NaN, and only the batch slices may change.
- **Transfers:** a warm call compiles nothing, runs kernels on the device, sends only the bucket's input and receives only the head buffer.
- **`reload()`:** no effect before it; after it, a bucket used before and a bucket first used afterwards both agree with the reference of the new weights; changing any single tensor changes the output.
- **Saturated values:** SMALL, three players, dense2 biases 44.3, 100, −1000 and 1000, 45, +∞; every value of the reference is ±1, and the device must agree at batches 1 and 8 and through `Evaluator` (values equal bit for bit).
- **NaN weight:** a NaN in `conv_in.weight` makes every logit and value of the reference NaN; so must the device, with every kernel variant at batches 1 and 64, and `Evaluator` must throw `IllegalStateException` for both networks.
- **Misuse:** a dirty padding row (position 0 alone, position 0 of two, and the last position of a batch of 6 above maxBatch 4), short arrays, batch 0, null, maxBatch 0 or too large; a call while busy (a package-private hook marks the instance busy); calls after `close()`.
- **Evaluator end to end:** SMALL and LARGE, two and three players, 90 positions of random playouts with every mover, as one batch (bucket 128) and every tenth position alone. The moves must be equal, the priors and values within the tolerance; the test also checks that the priors are not near-uniform (largest ratio of two priors in a position above 2).

Mutation check (2026-11-07): each of these bugs, put into `TornadoNetwork` one at a time, made at least one test fail: values read at `valuesOffset(n)` instead of the bucket's; conv2 without the residual; the buffers not rotated after a block; `reload()` not copying; chunks reading the input from offset 0; the bucket rounded down instead of up; chunks writing the logits to offset 0; the padding check skipping position 0.

Largest error as a fraction of the bound, over all comparisons:

| Comparison | Largest error / bound | Largest absolute error |
|---|---|---|
| Forward pass, TINY | 0.0052 | 8.3·10⁻⁷ |
| Forward pass, SMALL | 0.062 (P = 3, `Parameters.init`) | 1.2·10⁻⁵ |
| Forward pass, LARGE | 0.128 (P = 3, `Parameters.init`) | 2.6·10⁻⁵ |
| Every kernel variant, SMALL P = 3 | 0.0061 | 8.9·10⁻⁷ |
| `Evaluator` priors (SMALL / LARGE) | 0.0004 to 0.0017 | up to 2.8·10⁻⁷ |
| `Evaluator` values (SMALL / LARGE) | 0.0004 to 0.0019 | up to 2.2·10⁻⁷ |

For comparison, `VectorNetwork` reached 0.042 for LARGE ([network](network.md#measured-cpu-speed)). The device errors are larger with `Parameters.init` than with the uniform weights, because the activations grow larger without biases.

## Measured speed

**TL;DR:** in one process, the GPU computes about 120,000 SMALL or 21,000 LARGE evaluations per second from batch 256 on, 38 to 39 times one CPU thread with the Vector API. It overtakes one CPU thread at batch 16 (SMALL) and already at batch 1 (LARGE). For MCTS this means: leaf batches of 64 give most of the gain (73,000 SMALL and 19,000 LARGE per second), batches above 256 give almost nothing more, and single-position calls should stay on the CPU for SMALL. TINY is too small for the GPU to matter.

Run `devenv shell -- ./gradlew --no-build-cache :nn-tornado:benchmark` (about 7 minutes; `--args=speed` or `--args=kernels` runs one table).

**Method.** `TornadoNetworkBenchmark` (test sources) measures the whole forward pass, single process, 2 players, `Parameters.init` weights, inputs from 1,024 encoded positions of seeded random games. Each case warms up for at least 2 s and 10 calls (the GPU compiles the bucket's kernels there), then runs 3 rounds of at least 1 s; the table shows the median round. GPU time is the wall time of `forward`, so it includes the copy into the off-heap buffer, both host-device transfers, all kernels and the copy into the caller's arrays. The CPU paths run on one thread and compute one position after the other, so their rate does not depend on the batch (also measured in [network](network.md#measured-cpu-speed)). A CPU case whose single call would take more than 3 s at the rate of the smaller batches is not run; the table shows the rate of the smaller batches there.

**Machine and load.** Apple M3 Max (16 cores, Metal), JDK 25.0.3, 2026-11-07. Other agents ran arena games on the CPU at the same time: the 1-minute load average was 8.2 before and 7.3 after the run. An earlier run at load 17.8 gave CPU rates about 20 % lower (SMALL vector 2,380 instead of 2,950) and GPU rates up to 30 % lower at large batches. Treat the absolute numbers as lower bounds.

| Preset | Batch | Scalar CPU eval/s | Vector API CPU eval/s | GPU eval/s (range of 3 rounds) | GPU ms per call | GPU / Vector |
|---|---|---|---|---|---|---|
| TINY | 1 | 6,808 | 86,487 | 3,339 (3,329 to 3,387) | 0.30 | 0.04 |
| TINY | 16 | 6,790 | 106,206 | 50,555 (50,203 to 50,576) | 0.32 | 0.48 |
| TINY | 64 | 6,824 | 88,434 | 121,697 (121,275 to 122,452) | 0.53 | 1.38 |
| TINY | 256 | 6,822 | 87,875 | 340,119 (314,869 to 350,262) | 0.75 | 3.87 |
| TINY | 1024 | 6,808 | 88,285 | 702,133 (700,096 to 705,418) | 1.46 | 7.95 |
| SMALL | 1 | 201 | 2,949 | 1,968 (1,948 to 1,968) | 0.51 | 0.67 |
| SMALL | 16 | 202 | 2,898 | 25,980 (25,113 to 26,174) | 0.62 | 8.96 |
| SMALL | 64 | 199 | 2,973 | 72,671 (72,171 to 73,525) | 0.88 | 24.45 |
| SMALL | 256 | 203 | 3,019 | 111,409 (111,175 to 113,594) | 2.30 | 36.91 |
| SMALL | 1024 | not run (about 203) | 3,006 | 118,539 (118,538 to 120,172) | 8.64 | 39.44 |
| LARGE | 1 | 33 | 544 | 1,005 (1,003 to 1,006) | 1.00 | 1.85 |
| LARGE | 16 | 33 | 560 | 12,637 (12,631 to 12,711) | 1.27 | 22.58 |
| LARGE | 64 | 33 | 558 | 18,930 (18,922 to 19,000) | 3.38 | 33.93 |
| LARGE | 256 | not run (about 33) | 555 | 21,073 (20,953 to 21,097) | 12.15 | 37.95 |
| LARGE | 1024 | not run (about 33) | 556 | 21,362 (20,727 to 21,417) | 47.94 | 38.41 |

**Where the GPU overtakes the CPU** (of the batches measured, against one Vector API thread): TINY from batch 64, SMALL from batch 16 (at batch 1 the CPU is 1.5 times faster), LARGE from batch 1. In matrix work, the GPU reaches about 1.7 TFLOP/s for SMALL and 1.8 TFLOP/s for LARGE at batch 1,024, and one CPU thread 42 to 47 GFLOP/s.

### What this means for MCTS leaf batching

- **Latency.** A call at batch 1 takes 0.5 ms (SMALL) or 1.0 ms (LARGE). A search that evaluates one leaf at a time gets about 2,000 SMALL or 1,000 LARGE evaluations per second from the GPU, no more than one or two CPU threads.
- **Throughput saturates early.** SMALL reaches 61 % of its best rate at batch 64 and 94 % at batch 256; LARGE reaches 89 % at batch 64 and 99 % at batch 256. Batches above 256 add almost nothing, and a LARGE call at batch 1,024 takes 48 ms, which delays the search.
- **Against the whole CPU, not one thread.** The search runs on the same 16 cores. If `VectorNetwork` scaled to about 12 threads (an assumption, not measured; the other agents' load would make it fewer), the CPU would give about 35,000 SMALL or 6,700 LARGE evaluations per second and would have no time left for the tree. With that estimate, the GPU pays off from batch 16 for LARGE (12,600 against 6,700) and from batch 64 for SMALL (72,700 against 35,000; batch 16 gives only 26,000), and it leaves the cores to the search.
- **So the search must collect leaves:** about 16 (LARGE) or 64 (SMALL) per call to win clearly, 64 to 256 for nearly all of the gain. One tree with virtual loss over 64 paths changes the search a lot; batching leaves of several games or several trees (self-play with many games in parallel, one evaluating thread) gets these sizes without that. Each call blocks its thread; whether two instances in two threads can overlap host work and GPU work was not tested.
- **Bucket waste.** A batch of 65 runs as 128, with up to twice the work. Batch 128 was not measured; the wall time per call grows from 0.88 ms (batch 64) to 2.30 ms (256) for SMALL and from 3.4 ms to 12.2 ms for LARGE. Collect power-of-two batches where possible.

### Kernel choice

The whole pass with every convolution on one kernel (GPU eval/s, median of 3 rounds, same run):

| Preset | Batch | `NAIVE` | `TILED` | `SIMDGROUP` |
|---|---|---|---|---|
| SMALL | 1 | **1,996** | 1,267 | 1,748 |
| SMALL | 2 | **3,886** | 2,607 | 3,457 |
| SMALL | 4 | **7,619** | 5,399 | 6,931 |
| SMALL | 8 | **13,893** | 10,719 | 13,747 |
| SMALL | 16 | 22,931 | 20,998 | **25,260** |
| SMALL | 32 | 31,653 | 38,869 | **46,962** |
| SMALL | 64 | 35,449 | 64,488 | **70,471** |
| LARGE | 1 | **992** | 599 | 887 |
| LARGE | 2 | **2,032** | 1,200 | 1,813 |
| LARGE | 4 | 2,242 | 2,458 | **3,791** |
| LARGE | 8 | 2,408 | 5,055 | **7,146** |
| LARGE | 16 | 2,444 | 7,849 | **10,882** |
| LARGE | 32 | 2,493 | 11,528 | **13,974** |
| LARGE | 64 | 2,570 | 12,203 | **15,902** |

`NAIVE` wins up to batch 8 for SMALL and up to batch 2 for LARGE; it stops scaling for C = 64 from batch 4 on, as the kernel measurements showed ([Measured kernel speed](#measured-kernel-speed)). `SIMDGROUP` wins everywhere above, and `TILED` never wins. The rule bucket · C ≤ 128 → `NAIVE` matches every measured case except SMALL batch 8, where `NAIVE` was ahead by 1 % in this run and by 7 % in an earlier one.

### Findings

1. **The silent profiler costs milliseconds per call.** Same instance setup, profiler on and off (LARGE): batch 1 4.5 to 8.4 ms against 1.0 to 1.6 ms, batch 64 6.6 to 10.6 ms against 3.4 to 3.9 ms. It also distorts the kernel times: at SMALL batch 64 the profiled kernel times add up to 1.95 ms, more than the 0.88 ms wall time of an unprofiled call. Every earlier "warm call" number in this document was measured with the profiler. `TornadoNetwork` runs without it.
2. **The kernels do not explain all of the wall time at large batches.** At LARGE batch 1,024 the profiled per-task kernel times add up to about 29 ms (12 convolutions at 2.3 to 2.4 ms, `conv_in` 0.38 ms, the heads 0.1 ms each), while an unprofiled call takes 48 to 53 ms. The difference grows with the batch, about 0.02 ms per position; the transfers are 4.5 MB and take 0.27 ms. The cause is not known (see [Open points](#open-points)).
3. **The first call of a bucket compiles all its kernels** (15 for LARGE). For one LARGE bucket, not the first plan of the JVM, the profiler reported 127 ms of Graal compilation, 25 ms of code generation and 6 ms of driver compilation. The first plan of a JVM also starts the runtime (about 0.3 s, [Measured cost](#measured-cost)). A search should warm up the buckets it will use.

## Open points

- Compare Metal with OpenCL on this Mac (needs the second archive).
- Find the limit of the tiled convolution kernels at about 3 TFLOP/s (gather, occupancy?).
- Find the cause of the extra wall time at large batches of [Measured speed](#measured-speed), finding 2 (about 0.02 ms per position beyond the kernel times). Candidates: synchronisation between the 15 kernels of a call, or kernel times that the profiler measures wrongly (finding 1).
- Measure `VectorNetwork` with one instance per thread on many threads, to compare the GPU with the whole CPU instead of the estimate in [What this means for MCTS leaf batching](#what-this-means-for-mcts-leaf-batching).
- Find out whether two `TornadoNetwork` instances in two threads overlap their host work and GPU work.
- Find out whether the Metal code cache (`tornado.metal.codecache.enable`, default `False`) shortens the first call.
