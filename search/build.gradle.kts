plugins {
    id("sternhalma.java-conventions")
    `java-test-fixtures`
}

dependencies {
    api(project(":engine"))

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
