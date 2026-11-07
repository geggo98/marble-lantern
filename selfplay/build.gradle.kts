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
