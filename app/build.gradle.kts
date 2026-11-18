import java.io.File

// The online mode: a Vaadin Hilla app that serves the shared React UI (web/src) and runs the server engines.
// Versions come from the Hilla scout (Hilla and Vaadin 25.3.5, Spring Boot 4.1.2 as the Vaadin platform manages it).
plugins {
    id("sternhalma.java-conventions")
    id("org.springframework.boot") version "4.1.2"
    id("com.vaadin") version "25.3.5"
}

dependencies {
    implementation(platform("com.vaadin:vaadin-bom:25.3.5"))
    implementation(platform("org.springframework.boot:spring-boot-dependencies:4.1.2"))
    implementation("com.vaadin:hilla-spring-boot-starter")
    // hilla-spring-boot-starter declares vaadin-spring-boot-starter with Maven scope "provided", which Gradle drops;
    // without these two lines SpringApplication does not compile (hilla-report.md, section 2).
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("com.vaadin:vaadin-spring-boot-starter")
    implementation(project(":engine"))
    implementation(project(":search"))
    implementation(project(":tablebase"))
    implementation(project(":nn"))
    // Only the classes: TornadoNetwork is created by reflection and only when the server starts with the TornadoVM flags.
    runtimeOnly(project(":nn-tornado"))

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<Test>().configureEach {
    // The network player uses the Vector API forward pass when the module is present.
    jvmArgs("--add-modules", "jdk.incubator.vector")
}

tasks.withType<JavaExec>().configureEach {
    jvmArgs("--add-modules", "jdk.incubator.vector")
}

// The browser engines are static files that web/ builds (web/public/engines, web/public/tables). Both are generated
// and not in Git; the online mode serves them from the boot jar so that it also offers the browser engines.
// Missing directories (a build without "devenv tasks run web:engines web:tables") give a jar without them.
val webPublic = rootProject.layout.projectDirectory.dir("web/public")
tasks.processResources {
    for (dir in listOf("engines", "tables")) {
        val source = webPublic.dir(dir).asFile
        if (source.isDirectory) {
            from(source) { into("META-INF/resources/$dir") }
        }
    }
}
