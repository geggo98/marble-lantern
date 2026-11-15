plugins {
    id("sternhalma.java-conventions")
    `java-test-fixtures`
}

dependencies {
    api(project(":engine"))
    implementation(project(":tablebase"))
    // MCTS (Mcts, MctsPlayer) takes a network as policy prior and value; nn depends on engine only, so there is no cycle.
    api(project(":nn"))

    // The strategy contract (abstract jqwik tests) is shared with the modules that add strategies.
    testFixturesApi(project(":engine"))
    testFixturesApi(project(":search"))
    testFixturesApi(platform(libs.junit.bom))
    testFixturesApi(libs.junit.jupiter)
    testFixturesApi(libs.jqwik)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.jqwik)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// MctsTrainedNetworkTest uses the Vector API forward pass of a trained network (nn.Networks.fastest), 15 times faster
// than the scalar one; without the module the test is skipped.
tasks.withType<Test>().configureEach {
    jvmArgs("--add-modules", "jdk.incubator.vector")
}

// Yardstick tools of round 2 (docs/search-and-evaluation.md, "Round 2 yardstick"). Both run in the repository
// root, so Bench finds the frozen build in .reference/ (override: -Psternhalma.reference.dir=<dir>).
//   ./gradlew --no-build-cache :search:bench --args='<specA> [<specB>] [--reps 3]'
//   ./gradlew --no-build-cache :search:fingerprint --args='[<spec> ...]'
val referenceDir = providers.gradleProperty("sternhalma.reference.dir")

tasks.register<JavaExec>("bench") {
    group = "verification"
    description = "Nodes per CPU-second of a spec on the reference positions, against the frozen pre-round build."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "sternhalma.search.Bench"
    workingDir = rootDir
    referenceDir.orNull?.let { systemProperty("sternhalma.reference.dir", it) }
}

tasks.register<JavaExec>("fingerprint") {
    group = "verification"
    description = "Prints the search-tree fingerprint of specs (default: R2 and BEST_SPEC), one line per position."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "sternhalma.search.Fingerprint"
    workingDir = rootDir
}

// Golden files of the C++ port, classical part (docs/cpp-comparison-plan.md, WP1): positions, move lists, evaluator
// values, fingerprint lines and race table results, all written by Java and read by the C++ tests. The argument is
// the output directory (default cpp/golden, git-ignored). The rows>=1 race table (254 MB) needs the larger heap.
//   ./gradlew --no-build-cache :search:goldenExport [--args='<output directory>']
tasks.register<JavaExec>("goldenExport") {
    group = "verification"
    description = "Writes the golden files of the C++ port (classical part) to cpp/golden."
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "sternhalma.search.GoldenExport"
    workingDir = rootDir
    maxHeapSize = "3g"
    jvmArgs("--add-modules", "jdk.incubator.vector")
}

// Writes the java command line of the Bench tool (JVM flags, classpath, main class) to cpp/build/java-search.cmd, so
// that the measurements start the JVM directly and not through Gradle. The line runs in the repository root:
//   $(cat cpp/build/java-search.cmd) <specA> <specB> --games 0
abstract class PrintRunCommand : DefaultTask() {
    @get:Input
    abstract val javaExecutable: Property<String>

    @get:Input
    abstract val jvmFlags: ListProperty<String>

    @get:Input
    abstract val mainClassName: Property<String>

    @get:Classpath
    abstract val runtimeClasspath: ConfigurableFileCollection

    @get:OutputFile
    abstract val commandFile: RegularFileProperty

    @TaskAction
    fun write() {
        val classpath = runtimeClasspath.files.joinToString(File.pathSeparator) { it.absolutePath }
        val words = listOf(javaExecutable.get()) + jvmFlags.get() + listOf("-cp", classpath, mainClassName.get())
        val file = commandFile.get().asFile
        file.parentFile.mkdirs()
        file.writeText(words.joinToString(" ") + "\n")
    }
}

tasks.register<PrintRunCommand>("printRunCommand") {
    group = "verification"
    description = "Writes the java command line of Bench to cpp/build/java-search.cmd."
    javaExecutable = providers.systemProperty("java.home").map { "$it/bin/java" }
    jvmFlags = listOf("-Xms4g", "-Xmx4g", "--add-modules", "jdk.incubator.vector")
    mainClassName = "sternhalma.search.Bench"
    runtimeClasspath.from(sourceSets.main.get().runtimeClasspath)
    commandFile = rootProject.layout.projectDirectory.file("cpp/build/java-search.cmd")
}
