plugins {
    id("sternhalma.java-conventions")
}

dependencies {
    api(project(":nn"))

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}
