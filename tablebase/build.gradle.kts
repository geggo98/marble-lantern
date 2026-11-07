plugins {
    id("sternhalma.java-conventions")
}

dependencies {
    api(project(":engine"))

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// Builds the rows>=2 race table into tablebase/tables/rows2.bin (tracked with Git LFS, see .gitattributes):
//   ./gradlew --no-build-cache :tablebase:buildTable
// Only needed after a change of the table format or of the solitaire rules; RaceTable rebuilds a missing or
// pointer-only table into tablebase/build/tables/ on first use.
tasks.register<JavaExec>("buildTable") {
    group = "build"
    description = "Writes the rows>=2 race table to tablebase/tables/rows2.bin."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "sternhalma.tablebase.RaceTable"
    args = listOf(projectDir.resolve("tables").toString())
}
