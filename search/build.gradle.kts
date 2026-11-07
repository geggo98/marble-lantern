plugins {
    id("sternhalma.java-conventions")
    `java-test-fixtures`
}

dependencies {
    api(project(":engine"))
    implementation(project(":tablebase"))

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
