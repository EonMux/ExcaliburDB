// excalibur-vector (Phase 4) - Vector Index
plugins {
    `java-library`
}


dependencies {
    // SLF4J API for logging
    implementation("org.slf4j:slf4j-api:2.0.16")

    // Test dependencies
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
    testImplementation("org.assertj:assertj-core:3.26.3")

    // Phase 1 dependency
    implementation(project(":excalibur-core"))
    // Phase 3 dependency
    implementation(project(":excalibur-filter"))
    // Config module for Config.Shard
    implementation(project(":excalibur-config"))
}

// Enable Vector API incubator module for SIMD operations
tasks.withType<JavaCompile> {
    options.compilerArgs.add("--add-modules=jdk.incubator.vector")
}

tasks.withType<Test> {
    jvmArgs("--add-modules=jdk.incubator.vector")
    jvmArgs("--enable-preview")
}

tasks.withType<JavaExec> {
    jvmArgs("--add-modules=jdk.incubator.vector")
    jvmArgs("--enable-preview")
}
