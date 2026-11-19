plugins {
    id("sternhalma.java-conventions")
}

dependencies {
    api(project(":engine"))

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// The CPU reference network uses the incubating Vector API.
tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.addAll(listOf("--add-modules", "jdk.incubator.vector"))
}

tasks.withType<Test>().configureEach {
    jvmArgs("--add-modules", "jdk.incubator.vector")
}

// Benchmark of the scalar and the Vector API forward pass (a main method in the test sources, not a test).
// Run: ./gradlew --no-build-cache :nn:benchmark. See NetworkBenchmark and docs/network.md, "Measured CPU speed".
tasks.register<JavaExec>("benchmark") {
    group = "verification"
    description = "Measures evaluations per second of the scalar and the vector network, single thread."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("sternhalma.nn.NetworkBenchmark")
    jvmArgs("--add-modules", "jdk.incubator.vector")
}

// Cost of checkpoints and their share of a 500-step SMALL run (a main method in the test sources, not a test).
// Run: ./gradlew --no-build-cache :nn:checkpointBenchmark. See CheckpointBenchmark and docs/training.md, "As implemented (block 2b)".
tasks.register<JavaExec>("checkpointBenchmark") {
    group = "verification"
    description = "Measures the cost of checkpoints for TINY and SMALL and their share of a 500-step SMALL run."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("sternhalma.nn.CheckpointBenchmark")
    jvmArgs("--add-modules", "jdk.incubator.vector")
}

// Benchmark of the CPU reference trainer (a main method in the test sources, not a test).
// Run: ./gradlew --no-build-cache :nn:trainerBenchmark. See TrainerBenchmark and docs/training.md, "As implemented".
tasks.register<JavaExec>("trainerBenchmark") {
    group = "verification"
    description = "Measures training samples per second of the CPU reference trainer, single thread."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("sternhalma.nn.TrainerBenchmark")
    jvmArgs("--add-modules", "jdk.incubator.vector")
}

// GFLOP/s of the Vector API forward pass for SMALL and LARGE at batch 1 and 256, one thread (a main method in the test
// sources, not a test). Properties: -PvectorBits=64|128|256|512 forces the species (default: the preferred one),
// -PvectorSeconds=N sets the length of a round, -PvectorJvmArgs="-XX:UseAVX=2 -XX:MaxVectorSize=32" adds JVM flags (x86).
// Run: ./gradlew --no-build-cache :nn:vectorBenchmark. See docs/architecture.md, "Portability".
tasks.register<JavaExec>("vectorBenchmark") {
    group = "verification"
    description = "Measures GFLOP/s of the Vector API forward pass (SMALL and LARGE, batch 1 and 256)."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("sternhalma.nn.VectorBenchmark")
    jvmArgs("--add-modules", "jdk.incubator.vector")
    providers.gradleProperty("vectorBits").orNull?.let { systemProperty("sternhalma.vector.bits", it) }
    providers.gradleProperty("vectorJvmArgs").orNull?.let { jvmArgs(it.split(" ").filter(String::isNotBlank)) }
    providers.gradleProperty("vectorSeconds").orNull?.let { args("--seconds", it) }
}

// Input and reference file of the network benchmark in the browser (scripts/bench-net-wasm.zsh), not a test.
// Run: ./gradlew --no-build-cache :nn:wasmBenchExport --args='<net.safetensors> <out.safetensors> [positions] [seed]'
tasks.register<JavaExec>("wasmBenchExport") {
    group = "verification"
    description = "Writes the golden inputs and the scalar reference outputs of a net for the browser benchmark."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("sternhalma.nn.WasmBenchExport")
    workingDir = rootDir
    jvmArgs("--add-modules", "jdk.incubator.vector")
}

// How much storing the weights as float16 changes the outputs of a net (not a test).
// Run: ./gradlew --no-build-cache :nn:halfPrecisionStudy --args='<net.safetensors> [positions] [seed]'
tasks.register<JavaExec>("halfPrecisionStudy") {
    group = "verification"
    description = "Compares a net with its F32-F16-F32 round trip: logits, values, arg-max move agreement, policy KL."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("sternhalma.nn.HalfPrecisionStudy")
    workingDir = rootDir
    jvmArgs("--add-modules", "jdk.incubator.vector")
}

// Golden files of the C++ network port (docs/cpp-comparison-plan.md, WP8): weights, 1,024 encoded inputs, reference outputs of
// the scalar and the Vector API network, hand-checkable cases and MANIFEST.tsv in cpp/golden. With -Psternhalma.golden.real=true
// it also writes the reference for the real LARGE weights ($STERNHALMA_DATA_DIR/loop4/gen-0011). -Psternhalma.golden.out=DIR
// changes the output directory (default cpp/golden).
// Run: ./gradlew --no-build-cache :nn:goldenExport
tasks.register<JavaExec>("goldenExport") {
    group = "verification"
    description = "Writes the golden network files for the C++ port into cpp/golden (git-ignored)."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("sternhalma.nn.GoldenNnExport")
    workingDir = rootDir
    jvmArgs("--add-modules", "jdk.incubator.vector")
    val out = providers.gradleProperty("sternhalma.golden.out").orElse("cpp/golden")
    val real = providers.gradleProperty("sternhalma.golden.real").map { it == "true" }.orElse(false)
    argumentProviders.add(CommandLineArgumentProvider {
        listOf("--out", out.get()) + (if (real.get()) listOf("--real") else emptyList())
    })
}
