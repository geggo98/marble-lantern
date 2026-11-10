plugins {
    id("sternhalma.java-conventions")
}

dependencies {
    implementation(project(":search"))
    // RaceTable orders the S1 plan search; Orientation gives the canonical cells of the sample format.
    implementation(project(":tablebase"))
    implementation(project(":nn"))
    // GpuTrainers: no TornadoVM type in its signatures, so this module compiles without the TornadoVM API.
    implementation(project(":nn-tornado"))

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    // The strategy contract (abstract jqwik tests) runs against the raw-policy player.
    testImplementation(testFixtures(project(":search")))
    testImplementation(libs.jqwik)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// The GPU trainer tests (docs/training.md, "As implemented (block B6-B10)") need the TornadoVM runtime flags of the SDK
// (see nn-tornado/build.gradle.kts, which this block follows): they run in their own task, "gpuTest", and are
// excluded from "test", whose JVM runs without the flags. Without the SDK, gpuTest is skipped.
val tornadoHome: String? = providers.environmentVariable("TORNADOVM_HOME").orNull
val sdkArgfileTemplate: File? = tornadoHome?.let { file("$it/tornado-argfile.template") }?.takeIf { it.isFile }

abstract class TornadoJvmArgs : CommandLineArgumentProvider {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.ABSOLUTE)
    abstract val template: RegularFileProperty

    @get:Input
    abstract val home: Property<String>

    override fun asArguments(): Iterable<String> {
        val args = mutableListOf<String>()
        template.get().asFile.readLines()
            .map { it.trim().replace("\${TORNADOVM_HOME}", home.get()) }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .forEach { line ->
                val space = line.indexOf(' ')
                if (space < 0) {
                    args += line
                } else {
                    val flag = line.substring(0, space)
                    var value = line.substring(space + 1).trim()
                    if (flag == "--module-path") {
                        value = value.split(File.pathSeparator).filter { it != "." }.joinToString(File.pathSeparator)
                    }
                    args += flag
                    args += value
                }
            }
        return args
    }
}

tasks.test {
    filter { excludeTestsMatching("*Gpu*") }
}

tasks.register<Test>("gpuTest") {
    group = "verification"
    description = "The loop with the GPU trainer (classes named *Gpu*); needs the TornadoVM SDK and a device."
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    filter { includeTestsMatching("*Gpu*") }
    if (sdkArgfileTemplate != null && tornadoHome != null) {
        jvmArgumentProviders.add(objects.newInstance<TornadoJvmArgs>().apply {
            template.set(sdkArgfileTemplate)
            home.set(tornadoHome)
        })
        systemProperty("sternhalma.tornado.flags", "true")
        jvmArgs("--sun-misc-unsafe-memory-access=allow")
    }
    jvmArgs("--add-modules", "jdk.incubator.vector")
    // Gradle runs tests with -ea, which breaks the TornadoVM backend start (docs/tornado.md, pitfall 1).
    enableAssertions = false
    jvmArgs("-ea:sternhalma...")
    systemProperty("tornado.recover.bailout", "False")
    val sdkInstalled = sdkArgfileTemplate != null
    onlyIf("the TornadoVM SDK is installed") { sdkInstalled }
}

// Trains one generation of a finished loop run again, with the trainer of your choice, and prints the held-out proxies
// next to those of the loop's candidate (a main in the test sources; see GpuCheck). Arguments after --args.
tasks.register<JavaExec>("gpuCheck") {
    group = "verification"
    description = "Re-trains the candidate of one loop generation with the CPU or the GPU trainer; see sternhalma.selfplay.GpuCheck."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("sternhalma.selfplay.GpuCheck")
    workingDir = rootDir
    if (sdkArgfileTemplate != null && tornadoHome != null) {
        jvmArgumentProviders.add(objects.newInstance<TornadoJvmArgs>().apply {
            template.set(sdkArgfileTemplate)
            home.set(tornadoHome)
        })
        jvmArgs("--sun-misc-unsafe-memory-access=allow")
    }
    jvmArgs("--add-modules", "jdk.incubator.vector", "-Xmx6g")
    systemProperty("tornado.recover.bailout", "False")
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
