plugins {
    id("sternhalma.java-conventions")
}

dependencies {
    api(project(":engine"))

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// Builds a race table (sternhalma.tablebase.RaceTable). The default region rows>=2 goes to tablebase/tables/rows2.bin
// (committed, tracked with Git LFS, see .gitattributes); rows>=1 (254M states, about 242 MB) is never committed and
// goes to the cache tablebase/build/tables/ (or -Psternhalma.tablebase.dir=<dir>), where RaceTable finds it:
//   ./gradlew --no-build-cache :tablebase:buildTable                        # rows>=2, about 12 s
//   ./gradlew --no-build-cache :tablebase:buildTable -Prows=1 -Pthreads=4   # rows>=1, a few minutes
// A rows>=2 rebuild is only needed after a change of the table format or of the solitaire rules; RaceTable rebuilds a
// missing or pointer-only table into the cache on first use.
val rows = providers.gradleProperty("rows").map { it.toInt() }.orElse(2)
val tableThreads = providers.gradleProperty("threads")
val tableDir = providers.gradleProperty("sternhalma.tablebase.dir")

tasks.register<JavaExec>("buildTable") {
    group = "build"
    description = "Writes a race table: rows>=2 into tablebase/tables, rows>=1 (-Prows=1) into the cache."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "sternhalma.tablebase.RaceTable"
    maxHeapSize = "2g"
    val r = rows.get()
    val dir = tableDir.orNull
        ?: if (r >= 2) projectDir.resolve("tables").toString()
        else layout.buildDirectory.dir("tables").get().asFile.toString()
    args = listOf(dir, "--rows", r.toString()) + (tableThreads.orNull?.let { listOf("--threads", it) } ?: emptyList())
}

// Checks a table against the engine's move generator (sternhalma.tablebase.TableValidation):
//   ./gradlew --no-build-cache :tablebase:validateTable --args='--rows 1 --samples 0 --compare --bfs 2000 --threads 8'
tasks.register<JavaExec>("validateTable") {
    group = "verification"
    description = "Validates a race table: layer consistency (--samples 0 = every entry), --compare, --bfs N."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "sternhalma.tablebase.TableValidation"
    maxHeapSize = "3g"
    workingDir = rootDir
    tableDir.orNull?.let { systemProperty("sternhalma.tablebase.dir", it) }
}
