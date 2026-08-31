// excalibur-store (Phase 2) - Storage Engine
plugins {
    `java-library`
}



tasks.withType<JavaCompile> {
    options.compilerArgs.add("--enable-preview")
}

tasks.withType<Test> {
    jvmArgs("--enable-preview")
    useJUnitPlatform()
}

dependencies {
    // SLF4J API for logging
    implementation("org.slf4j:slf4j-api:2.0.16")

    // Phase 1 dependencies
    implementation(project(":excalibur-core"))

    // Phase 3 dependencies (for LSH integration)
    implementation(project(":excalibur-filter"))

    // Phase 4 dependencies (for HNSW graph integration)
    implementation(project(":excalibur-vector"))

    // Test dependencies
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
    testImplementation("org.assertj:assertj-core:3.26.3")
}
