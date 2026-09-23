// excalibur-integration (Phase 10) - Integration Tests
plugins {
    `java`
}


dependencies {
    // SLF4J API for logging
    implementation("org.slf4j:slf4j-api:2.0.16")

    // Test dependencies
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
    testImplementation("org.assertj:assertj-core:3.26.3")

    // All phases dependencies
    implementation(project(":excalibur-core"))
    implementation(project(":excalibur-config"))
    implementation(project(":excalibur-store"))
    implementation(project(":excalibur-filter"))
    implementation(project(":excalibur-vector"))
    implementation(project(":excalibur-shard"))
    implementation(project(":excalibur-query"))
    implementation(project(":excalibur-llm"))
    implementation(project(":excalibur-ingest"))
    implementation(project(":excalibur-api"))

    // Test utilities (FailureCategorizer, FailureReportingExtension)
    testImplementation(project(":excalibur-test"))
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.release.set(21)
    options.compilerArgs.add("--enable-preview")
}

tasks.jar {
    archiveBaseName.set("excalibur-integration")
    archiveVersion.set(version.toString())
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
    }
    // The same flags the node starts with. --enable-native-access because the engine maps its
    // files through Panama; --add-modules because without the Vector API resolved, the
    // distance kernels fall back to scalar arithmetic silently, and an end-to-end suite would
    // then prove the search path production does not take. No source here imports the
    // incubator package, so the compiler needs no matching flag.
    jvmArgs(
        "--enable-preview",
        "--enable-native-access=ALL-UNNAMED",
        "--add-modules=jdk.incubator.vector"
    )
}
