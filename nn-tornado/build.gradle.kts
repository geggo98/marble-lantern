plugins {
    id("sternhalma.java-conventions")
}

// TornadoVM comes from the SDK that scripts/setup-tornado.sh installs into tornado-sdk/current;
// the devenv shell sets TORNADOVM_HOME. See docs/tornado.md.
//
// - Compile: against tornado-api from the SDK (the same build as the runtime). Without the SDK the
//   build compiles against the same version from Maven Central, so the build never fails for lack of it.
// - Test and probe: need the SDK at run time. Without it they are skipped with a message.
val tornadoVersion = "7.3.1"
val tornadoHome: String? = providers.environmentVariable("TORNADOVM_HOME").orNull
val sdkApiJar: File? = tornadoHome
    ?.let { file("$it/share/java/tornado/tornado-api-$tornadoVersion.jar") }
    ?.takeIf { it.isFile }
val sdkArgfileTemplate: File? = tornadoHome
    ?.let { file("$it/tornado-argfile.template") }
    ?.takeIf { it.isFile }
val sdkMissingReason: String? = when {
    tornadoHome == null -> "TORNADOVM_HOME is not set (run inside 'devenv shell')"
    sdkApiJar == null -> "no tornado-api-$tornadoVersion.jar under $tornadoHome (run 'devenv tasks run setup:tornado')"
    sdkArgfileTemplate == null -> "no tornado-argfile.template in $tornadoHome"
    else -> null
}

