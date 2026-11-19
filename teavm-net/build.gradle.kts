import org.teavm.gradle.api.JSModuleType

// The net-MCTS engine of the browser without its forward pass: the stepwise Mcts of module search, the encoder, the
// softmax over the legal moves and the value mapping, compiled by TeaVM to WebAssembly GC and to JavaScript. The
// forward pass runs outside (C++ WebAssembly or WebGPU). Same plugin setup as teavm-web (no java-conventions: -Werror
// fails on the TeaVM annotation processor warning).
plugins {
    java
    id("org.teavm") version "0.16.0"
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 25
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all", "--add-modules", "jdk.incubator.vector"))
}

dependencies {
    implementation(project(":engine"))
    implementation(project(":nn"))
    implementation(project(":tablebase"))
    implementation(project(":search"))
    teavm(teavm.libs.jsoApis)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    maxHeapSize = "1g"
    jvmArgs("--add-modules", "jdk.incubator.vector")
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

// The golden games of the node harness (teavm-net/test/harness.mjs): the JVM plays them with a hash stand-in for the
// network and writes one file per variant. Run: ./gradlew --no-build-cache :teavm-net:goldenExport
tasks.register<JavaExec>("goldenExport") {
    group = "verification"
    description = "Writes teavm-net/test/golden/<variant>.txt, the games the TeaVM builds must reproduce."
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "sternhalma.webnet.GoldenGame"
    workingDir = rootDir
    jvmArgs("--add-modules", "jdk.incubator.vector")
}

teavm {
    all {
        mainClass = "sternhalma.webnet.WebNet"
    }
    js {
        moduleType = JSModuleType.ES2015
        targetFileName = "engine-net.js"
    }
    wasmGC {
        targetFileName = "engine-net.wasm"
        copyRuntime = true
    }
}
