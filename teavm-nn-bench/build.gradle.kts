import org.teavm.gradle.api.JSModuleType

// Bench-only module of the network benchmark in the browser (scripts/bench-net-wasm.zsh): the scalar Java Network
// (nn/Network.java) compiled by TeaVM to WebAssembly GC and to JavaScript, plus the JVM driver. It does not touch the
// shipped Web facade of teavm-web. Same plugin setup as teavm-web (no java-conventions: -Werror fails on the TeaVM
// annotation processor warning).
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
    implementation(project(":nn"))
    teavm(teavm.libs.jsoApis)
}

teavm {
    all {
        mainClass = "sternhalma.nnbench.NetBenchWeb"
    }
    js {
        moduleType = JSModuleType.ES2015
        targetFileName = "nn-bench-java.js"
    }
    wasmGC {
        targetFileName = "nn-bench-java.wasm"
        copyRuntime = true
    }
}

// The JVM rows: scalar Network and Vector API VectorNetwork, JSON lines on stdout.
//   ./gradlew --no-build-cache :teavm-nn-bench:jvmBench --args='<net.safetensors> <reference.safetensors> [1,8,32,128]'
tasks.register<JavaExec>("jvmBench") {
    group = "verification"
    description = "Measures positions/s of the scalar and the Vector API network on the JVM with the schedule of the browser driver."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "sternhalma.nnbench.JvmNetBench"
    jvmArgs("--add-modules", "jdk.incubator.vector")
    workingDir = rootDir
}
