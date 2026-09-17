// excalibur-filter (Phase 3) - Probabilistic Structures
plugins {
    `java-library`
}



repositories {
    mavenCentral()
}

dependencies {
    // SLF4J API for logging
    implementation("org.slf4j:slf4j-api:2.0.16")

    // Test dependencies
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
    testImplementation("org.assertj:assertj-core:3.26.3")

    // Phase 1 dependency. api, not implementation: IntegrityReport is the declared return
    // type of HnswGraph.verifyIntegrity and its siblings, so a module that calls one cannot
    // name what it gets back without core on its own compile classpath.
    api(project(":excalibur-core"))
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.release.set(21)
    options.compilerArgs.add("--enable-preview")
}

tasks.test {
    useJUnitPlatform()
    jvmArgs("--enable-preview")
}