dependencies {
    api(project(":nn"))

    if (sdkApiJar != null) {
        compileOnly(files(sdkApiJar))
        testCompileOnly(files(sdkApiJar))
    } else {
        // Not transitive: the POM drags in Graal, log4j and OSGi jars that compiling against the API never needs.
        compileOnly(libs.tornado.api) { isTransitive = false }
        testCompileOnly(libs.tornado.api) { isTransitive = false }
    }

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

/**
 * The JVM flags a TornadoVM program needs, read from the SDK's tornado-argfile.template.
 * The template is a faithful copy of `tornado --printJavaFlags` for the installed backend and JDK.
 * Each "--flag value" line becomes two arguments; the working directory "." is dropped from the
 * module path, because the TornadoVM modules come from the SDK and the program runs on the class path.
 */
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

fun JavaForkOptions.tornadoJvm() {
    if (sdkArgfileTemplate != null && tornadoHome != null) {
        jvmArgumentProviders.add(objects.newInstance<TornadoJvmArgs>().apply {
            template.set(sdkArgfileTemplate)
            home.set(tornadoHome)
        })
        // Read by the tests: with this set, a missing TornadoVM runtime is a failure, not a skip.
        systemProperty("sternhalma.tornado.flags", "true")
        // TornadoVM 7.3.1 calls sun.misc.Unsafe::objectFieldOffset; JDK 25 warns about it at startup.
        jvmArgs("--sun-misc-unsafe-memory-access=allow")
    }
    // Classes of module nn use the Vector API.
    jvmArgs("--add-modules", "jdk.incubator.vector")
}

tasks.test {
    tornadoJvm()
    // Gradle runs tests with -ea. With assertions on, the Graal compiler inside TornadoVM runs its
    // self-checks, and InvocationPlugins$Checks fails the backend start with
    // NoSuchMethodError: jdk.internal.misc.Unsafe.getObject. Keep assertions for project code only.
    enableAssertions = false
    jvmArgs("-ea:sternhalma...")
    // Never run a kernel that failed to compile as Java on the host (the 7.3.1 default; stated, not assumed).
    systemProperty("tornado.recover.bailout", "False")
    val reason = sdkMissingReason
    onlyIf("the TornadoVM SDK is installed") {
        if (reason != null) {
            Logging.getLogger("nn-tornado").warn("nn-tornado: skipping TornadoVM tests: {}", reason)
        }
        reason == null
    }
}

// Prints device, compile cost and warm execution times of the feasibility kernels (a main in the
// test sources, not a test). Run: ./gradlew --no-build-cache :nn-tornado:probe
tasks.register<JavaExec>("probe") {
    group = "verification"
    description = "Runs the TornadoVM feasibility kernels and prints timings."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("sternhalma.tornado.TornadoProbe")
    tornadoJvm()
    systemProperty("tornado.recover.bailout", "False")
    val reason = sdkMissingReason
    onlyIf("the TornadoVM SDK is installed") {
        if (reason != null) {
            Logging.getLogger("nn-tornado").warn("nn-tornado: skipping probe: {}", reason)
        }
        reason == null
    }
}

// GFLOP/s of the hex convolution kernels (naive and tiled) with resident data, and of the CPU Vector API
// path for comparison (a main in the test sources). Run: ./gradlew --no-build-cache :nn-tornado:kernelBenchmark
tasks.register<JavaExec>("kernelBenchmark") {
    group = "verification"
    description = "Benchmarks the hex convolution kernels on the device against the CPU Vector API path."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("sternhalma.tornado.KernelsBenchmark")
    tornadoJvm()
    systemProperty("tornado.recover.bailout", "False")
    val reason = sdkMissingReason
    onlyIf("the TornadoVM SDK is installed") {
        if (reason != null) {
            Logging.getLogger("nn-tornado").warn("nn-tornado: skipping kernelBenchmark: {}", reason)
        }
        reason == null
    }
}

// Evaluations per second of the whole forward pass: scalar and Vector API CPU paths (one thread) and
// TornadoNetwork on the device, TINY/SMALL/LARGE at batch 1 to 1024 (a main in the test sources).
// Run: ./gradlew --no-build-cache :nn-tornado:benchmark. See docs/tornado.md, "Measured speed".
tasks.register<JavaExec>("benchmark") {
    group = "verification"
    description = "Measures evaluations per second of the CPU paths and of TornadoNetwork on the device."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("sternhalma.tornado.TornadoNetworkBenchmark")
    tornadoJvm()
    systemProperty("tornado.recover.bailout", "False")
    val reason = sdkMissingReason
    onlyIf("the TornadoVM SDK is installed") {
        if (reason != null) {
            Logging.getLogger("nn-tornado").warn("nn-tornado: skipping benchmark: {}", reason)
        }
        reason == null
    }
}

// Time of the training forward pass, the losses and the head backward on the device (SMALL classic1), wall time
// and per kernel (a main in the test sources). Arguments: batch sizes. See docs/training.md, "As implemented
// (block B1-B5)". Run: ./gradlew --no-build-cache :nn-tornado:trainerBenchmark
tasks.register<JavaExec>("trainerBenchmark") {
    group = "verification"
    description = "Measures the training kernels (forward, loss, head backward) on the device."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("sternhalma.tornado.TrainerBenchmark")
    tornadoJvm()
    systemProperty("tornado.recover.bailout", "False")
    val reason = sdkMissingReason
    onlyIf("the TornadoVM SDK is installed") {
        if (reason != null) {
            Logging.getLogger("nn-tornado").warn("nn-tornado: skipping trainerBenchmark: {}", reason)
        }
        reason == null
    }
}

// Evaluations per second that the device delivers to many games with real classic1 planes (a main in the test sources).
// Arguments: --size small|large --batches 64,128,256,512 --seconds 60 --threads 8 --cohorts 2. See docs/training.md, "As
// implemented (block G1)". Run: ./gradlew --no-build-cache :nn-tornado:gpuFeed --args="--size small"
tasks.register<JavaExec>("gpuFeed") {
    group = "verification"
    description = "Measures the evaluations per second of the pooled device evaluator under real plane computation."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("sternhalma.tornado.GpuFeedBenchmark")
    tornadoJvm()
    systemProperty("tornado.recover.bailout", "False")
    val reason = sdkMissingReason
    onlyIf("the TornadoVM SDK is installed") {
        if (reason != null) {
            Logging.getLogger("nn-tornado").warn("nn-tornado: skipping gpuFeed: {}", reason)
        }
        reason == null
    }
}
