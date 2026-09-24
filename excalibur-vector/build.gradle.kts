// excalibur-vector - Vector Index
plugins {
    `java-library`
}


dependencies {
    // SLF4J API for logging
    implementation("org.slf4j:slf4j-api:2.0.16")

    // Test dependencies
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
    testImplementation("org.assertj:assertj-core:3.26.3")

    // Core primitives. api, not implementation: IntegrityReport is the declared return
    // type of HnswGraph.verifyIntegrity and its siblings, so a module that calls one cannot
    // name what it gets back without core on its own compile classpath.
    api(project(":excalibur-core"))
    // Probabilistic structures
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

// The doclet resolves imports like the compiler does, so it needs the incubator module too;
// without it every file importing jdk.incubator.vector fails with "package is not visible".
tasks.withType<Javadoc> {
    (options as StandardJavadocDocletOptions)
        .addStringOption("-add-modules", "jdk.incubator.vector")
}
