import org.teavm.gradle.api.JSModuleType

// The classical browser engine (BROWSER_SPEC over rows2.bin) compiled by TeaVM to WebAssembly GC and to JavaScript.
// Not the shared java-conventions plugin: its -Werror fails on a warning of the TeaVM annotation processor
// ("Supported source version 'RELEASE_11' ... less than -source '25'"). Release level and -Xlint:all stay.
plugins {
    java
    id("org.teavm") version "0.16.0"
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 25
    options.encoding = "UTF-8"
    options.compilerArgs.add("-Xlint:all")
}

dependencies {
    implementation(project(":engine"))
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
    // The tests read tablebase/tables/rows2.bin; -Psternhalma.tablebase.dir overrides the directory.
    systemProperty(
        "sternhalma.web.rows2",
        providers.gradleProperty("sternhalma.tablebase.dir").map { "$it/rows2.bin" }
            .orElse(rootProject.layout.projectDirectory.file("tablebase/tables/rows2.bin").asFile.path).get(),
    )
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

// File names carry no project name (plan section 3.5). The optimization level is set per target: in all { } it has no effect.
teavm {
    all {
        mainClass = "sternhalma.web.Web"
    }
    js {
        moduleType = JSModuleType.ES2015
        targetFileName = "engine-java.js"
    }
    wasmGC {
        targetFileName = "engine-java.wasm"
        copyRuntime = true
    }
}

// The benchmark game on the JVM, the Java baseline of the demo and the source of the expected values of the smoke run:
//   ./gradlew --no-build-cache :teavm-web:benchReference --args='[--json] [<rows2.bin> [<plies> [<nodes>]]]'
tasks.register<JavaExec>("benchReference") {
    group = "verification"
    description = "Plays the benchmark game on the JVM and prints its fingerprint (checksum, nodes) and time."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "sternhalma.web.BenchReference"
    workingDir = rootDir
}
