plugins {
    `java-library`
}

// The devenv shell provides JDK 25; --release pins the language level without a toolchain download.
tasks.withType<JavaCompile>().configureEach {
    options.release = 25
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // The optional rows>=1 race table is a 254 MB array; Gradle's default test heap of 512 MB cannot hold it.
    maxHeapSize = "2g"
    // Tests look for race tables in -Psternhalma.tablebase.dir as well (RaceTable).
    providers.gradleProperty("sternhalma.tablebase.dir").orNull?.let { systemProperty("sternhalma.tablebase.dir", it) }
    testLogging {
        events("failed", "skipped")
        showStandardStreams = false
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
