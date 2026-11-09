plugins {
    id("sternhalma.java-conventions")
}

dependencies {
    implementation(project(":search"))
    // RaceTable orders the S1 plan search; Orientation gives the canonical cells of the sample format.
    implementation(project(":tablebase"))
    implementation(project(":nn"))

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    // The strategy contract (abstract jqwik tests) runs against the raw-policy player.
    testImplementation(testFixtures(project(":search")))
    testImplementation(libs.jqwik)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<Test>().configureEach {
    // The subprocess tests (signals, lock) start the tool in a child JVM with the test's class path.
    workingDir = rootDir
    maxHeapSize = "2g"
}

// The self-play data tool (docs/training.md, "Self-play data tool"). Runs in the repository root.
tasks.register<JavaExec>("selfplay") {
    group = "application"
    description = "Self-play games with adjudication into sample files; see sternhalma.selfplay.SelfPlay."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "sternhalma.selfplay.SelfPlay"
    workingDir = rootDir
}

// Samples per second of ReplayBuffer.batch (a main method in the test sources, not a test).
// Run: ./gradlew --no-build-cache :selfplay:replayBenchmark. See ReplayBufferBenchmark and docs/training.md, "As implemented (block 2c)".
tasks.register<JavaExec>("replayBenchmark") {
    group = "verification"
    description = "Measures training samples per second of ReplayBuffer.batch, single thread."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("sternhalma.selfplay.ReplayBufferBenchmark")
}

// Paired games of a network player (raw policy or MCTS) against ladder rungs, resumable per pair (docs/training.md,
// "As implemented (block 2g)"). The Vector API forward pass needs the incubating module.
//   ./gradlew --no-build-cache :selfplay:mctsMatch --args='--out DIR --net FILE --subject mcts:sims=100 --vs ab:100'
tasks.register<JavaExec>("mctsMatch") {
    group = "verification"
    description = "Paired games of an MCTS or raw-policy player against ladder rungs; see sternhalma.selfplay.MctsMatch."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "sternhalma.selfplay.MctsMatch"
    workingDir = rootDir
    jvmArgs("--add-modules", "jdk.incubator.vector", "-Xmx3g")
}

// Simulations per second of an MCTS player (docs/training.md, "As implemented (block 2g)").
tasks.register<JavaExec>("mctsBench") {
    group = "verification"
    description = "Simulations per second of an MCTS player on mid-game positions; see sternhalma.selfplay.MctsBench."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "sternhalma.selfplay.MctsBench"
    workingDir = rootDir
    jvmArgs("--add-modules", "jdk.incubator.vector", "-Xmx3g")
}
