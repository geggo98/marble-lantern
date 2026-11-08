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
